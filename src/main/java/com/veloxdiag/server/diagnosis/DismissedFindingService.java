package com.veloxdiag.server.diagnosis;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Per-finding dismiss/suppress. Lets one specific (endpoint, ruleType)
 * finding be silenced without touching the rule everywhere else — see
 * DismissedFinding for why this is a fingerprint rather than a row id.
 *
 * DiagnosisService consults this to MARK matching findings as dismissed
 * (via DiagnosisFinding.dismissedInfo) rather than dropping them from the
 * response entirely: a muted-but-visible finding can still be restored
 * from the same card, and a fully separate "how many things are silenced
 * right now" view stays possible via listDismissed(). Hiding outright would
 * make dismissals invisible and unmanageable, which is exactly the failure
 * mode this feature exists to avoid.
 */
@Service
public class DismissedFindingService {

    private final DismissedFindingRepository dismissedFindingRepository;

    public DismissedFindingService(DismissedFindingRepository dismissedFindingRepository) {
        this.dismissedFindingRepository = dismissedFindingRepository;
    }

    private static boolean isBlank(String applicationName) {
        return applicationName == null || applicationName.isBlank();
    }

    public DismissedFinding dismiss(String applicationName, String endpoint, String ruleType, String note) {
        String normalizedEndpoint = EndpointNormalizer.normalize(endpoint);

        Optional<DismissedFinding> existing = isBlank(applicationName)
                ? dismissedFindingRepository.findByEndpointAndRuleTypeAndApplicationNameIsNull(normalizedEndpoint, ruleType)
                : dismissedFindingRepository.findByApplicationNameAndEndpointAndRuleType(applicationName, normalizedEndpoint, ruleType);

        DismissedFinding dismissedFinding = existing.orElseGet(DismissedFinding::new);
        dismissedFinding.setApplicationName(isBlank(applicationName) ? null : applicationName);
        dismissedFinding.setEndpoint(normalizedEndpoint);
        dismissedFinding.setRuleType(ruleType);
        dismissedFinding.setNote(note);
        dismissedFinding.setDismissedAt(LocalDateTime.now());

        return dismissedFindingRepository.save(dismissedFinding);
    }

    public void undismiss(String applicationName, String endpoint, String ruleType) {
        String normalizedEndpoint = EndpointNormalizer.normalize(endpoint);
        if (isBlank(applicationName)) {
            dismissedFindingRepository.deleteByEndpointAndRuleTypeAndApplicationNameIsNull(normalizedEndpoint, ruleType);
        } else {
            dismissedFindingRepository.deleteByApplicationNameAndEndpointAndRuleType(applicationName, normalizedEndpoint, ruleType);
        }
    }

    public List<DismissedFinding> listDismissed(String applicationName) {
        return isBlank(applicationName)
                ? dismissedFindingRepository.findAllByOrderByDismissedAtDesc()
                : dismissedFindingRepository.findByApplicationNameOrderByDismissedAtDesc(applicationName);
    }

    private static String fingerprint(String endpoint, String ruleType) {
        return endpoint + "::" + ruleType;
    }

    /**
     * Fingerprint -> DismissedFinding lookup for everything dismissed in the
     * given application scope, keyed by (normalized endpoint, ruleType).
     * DiagnosisService calls this once per diagnosis run and does an O(1)
     * lookup per finding rather than querying per-finding.
     */
    public Map<String, DismissedFinding> getDismissedFingerprints(String applicationName) {
        return listDismissed(applicationName).stream()
                .collect(Collectors.toMap(
                        d -> fingerprint(d.getEndpoint(), d.getRuleType()),
                        d -> d,
                        (first, second) -> first));
    }

    public static String fingerprintOf(String endpoint, String ruleType) {
        return fingerprint(endpoint, ruleType);
    }
}