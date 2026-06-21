package com.gcll.ticketagent.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RrfKnowledgeSearchService} 的纯单测，覆盖 RRF 融合核心逻辑与降级路径。
 *
 * <p>不启动 Spring context，直接调静态 {@link RrfKnowledgeSearchService#rrfFusion} 验证算法，
 * 外加 {@link #degradesToOnePathWhenOtherIsEmpty} 验证降级。仿
 * {@code KnowledgeIngestionServiceTest} 的无 context 模式。
 */
class RrfKnowledgeSearchServiceTest {

    private static final int K = 60;

    /** 构造一个 hit，只关心 sourceId 和它在列表里的位置（rank）。 */
    private static KnowledgeHit hit(String sourceId) {
        return new KnowledgeHit(sourceId, "SOP", "title-" + sourceId, "summary", "resolution", 0.9, "test", false);
    }

    /** 断言融合结果包含所有期望 sourceId（不丢召回）。 */
    @Test
    void fusionKeepsAllUniqueHitsFromBothPaths() {
        // 向量路 [A, C]，关键词路 [B, D]——完全不重叠
        List<KnowledgeHit> vHits = List.of(hit("A"), hit("C"));
        List<KnowledgeHit> kHits = List.of(hit("B"), hit("D"));

        List<KnowledgeHit> fused = RrfKnowledgeSearchService.rrfFusion(vHits, kHits, K, 5);

        assertThat(fused).extracting(KnowledgeHit::sourceId)
                .containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    /** 两路都命中的文档，RRF 分数累加，应排第一（核心加权逻辑）。 */
    @Test
    void documentHitByBothPathsRanksFirst() {
        // 向量路 [A(rank0), B(rank1)]，关键词路 [A(rank0), C(rank1)]
        // A 两路都命中：1/(60+1) + 1/(60+1) = 0.0328（最高）
        // B 只向量路：1/(60+1) = 0.0164
        // C 只关键词路：1/(60+1) = 0.0164
        List<KnowledgeHit> vHits = List.of(hit("A"), hit("B"));
        List<KnowledgeHit> kHits = List.of(hit("A"), hit("C"));

        List<KnowledgeHit> fused = RrfKnowledgeSearchService.rrfFusion(vHits, kHits, K, 5);

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).sourceId()).isEqualTo("A");  // 两路都命中的排第一
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        assertThat(fused.get(0).matchedReason()).contains("RRF");
        assertThat(fused.get(0).reranked()).isTrue();
    }

    /** 一路空 → 返回另一路（降级不丢召回）。用 Mockito mock 具体类（构造器要求具体类型）。 */
    @Test
    void degradesToOnePathWhenOtherIsEmpty() {
        RrfProperties props = new RrfProperties();

        // 向量路返回 [X]，关键词路返回空 → 降级到向量路
        VectorKnowledgeSearchService vectorHasX = mock(VectorKnowledgeSearchService.class);
        when(vectorHasX.search(any(), any(), any(), any())).thenReturn(List.of(hit("X")));
        RelationalKnowledgeSearchService keywordEmpty = mock(RelationalKnowledgeSearchService.class);
        when(keywordEmpty.search(any(), any(), any(), any())).thenReturn(List.of());
        RrfKnowledgeSearchService service = new RrfKnowledgeSearchService(vectorHasX, keywordEmpty, props);

        List<KnowledgeHit> hits = service.search("OOM", "支付系统", null, "INCIDENT");
        assertThat(hits).extracting(KnowledgeHit::sourceId).containsExactly("X");

        // 反向：向量路空，关键词路返回 [Y] → 降级到关键词路
        VectorKnowledgeSearchService vectorEmpty = mock(VectorKnowledgeSearchService.class);
        when(vectorEmpty.search(any(), any(), any(), any())).thenReturn(List.of());
        RelationalKnowledgeSearchService keywordHasY = mock(RelationalKnowledgeSearchService.class);
        when(keywordHasY.search(any(), any(), any(), any())).thenReturn(List.of(hit("Y")));
        RrfKnowledgeSearchService service2 = new RrfKnowledgeSearchService(vectorEmpty, keywordHasY, props);

        List<KnowledgeHit> hits2 = service2.search("OOM", "支付系统", null, "INCIDENT");
        assertThat(hits2).extracting(KnowledgeHit::sourceId).containsExactly("Y");
    }

    /**
     * 黄金对照：模拟 OOM 场景的检索结果，验证融合后的排序符合预期。
     * <p>向量路（语义召回）[kd-003 OOM Runbook, kd-004 缓存击穿案例]，
     * 关键词路（精确匹配）[kd-003 OOM Runbook, kd-005 DB连接池 SOP]。
     * kd-003 两路都命中应排第一，kd-004/kd-005 随后。
     */
    @Test
    void goldenOomCaseFusionOrderMatchesExpectation() {
        List<KnowledgeHit> vHits = List.of(
                new KnowledgeHit("rb-payment-oom-001", "RUNBOOK", "OOM Runbook", "s", "r", 0.92, "向量语义检索", true),
                new KnowledgeHit("inc-2026-0412", "INCIDENT", "缓存击穿案例", "s", "r", 0.85, "向量语义检索", true)
        );
        List<KnowledgeHit> kHits = List.of(
                new KnowledgeHit("rb-payment-oom-001", "RUNBOOK", "OOM Runbook", "s", "r", 1.8, "关键词与系统模块匹配"),
                new KnowledgeHit("sop-db-pool-001", "SOP", "DB连接池SOP", "s", "r", 1.2, "关键词与系统模块匹配")
        );

        List<KnowledgeHit> fused = RrfKnowledgeSearchService.rrfFusion(vHits, kHits, K, 5);

        assertThat(fused).hasSize(3);
        // 两路都命中的 OOM Runbook 排第一
        assertThat(fused.get(0).sourceId()).isEqualTo("rb-payment-oom-001");
        // 剩余两个顺序：都是 rank1，分数相同（1/(60+2)），顺序由 LinkedHashMap 插入序保证（向量路先插）
        assertThat(fused).extracting(KnowledgeHit::sourceId)
                .containsExactlyInAnyOrder("rb-payment-oom-001", "inc-2026-0412", "sop-db-pool-001");
    }
}
