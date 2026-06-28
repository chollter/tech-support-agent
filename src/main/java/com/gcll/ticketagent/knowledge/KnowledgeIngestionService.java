package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.api.BusinessException;
import com.gcll.ticketagent.api.ErrorCode;
import com.gcll.ticketagent.api.dto.KnowledgeDocumentDto;
import com.gcll.ticketagent.api.dto.KnowledgeImportResponse;
import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 120;

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public KnowledgeIngestionService(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            ObjectProvider<VectorStore> vectorStoreProvider
    ) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    public KnowledgeImportResponse importText(
            String filename,
            byte[] bytes,
            String title,
            String systemName,
            String moduleName,
            String tags
    ) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Knowledge document is empty.");
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Knowledge document exceeds 512KB.");
        }
        if (!isSupported(filename)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only .md and .txt knowledge documents are supported.");
        }

        String normalized = normalize(new String(bytes, StandardCharsets.UTF_8));
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Knowledge document has no readable text.");
        }

        String sourceId = UUID.randomUUID().toString();
        String resolvedTitle = resolveTitle(title, filename, normalized);
        List<String> chunks = split(normalized);
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeDocumentEntity> saved = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
            entity.setId(sourceId + "-" + (i + 1));
            entity.setSourceType("UPLOAD");
            entity.setSourceId(sourceId);
            entity.setTitle(chunks.size() == 1 ? resolvedTitle : resolvedTitle + " #" + (i + 1));
            entity.setContent(chunks.get(i));
            entity.setSystemName(blankToNull(systemName));
            entity.setModuleName(blankToNull(moduleName));
            entity.setTags(blankToNull(tags));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            saved.add(knowledgeDocumentRepository.save(entity));
        }

        boolean vectorIndexed = indexVectorStore(saved);
        return new KnowledgeImportResponse(
                sourceId,
                resolvedTitle,
                saved.size(),
                vectorIndexed,
                saved.stream().map(this::toDto).toList()
        );
    }

    public List<KnowledgeDocumentDto> recent(int limit) {
        return knowledgeDocumentRepository.findRecent(limit).stream().map(this::toDto).toList();
    }

    /**
     * 重新向量化所有已入库文档（数据修复用）。
     *
     * <p>场景：pgvector 扩展后装、向量库重建、或文档入库时 VectorStore bean 不可用导致未向量化。
     * 把 knowledge_document 表里所有文档重新灌进 vector_store，供 RAG 检索。
     *
     * @return 重新索引的文档数；VectorStore 不可用时返回 -1
     */
    public int reindexAll() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("Reindex skipped: VectorStore bean not available. Check EmbeddingModel bean, vector store config, and startup diagnostics.");
            return -1;
        }
        List<KnowledgeDocumentEntity> all = knowledgeDocumentRepository.findAll();
        if (all.isEmpty()) {
            return 0;
        }
        vectorStore.add(all.stream().map(this::toVectorDocument).toList());
        log.info("Reindexed {} documents into vector store", all.size());
        return all.size();
    }

    private boolean indexVectorStore(List<KnowledgeDocumentEntity> documents) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || documents.isEmpty()) {
            return false;
        }
        vectorStore.add(documents.stream().map(this::toVectorDocument).toList());
        return true;
    }

    private Document toVectorDocument(KnowledgeDocumentEntity entity) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("knowledgeDocumentId", entity.getId());
        metadata.put("sourceId", entity.getSourceId());
        metadata.put("sourceType", entity.getSourceType());
        metadata.put("title", entity.getTitle());
        metadata.put("systemName", entity.getSystemName() == null ? "" : entity.getSystemName());
        metadata.put("moduleName", entity.getModuleName() == null ? "" : entity.getModuleName());
        metadata.put("tags", entity.getTags() == null ? "" : entity.getTags());
        return new Document(vectorDocumentId(entity.getId()), entity.getTitle() + "\n" + entity.getContent(), metadata);
    }

    private String vectorDocumentId(String businessId) {
        return UUID.nameUUIDFromBytes(businessId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private KnowledgeDocumentDto toDto(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentDto(
                entity.getId(),
                entity.getSourceType(),
                entity.getSourceId(),
                entity.getTitle(),
                summarize(entity.getContent()),
                entity.getSystemName(),
                entity.getModuleName(),
                entity.getTags(),
                entity.getUpdatedAt()
        );
    }

    private boolean isSupported(String filename) {
        if (filename == null || filename.isBlank()) {
            return true;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".txt");
    }

    private String resolveTitle(String title, String filename, String content) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (filename != null && !filename.isBlank()) {
            int dot = filename.lastIndexOf('.');
            return dot > 0 ? filename.substring(0, dot) : filename;
        }
        return summarize(content);
    }

    private String normalize(String text) {
        return text == null ? "" : text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            int boundary = findBoundary(text, start, end);
            chunks.add(text.substring(start, boundary).trim());
            if (boundary >= text.length()) {
                break;
            }
            start = Math.max(boundary - CHUNK_OVERLAP, start + 1);
        }
        return chunks.stream().filter(s -> !s.isBlank()).toList();
    }

    private int findBoundary(String text, int start, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        int paragraph = text.lastIndexOf("\n\n", end);
        if (paragraph > start + CHUNK_SIZE / 2) {
            return paragraph;
        }
        int line = text.lastIndexOf('\n', end);
        if (line > start + CHUNK_SIZE / 2) {
            return line;
        }
        int sentence = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
        if (sentence > start + CHUNK_SIZE / 2) {
            return sentence + 1;
        }
        return end;
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
