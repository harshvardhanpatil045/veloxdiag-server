package com.veloxdiag.server.diagnosis;

import com.veloxdiag.server.diagnosis.engine.RuleEngineService;
import com.veloxdiag.server.entity.SlowQueryPlan;
import com.veloxdiag.server.entity.Telemetry;
import com.veloxdiag.server.repository.SlowQueryPlanRepository;
import com.veloxdiag.server.repository.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the deterministic diagnosis rules in DiagnosisService — the
 * component making the product's core claim ("VeloxDiag diagnoses correctly").
 *
 * Strategy: bypass the DB entirely and drive getFindingsForEndpoint()'s inner
 * computeEndpointFindings() logic via hand-built Telemetry/SlowQueryPlan lists.
 * Repositories are mocked with Mockito; RuleEngineService and
 * TelemetryWindowSettings are lightweight collaborators, also mocked so this
 * suite tests DiagnosisService's own logic in isolation, not integration
 * behavior (that's a separate concern for an @SpringBootTest suite later).
 */
class DiagnosisServiceTest {

    private TelemetryRepository telemetryRepository;
    private TelemetryWindowSettings windowSettings;
    private RuleEngineService ruleEngineService;
    private SlowQueryPlanRepository slowQueryPlanRepository;
    private FixSnapshotRepository fixSnapshotRepository;
    private DismissedFindingService dismissedFindingService;
    private DiagnosisService diagnosisService;

    private static final String ENDPOINT = "/api/exams/{id}";

    @BeforeEach
    void setUp() {
        telemetryRepository = mock(TelemetryRepository.class);
        windowSettings = mock(TelemetryWindowSettings.class);
        ruleEngineService = mock(RuleEngineService.class);
        slowQueryPlanRepository = mock(SlowQueryPlanRepository.class);
        fixSnapshotRepository = mock(FixSnapshotRepository.class);
        dismissedFindingService = mock(DismissedFindingService.class);

        when(windowSettings.getLookbackDays(any())).thenReturn(7);
        when(ruleEngineService.evaluate(anyString(), any())).thenReturn(List.of());
        when(ruleEngineService.evaluate(anyString(), any(), any())).thenReturn(List.of());
        lenient().when(fixSnapshotRepository.findFirstByEndpointAndRuleTypeOrderByMarkedFixedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(fixSnapshotRepository.findFirstByApplicationNameAndEndpointAndRuleTypeOrderByMarkedFixedAtDesc(
                        anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(slowQueryPlanRepository.findByEndpointAndContainsSeqScanTrueAndTimestampAfter(anyString(), any()))
                .thenReturn(List.of());
        lenient().when(dismissedFindingService.getDismissedFingerprints(any()))
                .thenReturn(java.util.Map.of());

        diagnosisService = new DiagnosisService(
                telemetryRepository, windowSettings, ruleEngineService,
                slowQueryPlanRepository, fixSnapshotRepository, dismissedFindingService);
    }

    // ---------- helpers ----------

    private Telemetry telemetry(long durationMs, int status, Long queryCount, LocalDateTime timestamp) {
        Telemetry t = new Telemetry("CET_CELL", ENDPOINT, "GET", status, durationMs, timestamp);
        t.setQueryCount(queryCount);
        return t;
    }

    /** n identical records, evenly spaced 1 minute apart ending "now". */
    private List<Telemetry> uniformRecords(int n, long durationMs, int status, Long queryCount) {
        List<Telemetry> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < n; i++) {
            records.add(telemetry(durationMs, status, queryCount, now.minusMinutes(n - i)));
        }
        return records;
    }

    private List<DiagnosisFinding> runFor(List<Telemetry> records) {
        when(telemetryRepository.findByTimestampAfter(any())).thenReturn(records);
        return diagnosisService.getFindingsForEndpoint(ENDPOINT, null);
    }

    private Optional<DiagnosisFinding> find(List<DiagnosisFinding> findings, String ruleType) {
        return findings.stream().filter(f -> f.getRuleType().equals(ruleType)).findFirst();
    }

    // ---------- SLOW_REQUEST ----------

    @Nested
    @DisplayName("checkSlowRequest")
    class SlowRequest {

        @Test
        @DisplayName("does not fire when average duration is below threshold")
        void noFindingWhenFast() {
            // default threshold is 1000ms
            List<Telemetry> records = uniformRecords(10, 200, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "SLOW_REQUEST")).isEmpty();
        }

        @Test
        @DisplayName("fires with HIGH severity and HIGH confidence for consistent slow requests with enough samples")
        void firesHighSeverityHighConfidence() {
            // all requests exactly 6000ms -> zero variance -> CV = 0 -> HIGH confidence
            List<Telemetry> records = uniformRecords(10, 6000, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);

            DiagnosisFinding finding = find(findings, "SLOW_REQUEST")
                    .orElseThrow(() -> new AssertionError("expected SLOW_REQUEST finding"));
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
            assertThat(finding.getConfidence()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("downgrades to LOW severity and LOW confidence when sample count is below minimum")
        void lowConfidenceWithFewSamples() {
            // MIN_SAMPLE_COUNT is 6 — 3 samples should be treated as insufficient
            List<Telemetry> records = uniformRecords(3, 6000, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);

            DiagnosisFinding finding = find(findings, "SLOW_REQUEST")
                    .orElseThrow(() -> new AssertionError("expected SLOW_REQUEST finding"));
            assertThat(finding.getSeverity()).isEqualTo("LOW");
            assertThat(finding.getConfidence()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("does not treat a single early spike as insufficient-sample when total count is high, but flags high variance as LOW confidence")
        void highVarianceLowersConfidence() {
            // 9 fast requests + 1 very slow one -> average still above threshold,
            // but high coefficient of variation should push confidence down from HIGH.
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < 9; i++) {
                records.add(telemetry(100, 200, 2L, now.minusMinutes(20 - i)));
            }
            records.add(telemetry(15000, 200, 2L, now));

            List<DiagnosisFinding> findings = runFor(records);
            DiagnosisFinding finding = find(findings, "SLOW_REQUEST")
                    .orElseThrow(() -> new AssertionError("expected SLOW_REQUEST finding"));
            // average = (9*100 + 15000)/10 = 1590ms, above 1000ms threshold
            assertThat(finding.getConfidence()).isNotEqualTo("HIGH");
        }
    }

    // ---------- POSSIBLE_N_PLUS_ONE ----------

    @Nested
    @DisplayName("checkPossibleNPlusOne")
    class NPlusOne {

        @Test
        @DisplayName("does not fire when query count stays under threshold")
        void noFindingWhenQueryCountLow() {
            // default threshold is 15
            List<Telemetry> records = uniformRecords(10, 300, 200, 5L);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "POSSIBLE_N_PLUS_ONE")).isEmpty();
        }

        @Test
        @DisplayName("fires HIGH severity and HIGH confidence when query count spikes recur in most requests")
        void firesHighSeverityHighConfidence() {
            // all 10 requests spike to 60 queries -> 100% recurrence -> HIGH confidence, HIGH severity (>50)
            List<Telemetry> records = uniformRecords(10, 800, 200, 60L);
            List<DiagnosisFinding> findings = runFor(records);

            DiagnosisFinding finding = find(findings, "POSSIBLE_N_PLUS_ONE")
                    .orElseThrow(() -> new AssertionError("expected POSSIBLE_N_PLUS_ONE finding"));
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
            assertThat(finding.getConfidence()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("lowers confidence when the spike recurs in only a minority of requests")
        void lowRecurrenceLowersConfidence() {
            // 1 spiky request out of 10 -> recurrence 10% -> LOW confidence
            List<Telemetry> records = uniformRecords(9, 300, 200, 3L);
            records.add(telemetry(800, 200, 60L, LocalDateTime.now()));

            List<DiagnosisFinding> findings = runFor(records);
            DiagnosisFinding finding = find(findings, "POSSIBLE_N_PLUS_ONE")
                    .orElseThrow(() -> new AssertionError("expected POSSIBLE_N_PLUS_ONE finding"));
            assertThat(finding.getConfidence()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("ignores records with a null query count instead of throwing")
        void ignoresNullQueryCounts() {
            List<Telemetry> records = uniformRecords(10, 300, 200, null);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "POSSIBLE_N_PLUS_ONE")).isEmpty();
        }
    }

    // ---------- ROOT_CAUSE_CORRELATION ----------

    @Nested
    @DisplayName("correlateFindings (ROOT_CAUSE_CORRELATION)")
    class RootCauseCorrelation {

        @Test
        @DisplayName("does not fire when only one of SLOW_REQUEST / POSSIBLE_N_PLUS_ONE is present")
        void noCorrelationWithOnlyOneFinding() {
            // slow but query count always low -> SLOW_REQUEST fires, N+1 does not
            List<Telemetry> records = uniformRecords(10, 6000, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);

            assertThat(find(findings, "SLOW_REQUEST")).isPresent();
            assertThat(find(findings, "POSSIBLE_N_PLUS_ONE")).isEmpty();
            assertThat(find(findings, "ROOT_CAUSE_CORRELATION")).isEmpty();
        }

        @Test
        @DisplayName("fires HIGH confidence when high-query-count requests are at least 2x slower than normal ones")
        void firesHighConfidenceWhenStronglyCorrelated() {
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            // normal requests: low query count, fast (avg 400ms) -> above MIN_SAMPLE_COUNT (6)
            for (int i = 0; i < 8; i++) {
                records.add(telemetry(400, 200, 3L, now.minusMinutes(30 - i)));
            }
            // spiky requests: high query count, slow (avg 3000ms) -> 7.5x slower than normal
            for (int i = 0; i < 8; i++) {
                records.add(telemetry(3000, 200, 60L, now.minusMinutes(10 - i)));
            }

            List<DiagnosisFinding> findings = runFor(records);

            assertThat(find(findings, "SLOW_REQUEST")).isPresent();
            assertThat(find(findings, "POSSIBLE_N_PLUS_ONE")).isPresent();
            DiagnosisFinding correlation = find(findings, "ROOT_CAUSE_CORRELATION")
                    .orElseThrow(() -> new AssertionError("expected ROOT_CAUSE_CORRELATION finding"));
            assertThat(correlation.getConfidence()).isEqualTo("HIGH");
            assertThat(correlation.getRelatedFindings()).contains("SLOW_REQUEST", "POSSIBLE_N_PLUS_ONE");
        }

        @Test
        @DisplayName("does not overclaim: LOW confidence when spiky and normal requests take about the same time")
        void lowConfidenceWhenNotActuallyCorrelated() {
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            // normal requests: low query count, SLOW anyway (avg 6000ms)
            for (int i = 0; i < 8; i++) {
                records.add(telemetry(6000, 200, 3L, now.minusMinutes(30 - i)));
            }
            // spiky requests: high query count, but NOT meaningfully slower (avg 6000ms too)
            for (int i = 0; i < 8; i++) {
                records.add(telemetry(6000, 200, 60L, now.minusMinutes(10 - i)));
            }

            List<DiagnosisFinding> findings = runFor(records);
            DiagnosisFinding correlation = find(findings, "ROOT_CAUSE_CORRELATION")
                    .orElseThrow(() -> new AssertionError("expected ROOT_CAUSE_CORRELATION finding"));
            // duration ratio ~1.0 -> should NOT claim HIGH confidence causation
            assertThat(correlation.getConfidence()).isEqualTo("LOW");
        }
    }

    // ---------- PERFORMANCE_REGRESSION ----------

    @Nested
    @DisplayName("checkPerformanceRegression")
    class Regression {

        @Test
        @DisplayName("does not fire with too few total samples to establish a baseline")
        void noFindingWithFewSamples() {
            // needs >= MIN_BASELINE_SAMPLE_COUNT * 2 = 30 total records
            List<Telemetry> records = uniformRecords(10, 500, 200, 3L);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "PERFORMANCE_REGRESSION")).isEmpty();
        }

        @Test
        @DisplayName("fires when current-window mean is a statistically real jump above baseline")
        void firesOnRealRegression() {
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime start = LocalDateTime.now().minusHours(2);
            // baseline half: 20 requests, ~300ms, with a little natural jitter for a non-zero std dev
            for (int i = 0; i < 20; i++) {
                long duration = 290 + (i % 5) * 5; // 290..310ms
                records.add(telemetry(duration, 200, 3L, start.plusMinutes(i)));
            }
            // current half: 20 requests, ~1200ms — clear regression
            for (int i = 0; i < 20; i++) {
                long duration = 1190 + (i % 5) * 5;
                records.add(telemetry(duration, 200, 3L, start.plusMinutes(60 + i)));
            }

            List<DiagnosisFinding> findings = runFor(records);
            DiagnosisFinding finding = find(findings, "PERFORMANCE_REGRESSION")
                    .orElseThrow(() -> new AssertionError("expected PERFORMANCE_REGRESSION finding"));
            assertThat(finding.getSeverity()).isIn("HIGH", "MEDIUM");
        }

        @Test
        @DisplayName("does not fire when current window performs the same as baseline")
        void noRegressionWhenStable() {
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime start = LocalDateTime.now().minusHours(2);
            for (int i = 0; i < 40; i++) {
                long duration = 290 + (i % 5) * 5;
                records.add(telemetry(duration, 200, 3L, start.plusMinutes(i)));
            }
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "PERFORMANCE_REGRESSION")).isEmpty();
        }
    }

    // ---------- MISSING_INDEX_CANDIDATE ----------

    @Nested
    @DisplayName("checkMissingIndexCandidate")
    class MissingIndex {

        @Test
        @DisplayName("does not fire when no seq-scan plans are captured")
        void noFindingWithoutPlans() {
            List<Telemetry> records = uniformRecords(6, 200, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "MISSING_INDEX_CANDIDATE")).isEmpty();
        }

        @Test
        @DisplayName("does not fire when seq-scanned table is below the row threshold (small table is fine)")
        void noFindingWhenTableIsSmall() {
            when(slowQueryPlanRepository.findByEndpointAndContainsSeqScanTrueAndTimestampAfter(anyString(), any()))
                    .thenReturn(List.of(new SlowQueryPlan("CET_CELL", ENDPOINT, LocalDateTime.now(), 1500L,
                            "SELECT * FROM exam_questions",
                            "Seq Scan on exam_questions  (cost=0.00..12.00 rows=20 width=64)",
                            true)));

            List<Telemetry> records = uniformRecords(6, 200, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);
            // default seqScanRowThreshold is 500 — 20 rows should not trigger
            assertThat(find(findings, "MISSING_INDEX_CANDIDATE")).isEmpty();
        }

        @Test
        @DisplayName("fires as a Candidate (never 'Confirmed') when a large table is seq-scanned, with severity scaled to row count")
        void firesForLargeTableSeqScan() {
            when(slowQueryPlanRepository.findByEndpointAndContainsSeqScanTrueAndTimestampAfter(anyString(), any()))
                    .thenReturn(List.of(new SlowQueryPlan("CET_CELL", ENDPOINT, LocalDateTime.now(), 4000L,
                            "SELECT * FROM exam_questions",
                            "Seq Scan on exam_questions  (cost=0.00..900.00 rows=8310 width=64)",
                            true)));

            List<Telemetry> records = uniformRecords(6, 200, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);

            DiagnosisFinding finding = find(findings, "MISSING_INDEX_CANDIDATE")
                    .orElseThrow(() -> new AssertionError("expected MISSING_INDEX_CANDIDATE finding"));
            // rule: >10000 rows -> HIGH, >2000 -> MEDIUM, else LOW. 8310 rows -> MEDIUM.
            assertThat(finding.getSeverity()).isEqualTo("MEDIUM");
            assertThat(finding.getRuleType()).isEqualTo("MISSING_INDEX_CANDIDATE");
            // guards against copy drift back to an overclaimed "confirmed" wording (see Phase 1.3)
            assertThat(finding.getMessage()).doesNotContainIgnoringCase("confirmed");
        }

        @Test
        @DisplayName("scales severity to HIGH when the seq-scanned table is very large")
        void firesHighSeverityForVeryLargeTable() {
            when(slowQueryPlanRepository.findByEndpointAndContainsSeqScanTrueAndTimestampAfter(anyString(), any()))
                    .thenReturn(List.of(new SlowQueryPlan("CET_CELL", ENDPOINT, LocalDateTime.now(), 6000L,
                            "SELECT * FROM exam_questions",
                            "Seq Scan on exam_questions  (cost=0.00..9000.00 rows=250000 width=64)",
                            true)));

            List<Telemetry> records = uniformRecords(6, 200, 200, 2L);
            List<DiagnosisFinding> findings = runFor(records);

            DiagnosisFinding finding = find(findings, "MISSING_INDEX_CANDIDATE")
                    .orElseThrow(() -> new AssertionError("expected MISSING_INDEX_CANDIDATE finding"));
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
        }
    }

    // ---------- HIGH_ERROR_RATE / SERVER_ERROR ----------

    @Nested
    @DisplayName("checkHighErrorRate and checkServerErrors")
    class Errors {

        @Test
        @DisplayName("fires HIGH_ERROR_RATE once error count reaches the threshold")
        void firesHighErrorRate() {
            // default highErrorRateThreshold is 3
            List<Telemetry> records = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < 4; i++) {
                records.add(telemetry(200, 404, 1L, now.minusMinutes(i)));
            }
            for (int i = 0; i < 6; i++) {
                records.add(telemetry(200, 200, 1L, now.minusMinutes(10 + i)));
            }
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "HIGH_ERROR_RATE")).isPresent();
        }

        @Test
        @DisplayName("fires SERVER_ERROR (HIGH severity) whenever any 5xx status is present, regardless of count")
        void firesServerErrorOnAnySingle5xx() {
            List<Telemetry> records = uniformRecords(9, 200, 200, 1L);
            records.add(telemetry(200, 500, 1L, LocalDateTime.now()));

            List<DiagnosisFinding> findings = runFor(records);
            DiagnosisFinding finding = find(findings, "SERVER_ERROR")
                    .orElseThrow(() -> new AssertionError("expected SERVER_ERROR finding"));
            assertThat(finding.getSeverity()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("does not fire either error finding for an all-2xx endpoint")
        void noErrorFindingsWhenHealthy() {
            List<Telemetry> records = uniformRecords(10, 200, 200, 1L);
            List<DiagnosisFinding> findings = runFor(records);
            assertThat(find(findings, "HIGH_ERROR_RATE")).isEmpty();
            assertThat(find(findings, "SERVER_ERROR")).isEmpty();
        }
    }
}