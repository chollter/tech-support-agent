package com.gcll.ticketagent.knowledge.rerank;

/**
 * rerank 候选条目。{@code index} 指向原始候选列表的位置，
 * 便于 rerank 后回填原始 {@link com.gcll.ticketagent.knowledge.KnowledgeHit} 的完整字段。
 */
public record RerankEntry(int index, String text) {
}
