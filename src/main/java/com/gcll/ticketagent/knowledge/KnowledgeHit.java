package com.gcll.ticketagent.knowledge;

public record KnowledgeHit(
        String sourceId,
        String sourceType,
        String title,
        String summary,
        String resolution,
        double score,
        String matchedReason
) {
}
