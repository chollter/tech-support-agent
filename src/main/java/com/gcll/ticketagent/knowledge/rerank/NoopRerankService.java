package com.gcll.ticketagent.knowledge.rerank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认 rerank 实现：不重排，保持原始向量召回顺序。
 * <p>当 rerank 开关关闭（{@code opsmind.rag.rerank.enabled=false}）或未启用 DashScope 时激活。
 * {@link #active()} 返回 false，审计可据此区分"未 rerank"与"rerank 后"。
 */
@Service
@ConditionalOnProperty(prefix = "opsmind.rag.rerank", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopRerankService implements RerankService {

    @Override
    public List<RerankEntry> rerank(String query, List<RerankEntry> candidates, int topN) {
        return candidates.size() <= topN ? candidates : candidates.subList(0, topN);
    }

    @Override
    public boolean active() {
        return false;
    }
}
