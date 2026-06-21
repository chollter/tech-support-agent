package com.gcll.ticketagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RRF（Reciprocal Rank Fusion）混合检索融合配置。
 * <p>前缀 {@code opsmind.rag.fusion}。控制向量检索与关键词检索的 RRF 融合行为。
 *
 * <h3>RRF 算法说明</h3>
 * <p>对每路检索结果按排名计算 {@code 1/(k + rank)} 并按 sourceId 累加，
 * 融合分越高排越前。只依赖排名不依赖绝对分——因为向量 cosine（0.5~1.0）与
 * 关键词加权分（无上界）量纲不同，加权融合需先归一化，RRF 绕开这个问题。
 *
 * <p>{@code k} 是平滑参数：k 越大头部优势越平、尾部文档越不易被忽略；工业默认 60。
 */
@ConfigurationProperties(prefix = "opsmind.rag.fusion")
public class RrfProperties {

    /** 融合开关：关闭时回退到纯向量检索（HybridService 不激活）。 */
    private boolean enabled = true;

    /** RRF 平滑参数，平衡头部权重与尾部不忽略。 */
    private int k = 60;

    /** 融合后返回条数，与 {@code VectorKnowledgeSearchService.FINAL_TOP_K} 对齐。 */
    private int topK = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getK() {
        return k;
    }

    public void setK(int k) {
        this.k = k;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }
}
