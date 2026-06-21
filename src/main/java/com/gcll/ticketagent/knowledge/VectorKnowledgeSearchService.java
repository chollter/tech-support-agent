package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.knowledge.rerank.RerankEntry;
import com.gcll.ticketagent.knowledge.rerank.RerankService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 不再 @Primary：融合层 RrfKnowledgeSearchService 成为新的 @Primary，本类降为融合层的内部向量路组件。
// 融合层关闭时（opsmind.rag.fusion.enabled=false）或无 VectorStore 时，本类不主导注入。
@Service
@ConditionalOnBean(VectorStore.class)
public class VectorKnowledgeSearchService implements KnowledgeSearchService {

    /** 向量召回数（粗排）：多召回给 rerank 更多候选；无 rerank 时在最后截断到 FINAL_TOP_K。 */
    private static final int RECALL_TOP_K = 20;
    /** 最终返回条数（rerank 后或无 rerank 时）。 */
    private static final int FINAL_TOP_K = 5;

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    public VectorKnowledgeSearchService(VectorStore vectorStore, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    @Override
    public List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType) {
        String searchQuery = buildSearchQuery(query, systemName, moduleName);
        SearchRequest request = SearchRequest.builder()
                .query(searchQuery)
                .topK(RECALL_TOP_K)
                .similarityThreshold(0.5)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        // Java 层 system/module 过滤（向量库未下推表达式过滤）
        List<Document> filtered = new ArrayList<>();
        for (Document doc : documents) {
            if (matchesFilter(doc.getMetadata(), systemName, moduleName)) {
                filtered.add(doc);
            }
        }
        if (filtered.isEmpty()) {
            return List.of();
        }

        // rerank：候选数 > FINAL_TOP_K 才值得调；rerank 失败/关闭由 RerankService 内部降级
        List<RerankEntry> entries = new ArrayList<>(filtered.size());
        for (int i = 0; i < filtered.size(); i++) {
            entries.add(new RerankEntry(i, filtered.get(i).getText()));
        }
        List<RerankEntry> ordered = rerankService.rerank(query, entries, FINAL_TOP_K);

        boolean reranked = rerankService.active() && entries.size() > FINAL_TOP_K;
        List<KnowledgeHit> hits = new ArrayList<>(ordered.size());
        for (RerankEntry entry : ordered) {
            hits.add(toHit(filtered.get(entry.index()), filtered.get(entry.index()).getMetadata(), reranked));
        }
        return hits;
    }

    private String buildSearchQuery(String query, String systemName, String moduleName) {
        StringBuilder sb = new StringBuilder();
        if (query != null && !query.isBlank()) {
            sb.append(query.trim());
        }
        if (systemName != null && !systemName.isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(systemName);
        }
        if (moduleName != null && !moduleName.isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(moduleName);
        }
        return sb.isEmpty() ? "故障工单" : sb.toString();
    }

    private boolean matchesFilter(Map<String, Object> meta, String systemName, String moduleName) {
        if (systemName != null && !systemName.isBlank()) {
            String docSystem = stringMeta(meta, "systemName");
            if (!docSystem.isBlank()
                    && !docSystem.contains(systemName)
                    && !systemName.contains(docSystem)) {
                return false;
            }
        }
        if (moduleName != null && !moduleName.isBlank()) {
            String docModule = stringMeta(meta, "moduleName");
            if (!docModule.isBlank()
                    && !docModule.contains(moduleName)
                    && !moduleName.contains(docModule)) {
                return false;
            }
        }
        return true;
    }

    private KnowledgeHit toHit(Document doc, Map<String, Object> meta, boolean reranked) {
        String content = doc.getText() == null ? "" : doc.getText();
        String title = stringMeta(meta, "title");
        if (title.isBlank()) {
            title = content.length() > 40 ? content.substring(0, 40) + "..." : content;
        }
        double score = doc.getScore() != null ? doc.getScore() : 0.75;
        String reason = reranked ? "向量语义检索 + rerank 重排序" : "向量语义检索";
        return new KnowledgeHit(
                stringMeta(meta, "sourceId"),
                stringMeta(meta, "sourceType"),
                title,
                truncate(content, 120),
                content,
                score,
                reason,
                reranked
        );
    }

    private String stringMeta(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value == null ? "" : value.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
