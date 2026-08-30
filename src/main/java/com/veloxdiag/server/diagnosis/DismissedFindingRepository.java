package com.veloxdiag.server.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DismissedFindingRepository extends JpaRepository<DismissedFinding, Long> {

    // "Scoped to a specific application" variants.
    List<DismissedFinding> findByApplicationNameOrderByDismissedAtDesc(String applicationName);
    Optional<DismissedFinding> findByApplicationNameAndEndpointAndRuleType(
            String applicationName, String endpoint, String ruleType);
    void deleteByApplicationNameAndEndpointAndRuleType(String applicationName, String endpoint, String ruleType);

    // "No application filter" variants — mirrors the pattern used by
    // FixSnapshotRepository for the same null/blank-applicationName case.
    List<DismissedFinding> findAllByOrderByDismissedAtDesc();
    Optional<DismissedFinding> findByEndpointAndRuleTypeAndApplicationNameIsNull(String endpoint, String ruleType);
    void deleteByEndpointAndRuleTypeAndApplicationNameIsNull(String endpoint, String ruleType);
}