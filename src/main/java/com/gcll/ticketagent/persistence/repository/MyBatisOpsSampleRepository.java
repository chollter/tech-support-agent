package com.gcll.ticketagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.ticketagent.persistence.entity.OpsLogSampleEntity;
import com.gcll.ticketagent.persistence.entity.OpsMetricSampleEntity;
import com.gcll.ticketagent.persistence.mapper.OpsLogSampleMapper;
import com.gcll.ticketagent.persistence.mapper.OpsMetricSampleMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

@Repository
public class MyBatisOpsSampleRepository implements OpsSampleRepository {

    private final OpsLogSampleMapper logMapper;
    private final OpsMetricSampleMapper metricMapper;

    public MyBatisOpsSampleRepository(OpsLogSampleMapper logMapper, OpsMetricSampleMapper metricMapper) {
        this.logMapper = logMapper;
        this.metricMapper = metricMapper;
    }

    @Override
    public List<OpsLogSampleEntity> searchLogs(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsLogSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            wrapper.and(w -> w.like(OpsLogSampleEntity::getSystemName, systemName)
                    .or()
                    .like(OpsLogSampleEntity::getServiceName, systemName));
        }
        if (hasText(moduleName)) {
            wrapper.and(w -> w.like(OpsLogSampleEntity::getModuleName, moduleName)
                    .or()
                    .like(OpsLogSampleEntity::getServiceName, moduleName));
        }
        applyLogKeyword(wrapper, query);
        wrapper.orderByDesc(OpsLogSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        return logMapper.selectList(wrapper);
    }

    @Override
    public List<OpsMetricSampleEntity> searchMetrics(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsMetricSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            wrapper.and(w -> w.like(OpsMetricSampleEntity::getSystemName, systemName)
                    .or()
                    .like(OpsMetricSampleEntity::getServiceName, systemName));
        }
        applyMetricKeyword(wrapper, firstText(moduleName, query));
        wrapper.orderByDesc(OpsMetricSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        return metricMapper.selectList(wrapper);
    }

    private void applyLogKeyword(LambdaQueryWrapper<OpsLogSampleEntity> wrapper, String query) {
        String keyword = normalizeKeyword(query);
        if (!hasText(keyword)) {
            return;
        }
        wrapper.and(w -> w.like(OpsLogSampleEntity::getMessage, keyword)
                .or()
                .like(OpsLogSampleEntity::getTags, keyword)
                .or()
                .like(OpsLogSampleEntity::getTraceId, keyword));
    }

    private void applyMetricKeyword(LambdaQueryWrapper<OpsMetricSampleEntity> wrapper, String query) {
        String keyword = normalizeKeyword(query);
        if (!hasText(keyword)) {
            return;
        }
        wrapper.and(w -> w.like(OpsMetricSampleEntity::getMetricName, keyword)
                .or()
                .like(OpsMetricSampleEntity::getLabels, keyword)
                .or()
                .like(OpsMetricSampleEntity::getStatus, keyword)
                .or()
                .like(OpsMetricSampleEntity::getServiceName, keyword));
    }

    private String normalizeKeyword(String query) {
        if (!hasText(query)) {
            return null;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("oom") || lower.contains("memory") || lower.contains("heap") || query.contains("内存")) {
            return "memory";
        }
        if (lower.contains("timeout") || lower.contains("connection") || query.contains("连接池")) {
            return "connection";
        }
        if (lower.contains("500") || query.contains("回调")) {
            return "callback";
        }
        if (query.contains("结算") || query.contains("幂等")) {
            return "settlement";
        }
        return query.length() > 32 ? query.substring(0, 32) : query;
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
