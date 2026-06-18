package com.gcll.ticketagent.knowledge.rerank;

import com.gcll.ticketagent.knowledge.rerank.DashScopeRerankService.Output;
import com.gcll.ticketagent.knowledge.rerank.DashScopeRerankService.RerankResponse;
import com.gcll.ticketagent.knowledge.rerank.DashScopeRerankService.RerankResult;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DashScopeRerankService} 单测：mock {@link ExternalCallGateway}，
 * 验证请求构造、rerank 结果回填、降级（失败/熔断返回原始顺序）。
 * 不发真实 HTTP。
 */
class DashScopeRerankServiceTest {

    private final ExternalCallGateway gateway = mock(ExternalCallGateway.class);
    private DashScopeRerankService service;

    @BeforeEach
    void setUp() {
        service = new DashScopeRerankService(gateway, null, "sk-test", "gte-rerank-v2",
                "https://example.com/rerank");
    }

    private List<RerankEntry> candidates(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new RerankEntry(i, "doc-" + i))
                .toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rerankReordersByReturnedIndex() {
        // 5 候选 topN=3（候选数 > topN 才触发 rerank），rerank 返回顺序 [2,0,4]，
        // 应回填成候选的 index 2/0/4
        RerankResponse response = new RerankResponse(new Output(List.of(
                new RerankResult(2, 0.9),
                new RerankResult(0, 0.8),
                new RerankResult(4, 0.7)
        )));
        when(gateway.execute(anyString(), any(Supplier.class)))
                .thenReturn(CallResult.ok(response, 1, 50));

        List<RerankEntry> result = service.rerank("query", candidates(5), 3);

        assertThat(result).extracting(RerankEntry::index).containsExactly(2, 0, 4);
        assertThat(service.active()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rerankReturnsOnlyTopN() {
        // 10 个候选，topN=3，rerank 返回 3 个
        RerankResponse response = new RerankResponse(new Output(List.of(
                new RerankResult(5, 0.95),
                new RerankResult(1, 0.9),
                new RerankResult(8, 0.85)
        )));
        when(gateway.execute(anyString(), any(Supplier.class)))
                .thenReturn(CallResult.ok(response, 1, 50));

        List<RerankEntry> result = service.rerank("query", candidates(10), 3);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(RerankEntry::index).containsExactly(5, 1, 8);
    }

    @Test
    void skipsRerankWhenCandidatesBelowTopN() {
        // 候选数 <= topN，不值得调 rerank，直接返回原始
        List<RerankEntry> cands = candidates(3);
        List<RerankEntry> result = service.rerank("query", cands, 5);

        assertThat(result).isSameAs(cands);
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradesToOriginalOrderWhenCallFails() {
        when(gateway.execute(anyString(), any(Supplier.class)))
                .thenReturn(CallResult.fail(new RuntimeException("timeout"), 1, 100));

        List<RerankEntry> result = service.rerank("query", candidates(10), 3);

        // 降级：返回原始前 topN（0,1,2）
        assertThat(result).extracting(RerankEntry::index).containsExactly(0, 1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradesToOriginalOrderWhenCircuitOpen() {
        when(gateway.execute(anyString(), any(Supplier.class)))
                .thenReturn(CallResult.circuitOpen(0));

        List<RerankEntry> result = service.rerank("query", candidates(10), 3);

        assertThat(result).extracting(RerankEntry::index).containsExactly(0, 1, 2);
    }
}
