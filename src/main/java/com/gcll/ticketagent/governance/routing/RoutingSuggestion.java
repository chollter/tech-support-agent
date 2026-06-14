package com.gcll.ticketagent.governance.routing;

import java.util.List;

public record RoutingSuggestion(
        String suggestedPrimaryTeam,
        List<String> suggestedBackupTeams,
        String reason,
        double confidence,
        boolean llmUsed
) {

    public static RoutingSuggestion empty() {
        return new RoutingSuggestion(null, List.of(), null, 0, false);
    }

    public boolean hasSuggestion() {
        return suggestedPrimaryTeam != null && !suggestedPrimaryTeam.isBlank();
    }
}
