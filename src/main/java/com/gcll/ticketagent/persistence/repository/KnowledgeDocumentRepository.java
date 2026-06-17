package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;

import java.util.List;

public interface KnowledgeDocumentRepository {
    List<KnowledgeDocumentEntity> findAll();

    KnowledgeDocumentEntity save(KnowledgeDocumentEntity document);

    List<KnowledgeDocumentEntity> findRecent(int limit);
}
