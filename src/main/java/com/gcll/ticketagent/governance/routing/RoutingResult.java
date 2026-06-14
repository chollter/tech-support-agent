package com.gcll.ticketagent.governance.routing;

import java.util.List;

public record RoutingResult(
        String primaryTeam,
        List<String> backupTeams,
        String routingReason,
        double confidence
) {
}
