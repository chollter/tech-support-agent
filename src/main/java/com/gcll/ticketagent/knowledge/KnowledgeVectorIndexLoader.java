package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnBean(VectorStore.class)
public class KnowledgeVectorIndexLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndexLoader.class);

    private final VectorStore vectorStore;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public KnowledgeVectorIndexLoader(
            VectorStore vectorStore,
            KnowledgeDocumentRepository knowledgeDocumentRepository
    ) {
        this.vectorStore = vectorStore;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<KnowledgeDocumentEntity> documents = knowledgeDocumentRepository.findAll();
        if (documents.isEmpty()) {
            log.warn("No knowledge documents to index for vector search");
            return;
        }
        List<Document> aiDocuments = documents.stream().map(this::toDocument).toList();
        vectorStore.add(aiDocuments);
        log.info("Indexed {} knowledge documents into vector store", aiDocuments.size());
    }

    private Document toDocument(KnowledgeDocumentEntity entity) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeDocumentId", entity.getId());
        metadata.put("sourceId", entity.getSourceId());
        metadata.put("sourceType", entity.getSourceType());
        metadata.put("title", entity.getTitle());
        metadata.put("systemName", entity.getSystemName() == null ? "" : entity.getSystemName());
        metadata.put("moduleName", entity.getModuleName() == null ? "" : entity.getModuleName());
        String text = entity.getTitle() + "\n" + entity.getContent();
        return new Document(vectorDocumentId(entity.getId()), text, metadata);
    }

    private String vectorDocumentId(String businessId) {
        return UUID.nameUUIDFromBytes(businessId.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
