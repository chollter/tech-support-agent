package com.gcll.ticketagent.governance.routing;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class RoutingPolicyEngine {

    public RoutingResult merge(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        if (ruleResult == null) {
            throw new IllegalArgumentException("ruleResult must not be null");
        }
        if (suggestion == null || !suggestion.hasSuggestion()) {
            return ruleResult;
        }

        String primaryTeam = ruleResult.primaryTeam();
        List<String> backupTeams = mergeBackupTeams(ruleResult, suggestion);
        String routingReason = buildReason(ruleResult, suggestion);
        double confidence = adjustConfidence(ruleResult, suggestion, primaryTeam);

        return new RoutingResult(primaryTeam, backupTeams, routingReason, confidence);
    }

    private List<String> mergeBackupTeams(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (ruleResult.backupTeams() != null) {
            merged.addAll(ruleResult.backupTeams());
        }
        if (merged.isEmpty() && suggestion.suggestedBackupTeams() != null) {
            for (String team : suggestion.suggestedBackupTeams()) {
                if (team != null && !team.isBlank() && !team.equals(ruleResult.primaryTeam())) {
                    merged.add(team);
                }
            }
        }
        return List.copyOf(merged);
    }

    private String buildReason(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        String baseReason = ruleResult.routingReason();
        if (suggestion.suggestedPrimaryTeam().equals(ruleResult.primaryTeam())) {
            if (suggestion.reason() != null && !suggestion.reason().isBlank()) {
                return baseReason + "；" + suggestion.reason();
            }
            return baseReason;
        }
        return baseReason + "（LLM 建议 " + suggestion.suggestedPrimaryTeam()
                + "，规则裁决为 " + ruleResult.primaryTeam() + "）";
    }

    private double adjustConfidence(RoutingResult ruleResult, RoutingSuggestion suggestion, String primaryTeam) {
        if (primaryTeam.equals(suggestion.suggestedPrimaryTeam()) && suggestion.confidence() > ruleResult.confidence()) {
            return Math.min(1.0, (ruleResult.confidence() + suggestion.confidence()) / 2.0);
        }
        return ruleResult.confidence();
    }
}
