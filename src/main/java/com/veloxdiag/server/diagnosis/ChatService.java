package com.veloxdiag.server.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloxdiag.server.dashboard.DashboardService;
import com.veloxdiag.server.dashboard.DashboardSummary;
import com.veloxdiag.server.dashboard.SlowEndpointDTO;
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
 * AI wow feature #4 — natural-language Q&A over current diagnosis state.
 * Same self-contained HTTP-plumbing convention as the other AI services
 * (own MODEL/API_URL/keyRotator/httpClient), same "rule engine decides,
 * AI only explains" discipline: the AI never invents a finding, table, or
 * number — it can only reference what's actually in the retrieved evidence
 * block, same real data DashboardSummaryService already uses.
 */
@Service
public class ChatService {

    private static final String MODEL = "openai/gpt-oss-120b"; // migrated off llama-3.3-70b-versatile — Groq deprecated it (announced Jun 17, 2026)
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are VeloxDiag's assistant, answering a direct question from an engineer about their " +
            "application's current performance state. You are given the health score, active findings " +
            "grouped by endpoint, and current slow endpoints — this is REAL data from a deterministic rule " +
            "engine, not something you're guessing at. Answer the question directly and specifically, " +
            "referencing real endpoint names and numbers from the data given. Refer to rule types in plain " +
            "English (say 'an N+1 query pattern' instead of shouting 'POSSIBLE_N_PLUS_ONE'). If the data " +
            "given doesn't actually answer the question (e.g. asked about an endpoint with no findings, or " +
            "asked something the data can't show), say that plainly instead of guessing or inventing an " +
            "answer. Never invent an endpoint name, table name, or number that isn't present in the input. " +
            "Answer in 2-4 sentences, like a teammate answering directly in chat — no headers, no bullet " +
            "points, no restating the question back. Vary your opening phrase across different questions " +
            "rather than always starting the same way (e.g. don't begin every answer with 'Based on the " +
            "data' or 'Looking at your findings').";

    private final DiagnosisService diagnosisService;
    private final DashboardService dashboardService;
    private final ApiKeyRotator keyRotator;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ChatService(DiagnosisService diagnosisService, DashboardService dashboardService,
                        ApiKeyRotator keyRotator) {
        this.diagnosisService = diagnosisService;
        this.dashboardService = dashboardService;
        this.keyRotator = keyRotator;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, String> answerQuestion(String question, String applicationName) {
        if (question == null || question.isBlank()) {
            return Map.of("answer", "Ask a question about your application's current performance state.");
        }
        if (!keyRotator.hasKeys()) {
            return Map.of("answer", "Chat isn't configured (missing GROQ_API_KEY).");
        }

        List<DiagnosisFinding> findings = diagnosisService.runDiagnosis(applicationName);
        List<SlowEndpointDTO> slowEndpoints = dashboardService.getSlowEndpoints(10, applicationName);
        DashboardSummary summary = dashboardService.getSummary(applicationName);

        String userPrompt = buildPrompt(question, summary, findings, slowEndpoints);

        try {
            String result = callGroq(userPrompt);
            return Map.of("answer", result);
        } catch (Exception e) {
            System.out.println("[VeloxDiag] ChatService.answerQuestion FAILED — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Map.of("answer", "Couldn't generate an answer — try again.");
        }
    }

    private String buildPrompt(String question, DashboardSummary summary, List<DiagnosisFinding> findings,
                                List<SlowEndpointDTO> slowEndpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question: ").append(question).append("\n\n");
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
                        .map(f -> f.getRuleType() + " (" + f.getSeverity() + ") - " + f.getMessage())
                        .collect(Collectors.joining(" | ")));
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

        return sb.toString();
    }

    private String buildRequestBody(String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 400);
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

    private String callGroq(String userPrompt) throws Exception {
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
            if (response.statusCode() == 429 && keyRotator.rotate()) continue;
            break;
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
            if (!text.isBlank()) return text.trim();
        }
        return "No answer generated.";
    }
}