package com.gcll.ticketagent.suggestion;

import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TemplateSuggestionGenerationService implements SuggestionGenerationService {

    @Override
    public StepOutcome<TicketSuggestion> generate(TicketExtractResult extract, List<KnowledgeHit> hits,
                                                  List<ToolResult> toolResults, RootCauseResult rootCause) {
        String summary = buildSummary(extract, rootCause);
        List<String> causes = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        if (rootCause != null) {
            causes.add(rootCause.hypothesis());
            if (rootCause.evidence() != null) {
                for (String e : rootCause.evidence()) {
                    causes.add("证据: " + e);
                }
            }
        }

        if (extract.affectedSystem() != null && extract.affectedSystem().contains("支付")) {
            causes.add("支付回调接口异常导致回调处理失败");
            causes.add("支付成功事件后续 MQ 消费或订单补偿异常");
        }

        actions = buildRunbookActions(extract, rootCause, toolResults);

        if (hits != null) {
            for (KnowledgeHit hit : hits) {
                sources.add(hit.sourceType() + ":" + hit.sourceId());
            }
        }

        if (causes.isEmpty()) {
            causes.add("根因待确认，需进一步排查");
        }
        if (actions.isEmpty()) {
            actions.add("收集完整错误日志与发生时间");
            actions.add("确认影响范围与复现步骤");
        }

        return StepOutcome.ruleBased(new TicketSuggestion(
                summary,
                causes,
                actions,
                actions,
                rootCause != null ? rootCause.unknowns() : List.of(),
                List.of("生产环境操作需谨慎，待确认信息请勿直接执行"),
                sources
        ));
    }

    private List<String> buildRunbookActions(TicketExtractResult extract, RootCauseResult rootCause, List<ToolResult> toolResults) {
        List<String> actions = new ArrayList<>();

        actions.add("[Step 1] 确认故障现象: " + (extract.errorCode() != null ? extract.errorCode() : "查看错误信息"));

        if (rootCause != null && rootCause.hypothesis() != null) {
            String lower = rootCause.hypothesis().toLowerCase();
            if (lower.contains("oom") || lower.contains("memory") || lower.contains("内存")) {
                actions.add("[Step 2] 查看 Pod 内存使用趋势和 JVM 堆监控");
                actions.add("[Step 3] 分析 heap dump 确认是否有内存泄漏");
                actions.add("[Step 4] 临时措施: 扩大容器内存限制或重启 Pod");
                actions.add("[Step 5] 根本措施: 修复内存泄漏或优化 JVM 参数");
            } else if (lower.contains("timeout") || lower.contains("connection") || lower.contains("连接")) {
                actions.add("[Step 2] 检查数据库连接池状态和当前活跃连接数");
                actions.add("[Step 3] 排查慢查询和长事务");
                actions.add("[Step 4] 临时措施: 增大连接池或 kill 阻塞会话");
                actions.add("[Step 5] 根本措施: 优化慢查询或增加连接池容量");
            } else if (lower.contains("支付") || lower.contains("回调")) {
                actions.add("[Step 2] 查询 /pay/callback 接口 10 点后的 500 错误日志");
                actions.add("[Step 3] 检查 payment_callback_log 回调处理状态");
                actions.add("[Step 4] 检查 MQ 消费积压和死信队列");
                actions.add("[Step 5] 检查订单补偿任务是否正常执行");
            } else {
                actions.add("[Step 2] 根据根因分析结果定位问题模块");
                actions.add("[Step 3] 查看相关服务日志和监控指标");
                actions.add("[Step 4] 确认是否有近期变更");
            }
        } else {
            actions.add("[Step 2] 收集完整错误日志与发生时间");
            actions.add("[Step 3] 确认影响范围与复现步骤");
        }

        actions.add("[Final] 验证修复效果并更新知识库");
        return actions;
    }

    private String buildSummary(TicketExtractResult extract, RootCauseResult rootCause) {
        StringBuilder sb = new StringBuilder();
        if (extract.environment() != null) {
            sb.append(extract.environment()).append("环境");
        }
        if (extract.affectedSystem() != null) {
            sb.append(extract.affectedSystem());
        }
        if (extract.apiName() != null) {
            sb.append(" ").append(extract.apiName());
        }
        if (extract.errorCode() != null) {
            sb.append(" 返回 ").append(extract.errorCode());
        }
        if (rootCause != null && rootCause.hypothesis() != null) {
            sb.append(" — 根因: ").append(rootCause.hypothesis());
        }
        return sb.length() > 0 ? sb.toString() : "工单摘要待确认";
    }
}
