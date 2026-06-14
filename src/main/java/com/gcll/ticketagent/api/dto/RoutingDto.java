package com.gcll.ticketagent.api.dto;

import java.util.List;

public record RoutingDto(
        String primaryTeam,
        List<String> backupTeams,
        String routingReason
) {
}
