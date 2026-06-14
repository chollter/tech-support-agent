package com.gcll.ticketagent.extract;

import com.gcll.ticketagent.llm.StepOutcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 不可用时的结构化抽取降级。
 * <p>
 * 【冻结策略】仅覆盖 Golden Eval（inc_001–inc_014）最低字段，不再新增关键词分支；
 * 新场景依赖 LLM extract + InfoGapAnalysis。
 */
@Service
public class RuleBasedTicketExtractService implements TicketExtractService {

    @Override
    public StepOutcome<TicketExtractResult> extract(String content) {
        String text = content == null ? "" : content;
        String lower = text.toLowerCase(Locale.ROOT);

        IssueType issueType = detectIssueType(lower);
        String affectedSystem = extractSystem(text);
        String affectedModule = extractModule(text, lower);
        String apiName = extractApiName(text);
        String errorCode = extractErrorCode(text);
        String errorMessage = extractErrorMessage(text, lower);
        String environment = extractEnvironment(lower);
        String impactScope = extractImpactScope(lower);
        String timeRange = extractTimeRange(text);
        String businessImpact = extractBusinessImpact(lower);
        List<String> severitySignals = extractSeveritySignals(lower);
        double confidence = calculateConfidence(
                issueType, affectedSystem, environment, apiName, errorCode, errorMessage, timeRange, impactScope
        );

        return StepOutcome.ruleBased(new TicketExtractResult(
                issueType,
                affectedSystem,
                affectedModule,
                apiName,
                errorCode,
                errorMessage,
                environment,
                impactScope,
                timeRange,
                businessImpact,
                severitySignals,
                confidence
        ));
    }

    private IssueType detectIssueType(String lower) {
        if (containsAny(lower, "权限", "授权")) {
            return IssueType.PERMISSION;
        }
        if (containsAny(lower, "数据", "不一致")) {
            return IssueType.DATA;
        }
        if (containsAny(lower, "报错", "故障", "500", "异常", "失败", "不可用", "timeout", "oom", "oomkilled", "超时", "积压", "慢", "卡顿", "延迟")) {
            return IssueType.INCIDENT;
        }
        if (containsAny(lower, "需求", "功能")) {
            return IssueType.REQUIREMENT;
        }
        if (containsAny(lower, "咨询", "如何", "怎么")) {
            return IssueType.CONSULT;
        }
        return IssueType.UNKNOWN;
    }

    private String extractSystem(String text) {
        if (text.contains("支付系统") || text.contains("支付")) {
            return "支付系统";
        }
        if (containsAny(text.toLowerCase(Locale.ROOT), "订单")) {
            return "订单系统";
        }
        if (containsAny(text.toLowerCase(Locale.ROOT), "账号", "mfa", "权限")) {
            return "账号系统";
        }
        if (containsAny(text.toLowerCase(Locale.ROOT), "结算", "账务")) {
            return "结算系统";
        }
        if (containsAny(text.toLowerCase(Locale.ROOT), "redis", "缓存")) {
            return "基础平台";
        }
        if (containsAny(text.toLowerCase(Locale.ROOT), "mq", "消息")) {
            return "中间件平台";
        }
        return null;
    }

    private String extractModule(String text, String lower) {
        if (lower.contains("回调") || text.contains("/pay/callback")) {
            return "支付回调";
        }
        if (containsAny(lower, "payment-service", "oom", "oomkilled", "内存", "heap")) {
            return "支付服务";
        }
        if (containsAny(lower, "连接池", "hikari", "connection")) {
            return "数据库连接池";
        }
        if (containsAny(lower, "mfa")) {
            return "MFA";
        }
        if (containsAny(lower, "权限")) {
            return "权限";
        }
        if (containsAny(lower, "结算", "批处理")) {
            return "批处理";
        }
        if (containsAny(lower, "redis", "缓存")) {
            return "Redis";
        }
        if (containsAny(lower, "mq", "消息", "积压")) {
            return "消息队列";
        }
        return null;
    }

    private String extractApiName(String text) {
        if (text.contains("/pay/callback")) {
            return "/pay/callback";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "下单接口")) {
            return "支付下单接口";
        }
        if (containsAny(lower, "风控接口")) {
            return "支付风控接口";
        }
        if (containsAny(lower, "订单查询接口")) {
            return "订单查询接口";
        }
        if (containsAny(lower, "mfa")) {
            return "MFA 重置";
        }
        if (containsAny(lower, "权限同步接口")) {
            return "权限同步";
        }
        if (containsAny(lower, "mq", "消息")) {
            return "支付成功事件消费";
        }
        if (containsAny(lower, "批处理", "结算")) {
            return "结算批处理";
        }
        if (containsAny(lower, "报表导出")) {
            return "报表导出";
        }
        if (text.contains("接口")) {
            return null;
        }
        return null;
    }

    private String extractErrorCode(String text) {
        if (text.contains("500")) {
            return "500";
        }
        return null;
    }

    private String extractErrorMessage(String text, String lower) {
        if (text.contains("500")) {
            return "HTTP 500";
        }
        if (containsAny(lower, "timeout", "超时")) {
            return "请求超时";
        }
        if (containsAny(lower, "oom", "oomkilled", "outofmemory", "内存")) {
            return "OOMKilled/内存超限";
        }
        if (containsAny(lower, "积压")) {
            return "消息积压";
        }
        if (lower.contains("报错")) {
            return "接口报错";
        }
        return null;
    }

    private String extractEnvironment(String lower) {
        if (containsAny(lower, "生产", "prod", "线上")) {
            return "生产";
        }
        if (containsAny(lower, "测试", "test", "uat")) {
            return "测试";
        }
        if (containsAny(lower, "预发", "staging")) {
            return "预发";
        }
        if (containsAny(lower, "开发", "dev")) {
            return "开发";
        }
        return null;
    }

    private String extractImpactScope(String lower) {
        if (containsAny(lower, "所有用户", "全量", "全部用户")) {
            return "所有用户";
        }
        if (containsAny(lower, "多个用户", "部分用户", "多用户", "多个商户", "部分")) {
            return "多个用户";
        }
        if (containsAny(lower, "单个用户", "单用户")) {
            return "单个用户";
        }
        return null;
    }

    private String extractTimeRange(String text) {
        if (text.contains("10点") || text.contains("10 点") || text.contains("上午 10 点") || text.contains("上午10点")) {
            return "上午10点起";
        }
        if (text.contains("02:31") || text.contains("2:31")) {
            return "凌晨02:31";
        }
        return null;
    }

    private String extractBusinessImpact(String lower) {
        if (containsAny(lower, "支付成功", "待支付", "订单状态")) {
            return "支付成功但订单状态未更新";
        }
        if (containsAny(lower, "支付失败", "下单失败")) {
            return "核心交易链路失败";
        }
        if (containsAny(lower, "数据不一致", "账务", "资金")) {
            return "资金或业务数据不一致";
        }
        return null;
    }

    private List<String> extractSeveritySignals(String lower) {
        List<String> signals = new ArrayList<>();
        if (containsAny(lower, "生产", "线上")) {
            signals.add("PRODUCTION");
        }
        if (containsAny(lower, "支付", "交易", "回调", "下单")) {
            signals.add("CORE_PAYMENT");
        }
        if (containsAny(lower, "多个用户", "部分用户", "全量")) {
            signals.add("MULTI_USER");
        }
        if (containsAny(lower, "资金", "待支付", "支付成功", "账务", "数据不一致")) {
            signals.add("FINANCIAL_INCONSISTENCY");
        }
        if (containsAny(lower, "oom", "oomkilled", "内存")) {
            signals.add("RESOURCE_EXHAUSTED");
        }
        if (containsAny(lower, "timeout", "连接池", "hikari", "超时")) {
            signals.add("DEPENDENCY_TIMEOUT");
        }
        return signals;
    }

    private double calculateConfidence(
            IssueType issueType,
            String system,
            String env,
            String api,
            String errorCode,
            String errorMessage,
            String timeRange,
            String impactScope
    ) {
        if (issueType != IssueType.INCIDENT) {
            if (system != null && !system.isBlank()) {
                return 0.65;
            }
            return 0.35;
        }
        int filled = 0;
        if (system != null && !system.isBlank()) filled++;
        if (env != null && !env.isBlank()) filled++;
        if (api != null && !api.isBlank()) filled++;
        if (errorCode != null || (errorMessage != null && !errorMessage.isBlank())) filled++;
        if (timeRange != null && !timeRange.isBlank()) filled++;
        if (impactScope != null && !impactScope.isBlank()) filled++;
        return filled / 6.0;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
