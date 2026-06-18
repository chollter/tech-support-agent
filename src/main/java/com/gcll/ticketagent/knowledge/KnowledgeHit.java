package com.gcll.ticketagent.knowledge;

public record KnowledgeHit(
        String sourceId,
        String sourceType,
        String title,
        String summary,
        String resolution,
        double score,
        String matchedReason,
        boolean reranked
) {

    /** 向后兼容：默认 reranked=false（未经过 rerank 的命中）。 */
    public KnowledgeHit(String sourceId, String sourceType, String title, String summary,
                        String resolution, double score, String matchedReason) {
        this(sourceId, sourceType, title, summary, resolution, score, matchedReason, false);
    }
}
