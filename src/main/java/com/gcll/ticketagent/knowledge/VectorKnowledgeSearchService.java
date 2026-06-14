package com.gcll.ticketagent.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Primary
@ConditionalOnBean(VectorStore.class)
public class VectorKnowledgeSearchService implements KnowledgeSearchService {

    private static final int TOP_K = 5;

    private final VectorStore vectorStore;

    public VectorKnowledgeSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType) {
        String searchQuery = buildSearchQuery(query, systemName, moduleName);
        SearchRequest request = SearchRequest.builder()
                .query(searchQuery)
                .topK(TOP_K)
                .similarityThreshold(0.5)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        List<KnowledgeHit> hits = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (!matchesFilter(meta, systemName, moduleName)) {
                continue;
            }
            hits.add(toHit(doc, meta));
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

    private KnowledgeHit toHit(Document doc, Map<String, Object> meta) {
        String content = doc.getText() == null ? "" : doc.getText();
        String title = stringMeta(meta, "title");
        if (title.isBlank()) {
            title = content.length() > 40 ? content.substring(0, 40) + "..." : content;
        }
        double score = doc.getScore() != null ? doc.getScore() : 0.75;
        return new KnowledgeHit(
                stringMeta(meta, "sourceId"),
                stringMeta(meta, "sourceType"),
                title,
                truncate(content, 120),
                content,
                score,
                "向量语义检索"
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
