package com.gcll.ticketagent.knowledge.rerank;

import java.util.List;

/**
 * 检索结果重排序端口。
 * <p>向量召回粗排后，用 rerank 模型对候选按 query 相关性精排，提升 top-K 质量。
 * 默认实现 {@link NoopRerankService}（保持原始顺序），DashScope 实现见 {@link DashScopeRerankService}。
 * <p>失败降级：实现内部应捕获异常并返回原始候选（保持召回结果不丢），与系统级降级理念一致。
 */
public interface RerankService {

    /**
     * 对候选文本按 query 相关性重排序。
     *
     * @param query     原始查询
     * @param candidates 候选条目（向量召回结果，顺序为向量相似度排序）
     * @param topN      返回前 N 个
     * @return 重排序后的前 topN 条目；rerank 不可用时返回原始前 topN
     */
    List<RerankEntry> rerank(String query, List<RerankEntry> candidates, int topN);

    /**
     * 是否实际执行重排序（用于审计区分 rerank 命中 vs 原始召回顺序）。
     */
    boolean active();
}
