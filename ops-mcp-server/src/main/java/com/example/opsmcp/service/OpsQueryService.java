package com.example.opsmcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsmcp.entity.OpsLogSampleEntity;
import com.example.opsmcp.entity.OpsMetricSampleEntity;
import com.example.opsmcp.mapper.OpsLogSampleMapper;
import com.example.opsmcp.mapper.OpsMetricSampleMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 运维日志/指标查询服务（从 tech-support-agent 主项目迁移而来）。
 * <p>供 MCP tool 调用，背后查 PostgreSQL 的 ops_log_sample / ops_metric_sample 表。
 * 查询逻辑与原 {@code MyBatisOpsSampleRepository} 保持一致，保证迁移后行为不变。
 */
@Service
public class OpsQueryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsLogSampleMapper logMapper;
    private final OpsMetricSampleMapper metricMapper;

    public OpsQueryService(OpsLogSampleMapper logMapper, OpsMetricSampleMapper metricMapper) {
        this.logMapper = logMapper;
        this.metricMapper = metricMapper;
    }

    /**
     * 查询日志，返回格式化后的文本（供 MCP tool 直接回传给 LLM）。
     */
    public String queryLogs(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsLogSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            // systemName 变体展开：工单抽取可能是 "Payment service"(空格)，
            // 库里可能是 "payment-service"(连字符) 或 "支付系统"(中文)，需多变体 OR 匹配
            applySystemMatchLog(wrapper, systemName);
        }
        if (hasText(moduleName)) {
            applySystemMatchLog(wrapper, moduleName);
        }
        applyLogKeyword(wrapper, query);
        wrapper.orderByDesc(OpsLogSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        List<OpsLogSampleEntity> logs = logMapper.selectList(wrapper);
        return logs.isEmpty() ? "no log evidence found" : formatLogs(logs);
    }

    /**
     * 查询指标，返回格式化后的文本（供 MCP tool 直接回传给 LLM）。
     */
    public String queryMetrics(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsMetricSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            applySystemMatchMetric(wrapper, systemName);
        }
        applyMetricKeyword(wrapper, firstText(moduleName, query));
        wrapper.orderByDesc(OpsMetricSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        List<OpsMetricSampleEntity> metrics = metricMapper.selectList(wrapper);
        return metrics.isEmpty() ? "no metric evidence found" : formatMetrics(metrics);
    }

    /**
     * 日志表的 system/service 宽松匹配：对查询词做变体展开（空格/连字符/下划线互换），
     * OR 匹配 system_name 和 service_name，解决 "Payment service" vs "payment-service" 不命中问题。
     */
    private void applySystemMatchLog(LambdaQueryWrapper<OpsLogSampleEntity> wrapper, String keyword) {
        List<String> variants = nameVariants(keyword);
        wrapper.and(w -> {
            boolean first = true;
            for (String v : variants) {
                if (first) {
                    w.like(OpsLogSampleEntity::getSystemName, v)
                            .or().like(OpsLogSampleEntity::getServiceName, v);
                    first = false;
                } else {
                    w.or().like(OpsLogSampleEntity::getSystemName, v)
                            .or().like(OpsLogSampleEntity::getServiceName, v);
                }
            }
        });
    }

    /** 指标表的 system/service 宽松匹配（同 applySystemMatchLog）。 */
    private void applySystemMatchMetric(LambdaQueryWrapper<OpsMetricSampleEntity> wrapper, String keyword) {
        List<String> variants = nameVariants(keyword);
        wrapper.and(w -> {
            boolean first = true;
            for (String v : variants) {
                if (first) {
                    w.like(OpsMetricSampleEntity::getSystemName, v)
                            .or().like(OpsMetricSampleEntity::getServiceName, v);
                    first = false;
                } else {
                    w.or().like(OpsMetricSampleEntity::getSystemName, v)
                            .or().like(OpsMetricSampleEntity::getServiceName, v);
                }
            }
        });
    }

    /**
     * 生成服务名变体：归一化分隔符（空格/连字符/下划线互换）+ 原值。
     * 如 "Payment service" → ["Payment service", "payment-service", "payment_service", "payment service"]
     * 让 SQL LIKE 能命中不同分隔符写法的 service 名。
     */
    private List<String> nameVariants(String name) {
        if (!hasText(name)) {
            return List.of();
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        // 用正则把空格/连字符/下划线统一替换，生成三种分隔符变体
        String noSep = lower.replaceAll("[\\s\\-_]+", " ");        // 空格
        String dashSep = lower.replaceAll("[\\s\\-_]+", "-");      // 连字符
        String underscoreSep = lower.replaceAll("[\\s\\-_]+", "_"); // 下划线
        // 去重保留：原值 + 三种变体
        return java.util.stream.Stream.of(trimmed, lower, noSep, dashSep, underscoreSep)
                .filter(s -> hasText(s) && s.length() >= 2)
                .distinct()
                .toList();
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

    private String formatLogs(List<OpsLogSampleEntity> logs) {
        StringBuilder sb = new StringBuilder();
        for (OpsLogSampleEntity log : logs) {
            sb.append('[').append(FORMATTER.format(log.getOccurredAt())).append("] ")
                    .append(log.getLevel()).append(' ')
                    .append(log.getSystemName()).append('/')
                    .append(log.getServiceName()).append(' ')
                    .append("traceId=").append(log.getTraceId()).append(' ')
                    .append(log.getMessage()).append('\n');
        }
        return sb.toString().trim();
    }

    private String formatMetrics(List<OpsMetricSampleEntity> metrics) {
        StringBuilder sb = new StringBuilder();
        for (OpsMetricSampleEntity metric : metrics) {
            sb.append('[').append(FORMATTER.format(metric.getOccurredAt())).append("] ")
                    .append(metric.getSystemName()).append('/')
                    .append(metric.getServiceName()).append(' ')
                    .append(metric.getMetricName()).append('=')
                    .append(metric.getMetricValue())
                    .append(metric.getUnit() == null ? "" : metric.getUnit())
                    .append(" status=").append(metric.getStatus())
                    .append(" labels=").append(metric.getLabels())
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
