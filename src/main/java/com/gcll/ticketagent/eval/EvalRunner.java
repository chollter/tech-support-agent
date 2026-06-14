package com.gcll.ticketagent.eval;

import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class EvalRunner {

    private static final List<String> NO_PLAN = List.of();
    private static final List<String> NO_KEYWORDS = List.of();
    private static final String NO_ISSUE_TYPE = null;

    private final TicketApplicationService ticketApplicationService;
    private final AgentRunRepository agentRunRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;

    public EvalRunner(
            TicketApplicationService ticketApplicationService,
            AgentRunRepository agentRunRepository,
            ToolExecutionLogRepository toolExecutionLogRepository
    ) {
        this.ticketApplicationService = ticketApplicationService;
        this.agentRunRepository = agentRunRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
    }

    public EvalReport run() {
        List<EvalCase> cases = List.of(
                new EvalCase("inc_001", "信息不足", "接口报错了",
                        ReplyType.NEED_MORE_INFO, null, AgentRunStatus.WAIT_USER_INPUT, 6, false,
                        false, false, false, false,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_002", "支付故障",
                        "生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_003", "OOMKilled",
                        "生产环境支付系统 payment-service 支付下单接口从上午10点开始失败，多个用户支付失败，Pod OOMKilled，错误信息 Java heap space，内存使用从 200Mi 飙升到 512Mi 后被 kill。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_004", "DB Connection Timeout",
                        "生产环境订单系统 order-service 订单查询接口从上午10点开始失败，多个用户订单查询超时，HikariPool 数据库连接池耗尽，错误信息 connection timeout。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_005", "MFA 权限",
                        "生产环境账号系统 MFA 重置接口从上午10点开始审批失败，单个用户 u-8831 无法登录后台，错误信息 wait admin approval。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P2", AgentRunStatus.FINAL, 0, false,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_006", "数据一致性",
                        "生产环境结算系统批处理重复执行，出现资金账务数据不一致，影响多个商户。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_007", "测试环境低优先级",
                        "测试环境账号系统权限同步接口从上午10点开始失败，单个用户测试账号受影响，可以手工绕过，错误信息 permission sync failed。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P3", AgentRunStatus.FINAL, 0, false,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_008", "知识库无命中",
                        "生产环境新上线的报表导出任务偶发格式错乱，暂无错误码，单个运营用户反馈。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P2", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_009", "MQ 积压",
                        "生产环境支付系统支付成功事件 MQ 消费接口从上午10点开始积压，多个用户支付完成后看不到订单变化，错误信息 consumer lag high。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_010", "缓存超时",
                        "生产环境支付系统支付风控接口从上午10点开始响应变慢，部分用户下单失败，Redis 缓存访问 timeout，错误信息 cache timeout。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS, NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_011", "批处理语义追问", "结算批处理有时跑不完",
                        ReplyType.NEED_MORE_INFO, null, AgentRunStatus.WAIT_USER_INPUT, 6, false,
                        false, false, false, false,
                        true, true, List.of("Job", "批处理", "结算", "任务"), NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_012", "偶发慢语义追问", "生产支付接口偶尔慢",
                        ReplyType.NEED_MORE_INFO, null, AgentRunStatus.WAIT_USER_INPUT, 6, false,
                        false, false, false, false,
                        true, true, List.of("偶发", "必现", "查询", "写", "下单", "接口"), NO_PLAN, NO_PLAN, NO_PLAN, NO_ISSUE_TYPE),
                new EvalCase("inc_013", "OOM plan 优先 metric",
                        "生产环境支付系统 payment-service 支付下单接口从上午10点开始失败，多个用户支付失败，Pod OOMKilled，错误信息 Java heap space，内存使用从 200Mi 飙升到 512Mi 后被 kill。",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P1", AgentRunStatus.WAIT_HUMAN_CONFIRM, 0, true,
                        true, true, true, true,
                        true, false, NO_KEYWORDS,
                        List.of("QUERY_METRIC"),
                        List.of("SIMILAR_CASE_SEARCH"),
                        List.of("query_metric", "query_logs"),
                        NO_ISSUE_TYPE),
                new EvalCase("inc_014", "MFA 咨询", "如何配置 MFA",
                        ReplyType.TICKET_ANALYSIS_RESULT, "P2", AgentRunStatus.FINAL, 0, false,
                        true, false, false, true,
                        true, false, NO_KEYWORDS,
                        List.of("KNOWLEDGE_SEARCH"),
                        List.of("SIMILAR_CASE_SEARCH", "QUERY_LOGS", "QUERY_METRIC"),
                        NO_PLAN,
                        "CONSULT")
        );

        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                    "eval-" + evalCase.id() + "-" + UUID.randomUUID(),
                    "u1001",
                    evalCase.title(),
                    evalCase.description(),
                    "EVAL",
                    null,
                    null
            ));
            boolean casePassed = response.replyType() == evalCase.expectedReplyType()
                    && response.status() == evalCase.expectedStatus()
                    && (evalCase.expectedPriority() == null
                    || (response.analysis() != null && evalCase.expectedPriority().equals(response.analysis().ticket().priority())))
                    && (evalCase.maxQuestions() == 0
                    || (response.questions() != null && response.questions().size() <= evalCase.maxQuestions()))
                    && (!evalCase.needHumanConfirm()
                    || (response.analysis() != null && response.analysis().humanConfirm().required()))
                    && stepExpectationPassed(response.runId(), evalCase)
                    && planExpectationPassed(response.runId(), evalCase)
                    && toolExpectationPassed(response.runId(), evalCase)
                    && selectedToolExpectationPassed(response.runId(), evalCase)
                    && rootCauseExpectationPassed(response, evalCase)
                    && semanticFollowUpPassed(response, evalCase)
                    && issueTypeExpectationPassed(response, evalCase);
            if (casePassed) {
                passed++;
            } else {
                failures.add(evalCase.id() + " failed: replyType=" + response.replyType()
                        + ", status=" + response.status() + ", priority="
                        + (response.analysis() == null ? null : response.analysis().ticket().priority())
                        + ", questions=" + response.questions());
            }
        }

        return new EvalReport(cases.size(), passed, cases.size() - passed, failures);
    }

    private boolean stepExpectationPassed(String runId, EvalCase evalCase) {
        var steps = agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .map(AgentStep::getStepName)
                .toList();
        if (evalCase.expectGapAnalysis()) {
            if (!steps.contains("INFO_GAP_ANALYSIS") || !steps.contains("COMPLETENESS_DECISION")) {
                return false;
            }
        }
        if (evalCase.expectEvidence()) {
            if (!steps.contains("AGENT_PLAN") || !steps.contains("TOOL_SELECTION")) {
                return false;
            }
        }
        return (!evalCase.expectKnowledgeSearch() || steps.contains("KNOWLEDGE_SEARCH"))
                && (!evalCase.expectEvidence() || steps.contains("EVIDENCE_COLLECTION"))
                && (evalCase.expectKnowledgeSearch() || !steps.contains("KNOWLEDGE_SEARCH"));
    }

    private boolean planExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectPlanActions().isEmpty() && evalCase.expectSkipPlanActions().isEmpty()) {
            return true;
        }
        String planOutput = agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .filter(step -> "AGENT_PLAN".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .findFirst()
                .orElse("");
        boolean actionsOk = evalCase.expectPlanActions().stream().allMatch(planOutput::contains);
        boolean skippedOk = evalCase.expectSkipPlanActions().stream().allMatch(planOutput::contains);
        return actionsOk && skippedOk;
    }

    private boolean selectedToolExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectSelectedTools().isEmpty()) {
            return true;
        }
        List<String> executed = toolExecutionLogRepository.findByRunId(runId).stream()
                .map(ToolResult::toolName)
                .toList();
        boolean allSelectedPresent = evalCase.expectSelectedTools().stream().allMatch(executed::contains);
        boolean similarSkipped = !evalCase.expectSkipPlanActions().contains("SIMILAR_CASE_SEARCH")
                || !executed.contains("searchSimilarCases");
        return allSelectedPresent && similarSkipped;
    }

    private boolean semanticFollowUpPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (!evalCase.expectSemanticFollowUp()) {
            return true;
        }
        if (response.questions() == null || response.questions().isEmpty()) {
            return false;
        }
        String joined = String.join(" ", response.questions());
        return evalCase.questionKeywords().stream().anyMatch(joined::contains);
    }

    private boolean toolExpectationPassed(String runId, EvalCase evalCase) {
        if (!evalCase.expectToolLog()) {
            return toolExecutionLogRepository.findByRunId(runId).isEmpty();
        }
        return !toolExecutionLogRepository.findByRunId(runId).isEmpty();
    }

    private boolean issueTypeExpectationPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (evalCase.expectedIssueType() == null) {
            return true;
        }
        return response.analysis() != null
                && evalCase.expectedIssueType().equals(response.analysis().ticket().issueType());
    }

    private boolean rootCauseExpectationPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (!evalCase.expectRootCauseEvidence()) {
            return true;
        }
        return response.analysis() != null
                && response.analysis().rootCause() != null
                && response.analysis().rootCause().evidence() != null
                && !response.analysis().rootCause().evidence().isEmpty();
    }
}
