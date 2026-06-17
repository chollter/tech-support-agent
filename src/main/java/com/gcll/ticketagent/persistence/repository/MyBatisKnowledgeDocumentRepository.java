package com.gcll.ticketagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Override
    public KnowledgeDocumentEntity save(KnowledgeDocumentEntity document) {
        int updated = knowledgeDocumentMapper.updateById(document);
        if (updated == 0) {
            knowledgeDocumentMapper.insert(document);
        }
        return document;
    }

    @Override
    public List<KnowledgeDocumentEntity> findRecent(int limit) {
        return knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
                .last("limit " + Math.max(1, limit)));
    }
}
