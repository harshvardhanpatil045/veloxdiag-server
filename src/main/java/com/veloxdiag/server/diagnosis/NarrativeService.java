package com.veloxdiag.server.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * LLM provider: Groq (OpenAI-compatible chat completions API), swapped in
 * from Gemini after the Gemini key pool ran out. ApiKeyRotator is reused
 * as-is — it's just a generic key list + rotation, provider-agnostic — only
 * the HTTP call shape changed: Bearer auth header instead of ?key= query
 * param, a messages[] array (system + user) instead of contents[]/parts[],
 * and choices[0].message.content instead of candidates[0].content.parts[0].text.
 * No thinkingConfig equivalent needed — Groq's Llama models aren't reasoning
 * models, so they don't eat into max_tokens the way gemini-flash-latest did.
 */
@Service
public class NarrativeService {

    private static final String MODEL = "openai/gpt-oss-120b"; // migrated off llama-3.3-70b-versatile — Groq deprecated it (announced Jun 17, 2026)
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are a senior backend engineer explaining a performance finding to a teammate, in plain " +
            "conversational language — not a compliance report. Given the findings below (each with a " +
            "rule type, severity, message, and evidence), write a 2-4 sentence explanation of the likely " +
            "root cause. Talk about what's actually happening in normal engineering language (e.g. 'this " +
            "endpoint is firing dozens of extra queries per request' rather than repeating the rule type " +
            "name in capitals like POSSIBLE_N_PLUS_ONE — mention the rule name at most once, only if it " +
            "helps, never as the subject of a sentence). Lead with the most useful insight, not a summary " +
            "of every finding in order. If multiple findings are present, state which is the primary " +
            "driver and which are secondary, using the confidence/ratio data provided where available — " +
            "but vary your sentence structure endpoint to endpoint rather than following the same " +
            "template shape every time. Do not invent numbers, percentages, or facts that are not present " +
            "in the input. If the evidence is inconclusive, say so plainly rather than guessing, but don't " +
            "pad every sentence with hedging — hedge once, clearly, not throughout. " +
            "Match your confidence language to each finding's stated severity: a LOW-severity or " +
            "'possible'/'suspected' finding should read as tentative; don't upgrade it to definitive " +
            "phrasing like 'the primary driver' or 'is caused by' unless the finding itself is HIGH " +
            "severity or stated as confirmed. Never let your narrative sound more certain than the " +
            "underlying evidence, but also don't sound like a legal disclaimer. " +
            "If a 'Captured Query Evidence' section is present below, it contains the endpoint's REAL " +
            "recently-executed SQL and its EXPLAIN plan — use it as your primary evidence when explaining " +
            "the root cause: name the actual table(s), whether it's a sequential scan, and roughly how " +
            "many rows are being scanned, instead of speaking generically about 'the database'. If that " +
            "section is absent, don't imply you've seen the query — describe the pattern from the " +
            "aggregate numbers only. " +
            "If a 'Business Context' line is present below, it's a note from the app's owner describing " +
            "what this endpoint actually does for a real user. Use it to add ONE short clause tying the " +
            "technical root cause to the real-world consequence for that user or the business — e.g. " +
            "'...which matters here because this is the page students hit right before their exam starts, " +
            "so a 2-second delay risks panic re-clicks and duplicate submissions' — instead of stopping at " +
            "the technical explanation alone. Do not invent a business consequence if no Business Context " +
            "line is given; in that case, explain the technical pattern only, exactly as you would today. " +
            "If a 'Data Growth' section is present below, it shows a table's row count growing over time " +
            "based on real captured EXPLAIN plans (earliest vs most recent capture). When present, treat " +
            "it as the answer to WHY THIS STARTED NOW, distinct from WHY IT HAPPENS — e.g. 'this query " +
            "wasn't a problem when exam_attempts had 500 rows, but now that it's grown past 3,000 rows the " +
            "same query pattern is slow.' Only mention growth as the trigger if this section is present; " +
            "never invent or guess that a table has grown when it isn't given.";

    // New: EXPLAIN-plan-to-plain-English translator (AI wow feature #1, Slow Queries page).
    // Low temp (0.3) — this is a factual translation, not creative prose.
    private static final String EXPLAIN_SYSTEM_PROMPT =
            "You translate raw SQL EXPLAIN plan output into ONE plain-English sentence. " +
            "Audience: a developer who doesn't read EXPLAIN plans fluently. Rules: exactly one " +
            "sentence, no preamble, no 'This query...', just the fact. Name the actual table(s) " +
            "and whether it's a sequential scan, index scan, or index-only scan. If a sequential " +
            "scan appears on a large/likely-large table, say so plainly (e.g. 'scans the whole " +
            "exam_questions table'). No hedging, no markdown.";

    private final ApiKeyRotator keyRotator;
    private final SlowQueryPlanRepository slowQueryPlanRepository;
    private final DataGrowthService dataGrowthService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Short-TTL cache keyed on (applicationName, endpoint, sorted rule-type
    // signature). Fixes a real observed problem: the dashboard renders one
    // finding-card per rule type on an endpoint, and each card independently
    // re-requests /api/diagnosis/narrative for the SAME endpoint — since
    // generateNarrative() already takes ALL findings for that endpoint in
    // one call, those repeat requests were identical in substance, just
    // burning through the Groq TPM quota (see the 429s in prod logs) and
    // producing near-duplicate "primary driver..." paragraphs under every
    // card. Caching the endpoint-level result for a few minutes means the
    // first card's request pays for the Groq call, the rest reuse it —
    // cutting both the token burn and the duplicate-text symptom without
    // needing a frontend change. Findings-signature is part of the key so a
    // genuinely new finding (not just a repeat request) still gets a fresh
    // narrative rather than serving stale text.
    private static final long CACHE_TTL_SECONDS = 180;
    private final Map<String, CachedNarrative> narrativeCache = new ConcurrentHashMap<>();

    private static final class CachedNarrative {
        final EndpointNarrative narrative;
        final Instant expiresAt;
        CachedNarrative(EndpointNarrative narrative, Instant expiresAt) {
            this.narrative = narrative;
            this.expiresAt = expiresAt;
        }
    }

    public NarrativeService(ApiKeyRotator keyRotator, SlowQueryPlanRepository slowQueryPlanRepository,
                             DataGrowthService dataGrowthService) {
        this.keyRotator = keyRotator;
        this.slowQueryPlanRepository = slowQueryPlanRepository;
        this.dataGrowthService = dataGrowthService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // Back-compat overload — no applicationName means no business-context lookup,
    // narrative behaves exactly as before this feature existed.
    public EndpointNarrative generateNarrative(String endpoint, List<DiagnosisFinding> findings) {
        return generateNarrative(endpoint, findings, null);
    }

    public EndpointNarrative generateNarrative(String endpoint, List<DiagnosisFinding> findings, String applicationName) {
        List<String> ruleTypes = findings.stream()
                .map(DiagnosisFinding::getRuleType)
                .collect(Collectors.toList());

        if (findings.isEmpty()) {
            return new EndpointNarrative(endpoint,
                    "No findings are currently present for this endpoint, so there's nothing to explain.",
                    ruleTypes);
        }

        String cacheKey = buildCacheKey(applicationName, endpoint, ruleTypes);
        CachedNarrative cached = narrativeCache.get(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.narrative;
        }

        String userPrompt = buildFindingsBlock(endpoint, findings);
        try {
            String text = callGroq(SYSTEM_PROMPT, userPrompt, 0.7);
            EndpointNarrative result = new EndpointNarrative(endpoint, text, ruleTypes);
            narrativeCache.put(cacheKey, new CachedNarrative(result, Instant.now().plusSeconds(CACHE_TTL_SECONDS)));
            return result;
        } catch (Exception e) {
            System.out.println("[VeloxDiag] generateNarrative FAILED for " + endpoint + " — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            String msg = e instanceof IllegalStateException
                    ? "Narrative generation isn't configured (missing GROQ_API_KEY)."
                    : "Narrative generation failed: " + e.getMessage();
            return new EndpointNarrative(endpoint, msg, ruleTypes);
        }
    }

    // New: AI wow feature #1 — plain-English EXPLAIN plan summary for Slow Queries page.
    // Reuses callGroq/keyRotator infra below. Called on-demand (button click), not bulk.
    public String explainPlanInPlainEnglish(SlowQueryPlan plan) {
        if (plan == null || plan.getExplainPlan() == null || plan.getExplainPlan().isBlank()) {
            return "No EXPLAIN output captured for this query.";
        }

        String userPrompt = "SQL:\n" + truncate(plan.getSqlText(), 400) +
                "\n\nEXPLAIN output:\n" + truncate(plan.getExplainPlan(), 400) +
                "\n\nSequential scan flag: " + plan.isContainsSeqScan();

        try {
            return callGroq(EXPLAIN_SYSTEM_PROMPT, userPrompt, 0.3).trim();
        } catch (Exception e) {
            return "Couldn't generate explanation (" + e.getClass().getSimpleName() + ").";
        }
    }

    private String buildFindingsBlock(String endpoint, List<DiagnosisFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Endpoint: ").append(endpoint).append("\n");

        sb.append("\nFindings:\n");
        for (DiagnosisFinding f : findings) {
            sb.append("- [").append(f.getRuleType()).append(", severity=").append(f.getSeverity());
            if (f.getConfidence() != null) {
                sb.append(", confidence=").append(f.getConfidence());
            }
            sb.append("] ").append(f.getMessage());
            if (f.getEvidence() != null) {
                sb.append(" Evidence: ").append(f.getEvidence());
            }
            sb.append("\n");
        }

        String capturedEvidence = buildCapturedEvidenceBlock(endpoint);
        if (capturedEvidence != null) {
            sb.append("\nCaptured Query Evidence (real SQL + EXPLAIN plan recently observed on this endpoint):\n");
            sb.append(capturedEvidence);
        }

        String growthBlock = buildDataGrowthBlock(endpoint);
        if (growthBlock != null) {
            sb.append("\nData Growth (row-count trend from captured EXPLAIN plans over time):\n");
            sb.append(growthBlock);
        }

        return sb.toString();
    }

    // Pulls table growth trends (see DataGrowthService) for this endpoint and
    // formats them for the prompt. Returns null if no table shows meaningful
    // growth (>=15%, >=3 data points) — narrative then answers WHY only, not
    // WHY NOW, exactly as it did before this feature existed.
    private String buildDataGrowthBlock(String endpoint) {
        List<TableGrowthTrend> trends = dataGrowthService.getGrowthTrends(endpoint);
        if (trends.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TableGrowthTrend t : trends) {
            sb.append("- ").append(t.getTableName())
                    .append(": ~").append(t.getEarliestRowCount()).append(" rows (first captured ")
                    .append(t.getEarliestCapturedAt()).append(") -> ~").append(t.getLatestRowCount())
                    .append(" rows (most recent capture ").append(t.getLatestCapturedAt())
                    .append("), +").append(String.format("%.0f", t.getGrowthPercent())).append("% growth")
                    .append(" (based on ").append(t.getDataPoints()).append(" captures)\n");
        }
        return sb.toString();
    }

    // Pulls the most recent real captured SlowQueryPlan(s) for this endpoint —
    // actual SQL text and EXPLAIN output — so the narrative can name real
    // tables/columns instead of speaking generically. Same repository/pattern
    // RecommendationService already used for MISSING_INDEX_CANDIDATE, now
    // shared here for ANY finding type on the endpoint. Returns null if no
    // plan has been captured yet — narrative falls back to aggregate numbers.
    private String buildCapturedEvidenceBlock(String endpoint) {
        List<SlowQueryPlan> plans = slowQueryPlanRepository.findTop3ByEndpointOrderByTimestampDesc(endpoint);
        if (plans == null || plans.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (SlowQueryPlan plan : plans) {
            if (plan.getSqlText() == null || plan.getExplainPlan() == null) continue;
            sb.append("Query ").append(++shown).append(":\n");
            sb.append("SQL: ").append(truncate(plan.getSqlText(), 400)).append("\n");
            sb.append("EXPLAIN: ").append(truncate(plan.getExplainPlan(), 400)).append("\n");
            sb.append("Contains sequential scan: ").append(plan.isContainsSeqScan()).append("\n\n");
            if (shown >= 2) break; // cap at 2 — enough real evidence without bloating the prompt
        }
        return shown > 0 ? sb.toString() : null;
    }

    private String buildCacheKey(String applicationName, String endpoint, List<String> ruleTypes) {
        String app = applicationName == null ? "" : applicationName;
        String signature = ruleTypes.stream().sorted().collect(Collectors.joining(","));
        return app + "::" + endpoint + "::" + signature;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // Builds an OpenAI-compatible chat-completions request body: a system
    // message plus a user message, rather than Gemini's single blob of text.
    // temperature is now a param — narrative prose uses 0.7, EXPLAIN-summary uses 0.3.
    private String buildRequestBody(String systemPrompt, String userPrompt, double temperature) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 2048);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        return objectMapper.writeValueAsString(root);
    }

    // Shared Groq call w/ key rotation, used by both generateNarrative and
    // explainPlanInPlainEnglish. Tries once per available key, only advances
    // on 429 (quota) so unrelated failures don't burn through keys pointlessly.
    // Throws on total failure — caller decides fallback text.
    private String callGroq(String systemPrompt, String userPrompt, double temperature) throws Exception {
        if (!keyRotator.hasKeys()) {
            throw new IllegalStateException("missing GROQ_API_KEY");
        }
        String requestBody = buildRequestBody(systemPrompt, userPrompt, temperature);

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
        return "Narrative generation returned an empty response.";
    }
}