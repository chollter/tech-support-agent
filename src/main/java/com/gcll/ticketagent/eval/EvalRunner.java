package com.gcll.ticketagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.eval.judge.EvalJudgeService;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvalRunner {

    static final String DEFAULT_SUITE = "eval/eval-cases.json";

    private static final List<String> NO_PLAN = List.of();
    private static final List<String> NO_KEYWORDS = List.of();
    private static final String NO_ISSUE_TYPE = null;

    private final TicketApplicationService ticketApplicationService;
    private final AgentRunRepository agentRunRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final ObjectMapper objectMapper;
    private final EvalJudgeService evalJudgeService;

    public EvalRunner(
            TicketApplicationService ticketApplicationService,
            AgentRunRepository agentRunRepository,
            ToolExecutionLogRepository toolExecutionLogRepository,
            ObjectMapper objectMapper,
            EvalJudgeService evalJudgeService
    ) {
        this.ticketApplicationService = ticketApplicationService;
        this.agentRunRepository = agentRunRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.objectMapper = objectMapper;
        this.evalJudgeService = evalJudgeService;
    }

    public EvalReport run() {
        List<EvalCase> cases = loadCases(DEFAULT_SUITE);

        int passed = 0;
        List<String> failures = new ArrayList<>();
        Map<String, GroupCounter> groups = new LinkedHashMap<>();
        // judge 探活：不可用则完全跳过质量评测（不影响现有流程断言）
        boolean judgeActive = evalJudgeService.available();
        // 收集有根因输出的 case（用于 judge 评分）：caseId + 根因文本
        Map<String, String> rootCauseTexts = judgeActive ? new LinkedHashMap<>() : null;

        for (EvalCase evalCase : cases) {
            GroupCounter counter = groups.computeIfAbsent(groupName(evalCase), ignored -> new GroupCounter());
            counter.total++;
            var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                    "eval-" + evalCase.id() + "-" + UUID.randomUUID(),
                    "u1001",
                    evalCase.title(),
                    evalCase.description(),
                    "EVAL",
                    null,
                    null
            ));
            // judge 可用时，收集根因文本（只对有根因输出的 case）
            if (judgeActive && evalCase.expectRootCauseEvidence()
                    && response.analysis() != null && response.analysis().rootCause() != null) {
                String rootCauseJson = writeRootCauseJson(response.analysis().rootCause());
                if (rootCauseJson != null) {
                    rootCauseTexts.put(evalCase.id(), rootCauseJson);
                }
            }
            boolean casePassed = response.replyType() == evalCase.expectedReplyType()
                    && response.status() == evalCase.expectedStatus()
                    && (evalCase.expectedPriority() == null
                    || (response.analysis() != null && evalCase.expectedPriority().equals(response.analysis().ticket().priority())))
                    && (evalCase.maxQuestions() == 0
                    || (response.questions() != null && response.questions().size() <= evalCase.maxQuestions()))
                    && (!evalCase.needHumanConfirm()
                    || (response.analysis() != null && response.analysis().humanConfirm().required()))
                    && stepExpectationPassed(response.runId(), evalCase)
                    && knowledgeDegradedExpectationPassed(response.runId(), evalCase)
                    && planExpectationPassed(response.runId(), evalCase)
                    && toolExpectationPassed(response.runId(), evalCase)
                    && selectedToolExpectationPassed(response.runId(), evalCase)
                    && failedToolExpectationPassed(response.runId(), evalCase)
                    && rootCauseExpectationPassed(response, evalCase)
                    && semanticFollowUpPassed(response, evalCase)
                    && issueTypeExpectationPassed(response, evalCase);
            if (casePassed) {
                passed++;
                counter.passed++;
            } else {
                failures.add(evalCase.id() + " failed: replyType=" + response.replyType()
                        + ", status=" + response.status() + ", priority="
                        + (response.analysis() == null ? null : response.analysis().ticket().priority())
                        + ", questions=" + response.questions());
            }
        }

        List<EvalGroupReport> groupReports = groups.entrySet().stream()
                .map(entry -> new EvalGroupReport(
                        entry.getKey(),
                        entry.getValue().total,
                        entry.getValue().passed,
                        entry.getValue().total - entry.getValue().passed
                ))
                .toList();
        return new EvalReport(DEFAULT_SUITE, cases.size(), passed, cases.size() - passed,
                groupReports, failures, buildQualitySummary(cases, rootCauseTexts));
    }

    /**
     * 构造质量评测汇总。judge 不可用时返回 skipped；否则对收集到的根因文本逐个调 judge 打分。
     * judge 失败的 case 记 score=null，不计入平均分（降级）。
     */
    private EvalQualitySummary buildQualitySummary(List<EvalCase> cases, Map<String, String> rootCauseTexts) {
        if (rootCauseTexts == null || rootCauseTexts.isEmpty()) {
            return new EvalQualitySummary(true, 0.0, 0, List.of());
        }
        Map<String, EvalCase> caseById = cases.stream()
                .collect(java.util.stream.Collectors.toMap(EvalCase::id, c -> c, (a, b) -> a));
        List<EvalQualitySummary.CaseScore> scores = new ArrayList<>();
        double sum = 0;
        int scored = 0;
        for (Map.Entry<String, String> entry : rootCauseTexts.entrySet()) {
            EvalCase evalCase = caseById.get(entry.getKey());
            if (evalCase == null) {
                continue;
            }
            com.gcll.ticketagent.eval.judge.EvalQualityScore score = evalJudgeService.score(evalCase, entry.getValue());
            scores.add(new EvalQualitySummary.CaseScore(entry.getKey(), score));
            if (score != null) {
                sum += score.score();
                scored++;
            }
        }
        double average = scored > 0 ? sum / scored : 0.0;
        return new EvalQualitySummary(false, average, scored, scores);
    }

    private String writeRootCauseJson(Object rootCause) {
        try {
            return objectMapper.writeValueAsString(rootCause);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<EvalCase> loadCases(String path) {
        try {
            return objectMapper.readValue(
                    new ClassPathResource(path).getInputStream(),
                    new TypeReference<List<EvalCase>>() {
                    }
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load eval cases from " + path, ex);
        }
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

    private boolean knowledgeDegradedExpectationPassed(String runId, EvalCase evalCase) {
        if (!evalCase.expectKnowledgeDegraded()) {
            return true;
        }
        return agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .filter(step -> "KNOWLEDGE_SEARCH".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .filter(output -> output != null && output.contains("degraded:"))
                .findFirst()
                .isPresent();
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

    private boolean failedToolExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectFailedTools().isEmpty()) {
            return true;
        }
        List<ToolResult> executed = toolExecutionLogRepository.findByRunId(runId);
        return evalCase.expectFailedTools().stream().allMatch(expected ->
                executed.stream().anyMatch(result ->
                        expected.equals(result.toolName()) && !result.success()));
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

    private String groupName(EvalCase evalCase) {
        return evalCase.scenarioType() == null || evalCase.scenarioType().isBlank()
                ? "default"
                : evalCase.scenarioType();
    }

    private static final class GroupCounter {
        private int total;
        private int passed;
    }
}
