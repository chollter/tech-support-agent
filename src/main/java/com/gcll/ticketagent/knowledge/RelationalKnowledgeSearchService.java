package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class RelationalKnowledgeSearchService implements KnowledgeSearchService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public RelationalKnowledgeSearchService(KnowledgeDocumentRepository knowledgeDocumentRepository) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    @Override
    public List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType) {
        List<KnowledgeDocumentEntity> documents = knowledgeDocumentRepository.findAll();
        List<KnowledgeHit> hits = new ArrayList<>();
        String lowerQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);

        for (KnowledgeDocumentEntity doc : documents) {
            if (systemName != null && doc.getSystemName() != null
                    && !doc.getSystemName().contains(systemName) && !systemName.contains(doc.getSystemName())) {
                continue;
            }
            if (moduleName != null && doc.getModuleName() != null
                    && !doc.getModuleName().contains(moduleName) && !moduleName.contains(doc.getModuleName())) {
                continue;
            }
            double score = scoreDocument(doc, lowerQuery);
            if (score > 0) {
                hits.add(new KnowledgeHit(
                        doc.getSourceId(),
                        doc.getSourceType(),
                        doc.getTitle(),
                        truncate(doc.getContent(), 120),
                        doc.getContent(),
                        score,
                        "关键词与系统模块匹配"
                ));
            }
        }
        hits.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());
        return hits.stream().limit(5).toList();
    }

    private double scoreDocument(KnowledgeDocumentEntity doc, String lowerQuery) {
        double score = 0;
        String content = doc.getContent().toLowerCase(Locale.ROOT);
        String title = doc.getTitle().toLowerCase(Locale.ROOT);
        if (lowerQuery.contains("支付") && (content.contains("支付") || title.contains("支付"))) {
            score += 0.4;
        }
        if (lowerQuery.contains("回调") && content.contains("回调")) {
            score += 0.3;
        }
        if (lowerQuery.contains("500") && content.contains("500")) {
            score += 0.2;
        }
        if (lowerQuery.contains("订单") && content.contains("订单")) {
            score += 0.2;
        }
        if (score == 0 && (content.contains("回调") || title.contains("回调"))) {
            score = 0.5;
        }
        return score;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
