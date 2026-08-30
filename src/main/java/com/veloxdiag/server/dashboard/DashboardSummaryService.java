package com.veloxdiag.server.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloxdiag.server.diagnosis.DiagnosisFinding;
import com.veloxdiag.server.diagnosis.DiagnosisService;
import com.veloxdiag.server.diagnosis.ApiKeyRotator;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI wow feature #2 — one AI-written paragraph at the top of the Dashboard
 * summarizing current state across all monitored endpoints. Reuses the same
 * Groq call plumbing pattern as NarrativeService/RecommendationService
 * (own MODEL/API_URL/keyRotator/httpClient — this project deliberately keeps
 * each AI service's HTTP plumbing self-contained rather than sharing a base
 * class, same convention as the other two services).
 *
 * Data fed to the model is all real: health score (DashboardService), active
 * findings (DiagnosisService.runDiagnosis), current slow endpoints, and a
 * trend comparison (first half vs second half of the lookback window's
 * hourly buckets) computed here from real TrendPointDTO numbers — never an
 * invented "today vs yesterday" claim. If the trend window has too few
 * buckets to compare, the prompt is told so explicitly and instructed not to
 * claim a direction.
 */
@Service
public class DashboardSummaryService {

    private static final String MODEL = "openai/gpt-oss-120b"; // migrated off llama-3.3-70b-versatile — Groq deprecated it (announced Jun 17, 2026)
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are a senior backend engineer writing a short status update at the top of a performance " +
            "dashboard, meant to be read in about 5 seconds by an engineer who just opened the page. Given " +
            "the health score, active findings grouped by endpoint, the current slow endpoints, and a trend " +
            "comparison between the first half and second half of the lookback window, write 2-3 sentences " +
            "covering: which endpoint(s) are the biggest problem right now, and whether things are trending " +
            "worse, better, or flat. Refer to rule types in plain English (say 'an N+1 query pattern' " +
            "instead of shouting 'POSSIBLE_N_PLUS_ONE'). Only state a trend direction if the trend data " +
            "provided actually shows one clearly — if it's flat, mixed, or marked as not enough data, say " +
            "that plainly instead of guessing. Never invent an endpoint name, table name, or number that " +
            "isn't present in the input. Write it like a teammate's quick status note, not a report header " +
            "— no bullet points, no bold labels, just plain sentences.";

    private final ApiKeyRotator keyRotator;
    private final DashboardService dashboardService;
    private final DiagnosisService diagnosisService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DashboardSummaryService(ApiKeyRotator keyRotator, DashboardService dashboardService,
                                    DiagnosisService diagnosisService) {
        this.keyRotator = keyRotator;
        this.dashboardService = dashboardService;
        this.diagnosisService = diagnosisService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, String> generateSummary(String applicationName) {
        List<DiagnosisFinding> findings = diagnosisService.runDiagnosis(applicationName);
        List<SlowEndpointDTO> slowEndpoints = dashboardService.getSlowEndpoints(5, applicationName);

        // Nothing to explain — don't burn a Groq call on an empty state, and
        // don't let the model pad an all-clear into three sentences.
        if (findings.isEmpty() && slowEndpoints.isEmpty()) {
            return Map.of("summary", "No active findings or slow endpoints right now — everything's within normal range.");
        }

        DashboardSummary summary = dashboardService.getSummary(applicationName);
        List<TrendPointDTO> trend = dashboardService.getTrends(24, applicationName);

        String userPrompt = buildPrompt(summary, findings, slowEndpoints, trend);
        try {
            return Map.of("summary", callGroq(userPrompt));
        } catch (Exception e) {
            String msg = e instanceof IllegalStateException
                    ? "Dashboard summary isn't configured (missing GROQ_API_KEY)."
                    : "Dashboard summary generation failed: " + e.getMessage();
            return Map.of("summary", msg);
        }
    }

    private String buildPrompt(DashboardSummary summary, List<DiagnosisFinding> findings,
                                List<SlowEndpointDTO> slowEndpoints, List<TrendPointDTO> trend) {
        StringBuilder sb = new StringBuilder();
        sb.append("Health score: ").append(summary.getHealthScore()).append("/100\n\n");

        Map<String, List<DiagnosisFinding>> byEndpoint = findings.stream()
                .collect(Collectors.groupingBy(DiagnosisFinding::getEndpoint, LinkedHashMap::new, Collectors.toList()));

        sb.append("Active findings by endpoint:\n");
        if (byEndpoint.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Map.Entry<String, List<DiagnosisFinding>> e : byEndpoint.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ");
                sb.append(e.getValue().stream()
                        .map(f -> f.getRuleType() + " (" + f.getSeverity() + ")")
                        .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }

        sb.append("\nCurrent slow endpoints (avg duration, sample count):\n");
        if (slowEndpoints.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (SlowEndpointDTO ep : slowEndpoints) {
                sb.append("- ").append(ep.getEndpoint()).append(": ")
                        .append(Math.round(ep.getAvgDuration())).append("ms avg, ")
                        .append(ep.getCount()).append(" samples\n");
            }
        }

        sb.append("\nTrend (first half vs second half of lookback window): ").append(describeTrend(trend));

        return sb.toString();
    }

    // Compares avg duration and error count between the first half and
    // second half of the hourly trend buckets — a real, computed comparison,
    // not an invented "today vs yesterday" framing. Needs at least 4 buckets
    // to split meaningfully; below that, tells the model plainly there's not
    // enough data rather than letting it guess a direction from noise.
    private String describeTrend(List<TrendPointDTO> trend) {
        if (trend == null || trend.size() < 4) {
            return "Not enough data points to determine a trend — say so, don't guess a direction.";
        }

        int mid = trend.size() / 2;
        List<TrendPointDTO> firstHalf = trend.subList(0, mid);
        List<TrendPointDTO> secondHalf = trend.subList(mid, trend.size());

        double firstAvgDuration = firstHalf.stream().mapToDouble(TrendPointDTO::getAvgDuration).average().orElse(0.0);
        double secondAvgDuration = secondHalf.stream().mapToDouble(TrendPointDTO::getAvgDuration).average().orElse(0.0);
        long firstErrors = firstHalf.stream().mapToLong(TrendPointDTO::getErrorCount).sum();
        long secondErrors = secondHalf.stream().mapToLong(TrendPointDTO::getErrorCount).sum();

        double pctChange = firstAvgDuration == 0.0 ? 0.0 : ((secondAvgDuration - firstAvgDuration) / firstAvgDuration) * 100.0;

        return String.format(
                "avg duration went from %.0fms (first half) to %.0fms (second half), a %.1f%% change; " +
                "errors went from %d (first half) to %d (second half).",
                firstAvgDuration, secondAvgDuration, pctChange, firstErrors, secondErrors);
    }

    private String buildRequestBody(String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 512);
        root.put("temperature", 0.6);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        return objectMapper.writeValueAsString(root);
    }

    // Same key-rotation-on-429 pattern as NarrativeService.callGroq.
    private String callGroq(String userPrompt) throws Exception {
        if (!keyRotator.hasKeys()) {
            throw new IllegalStateException("missing GROQ_API_KEY");
        }
        String requestBody = buildRequestBody(userPrompt);

        int attempts = Math.max(1, keyRotator.keyCount());
        String lastFailureBody = null;
        int lastStatus = -1;

        for (int i = 0; i < attempts; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + keyRotator.current())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return extractText(response.body());
            }

            lastStatus = response.statusCode();
            lastFailureBody = response.body();

            if (response.statusCode() == 429 && keyRotator.rotate()) {
                continue; // try next key
            }
            break; // non-429 failure, or no more keys to rotate to
        }

        String truncated = lastFailureBody != null && lastFailureBody.length() > 300
                ? lastFailureBody.substring(0, 300) : lastFailureBody;
        throw new RuntimeException("status " + lastStatus + ": " + truncated);
    }

    private String extractText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String text = choices.get(0).path("message").path("content").asText();
            if (!text.isBlank()) {
                return text.trim();
            }
        }
        return "Dashboard summary generation returned an empty response.";
    }
}