package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper knowledgeDocumentMapper) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    @Override
    public List<KnowledgeDocumentEntity> findAll() {
        return knowledgeDocumentMapper.selectList(null);
    }
}
