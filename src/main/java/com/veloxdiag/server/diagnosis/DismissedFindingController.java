package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Per-finding dismiss/suppress — quiets one specific (endpoint, ruleType)
 * finding without touching the rule everywhere else. See DismissedFinding
 * and DismissedFindingService for the fingerprint rationale.
 */
@RestController
@RequestMapping("/api/dismissed-findings")
public class DismissedFindingController {

    private final DismissedFindingService dismissedFindingService;

    public DismissedFindingController(DismissedFindingService dismissedFindingService) {
        this.dismissedFindingService = dismissedFindingService;
    }

    @PostMapping
    public DismissedFinding dismiss(@RequestParam String applicationName,
                                     @RequestParam String endpoint,
                                     @RequestParam String ruleType,
                                     @RequestParam(required = false) String note) {
        return dismissedFindingService.dismiss(applicationName, endpoint, ruleType, note);
    }

    @DeleteMapping
    public void undismiss(@RequestParam(required = false) String applicationName,
                           @RequestParam String endpoint,
                           @RequestParam String ruleType) {
        dismissedFindingService.undismiss(applicationName, endpoint, ruleType);
    }

    @GetMapping
    public List<DismissedFinding> getDismissed(@RequestParam(required = false) String applicationName) {
        return dismissedFindingService.listDismissed(applicationName);
    }
}