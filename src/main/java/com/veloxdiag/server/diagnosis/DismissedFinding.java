package com.veloxdiag.server.diagnosis;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Records that a specific (applicationName, endpoint, ruleType) finding has
 * been dismissed — e.g. an admin-only endpoint that's supposed to be slow,
 * a known batch job, or something already triaged and accepted as
 * low-priority. Keyed by that fingerprint rather than a finding row id,
 * since findings themselves aren't persisted between diagnosis runs — this
 * is what lets a dismissal survive across re-runs instead of only
 * suppressing whatever happened to be on screen at the moment of dismissal.
 *
 * Deliberately narrower than the whole-rule enable/disable toggle on the
 * Rules page: dismissing here silences one rule on one endpoint, leaving
 * that same rule fully active everywhere else.
 *
 * endpoint is stored normalized (see EndpointNormalizer) so a dismissal
 * matches regardless of which concrete id/UUID happened to appear in the
 * endpoint path at dismiss-time.
 */
@Entity
@Table(name = "dismissed_finding")
public class DismissedFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable — null/blank applicationName means "no app filter" scope,
    // consistent with how DiagnosisService treats a missing applicationName
    // elsewhere (see DiagnosisService.resolveKey).
    private String applicationName;

    private String endpoint;   // normalized form
    private String ruleType;
    private String note;       // optional free-text reason for dismissing

    private LocalDateTime dismissedAt;

    public DismissedFinding() {
    }

    public DismissedFinding(String applicationName, String endpoint, String ruleType, String note,
                             LocalDateTime dismissedAt) {
        this.applicationName = applicationName;
        this.endpoint = endpoint;
        this.ruleType = ruleType;
        this.note = note;
        this.dismissedAt = dismissedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(LocalDateTime dismissedAt) { this.dismissedAt = dismissedAt; }
}