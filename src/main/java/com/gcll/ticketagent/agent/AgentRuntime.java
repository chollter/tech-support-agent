package com.gcll.ticketagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.HumanConfirmDto;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.RootCauseDto;
import com.gcll.ticketagent.api.dto.RoutingDto;
import com.gcll.ticketagent.api.dto.SuggestionDto;
import com.gcll.ticketagent.api.dto.TicketAnalysisDto;
import com.gcll.ticketagent.api.dto.TicketSummaryDto;
import com.gcll.ticketagent.analysis.RootCauseAnalysisService;
import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecision;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecisionService;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysisService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.AgentPlanner;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.execution.tool.ToolSelector;
import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.extract.TicketExtractService;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeProperties;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.governance.priority.PriorityEvaluationService;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingPolicyEngine;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.governance.routing.RoutingSuggestion;
import com.gcll.ticketagent.governance.routing.RoutingSuggestionService;
import com.gcll.ticketagent.governance.routing.TeamRoutingService;
import com.gcll.ticketagent.suggestion.SuggestionGenerationService;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.ticket.TicketDraft;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final TicketExtractService ticketExtractService;
    private final InfoGapAnalysisService infoGapAnalysisService;
    private final CompletenessDecisionService completenessDecisionService;
    private final AgentPlanner agentPlanner;
    private final ToolSelector toolSelector;
    private final KnowledgeSearchService knowledgeSearchService;
    private final KnowledgeProperties knowledgeProperties;
    private final EvidenceCollectionService evidenceCollectionService;
    private final RootCauseAnalysisService rootCauseAnalysisService;
    private final PriorityEvaluationService priorityEvaluationService;
    private final TeamRoutingService teamRoutingService;
    private final RoutingSuggestionService routingSuggestionService;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final SuggestionGenerationService suggestionGenerationService;
    private final HumanConfirmTrigger humanConfirmTrigger;
    private final HumanConfirmService humanConfirmService;
    private final AuditLogService auditLogService;
    private final AgentRunRepository agentRunRepository;
    private final AgentMetrics agentMetrics;
    private final ExternalCallGateway externalCallGateway;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public AgentRuntime(
            TicketExtractService ticketExtractService,
            InfoGapAnalysisService infoGapAnalysisService,
            CompletenessDecisionService completenessDecisionService,
            AgentPlanner agentPlanner,
            ToolSelector toolSelector,
            KnowledgeSearchService knowledgeSearchService,
            KnowledgeProperties knowledgeProperties,
            EvidenceCollectionService evidenceCollectionService,
            RootCauseAnalysisService rootCauseAnalysisService,
            PriorityEvaluationService priorityEvaluationService,
            TeamRoutingService teamRoutingService,
            RoutingSuggestionService routingSuggestionService,
            RoutingPolicyEngine routingPolicyEngine,
            SuggestionGenerationService suggestionGenerationService,
            HumanConfirmTrigger humanConfirmTrigger,
            HumanConfirmService humanConfirmService,
            AuditLogService auditLogService,
            AgentRunRepository agentRunRepository,
            AgentMetrics agentMetrics,
            ExternalCallGateway externalCallGateway,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.ticketExtractService = ticketExtractService;
        this.infoGapAnalysisService = infoGapAnalysisService;
        this.completenessDecisionService = completenessDecisionService;
        this.agentPlanner = agentPlanner;
        this.toolSelector = toolSelector;
        this.knowledgeSearchService = knowledgeSearchService;
        this.knowledgeProperties = knowledgeProperties;
        this.evidenceCollectionService = evidenceCollectionService;
        this.rootCauseAnalysisService = rootCauseAnalysisService;
        this.priorityEvaluationService = priorityEvaluationService;
        this.teamRoutingService = teamRoutingService;
        this.routingSuggestionService = routingSuggestionService;
        this.routingPolicyEngine = routingPolicyEngine;
        this.suggestionGenerationService = suggestionGenerationService;
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.humanConfirmService = humanConfirmService;
        this.auditLogService = auditLogService;
        this.agentRunRepository = agentRunRepository;
        this.agentMetrics = agentMetrics;
        this.externalCallGateway = externalCallGateway;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
        try {
            return doExecute(run, draft);
        } catch (Exception ex) {
            log.warn("Agent run failed, runId={}, error={}", run.getId(), ex.getMessage());
            return failRun(run, draft, ex);
        }
    }

    private AgentRunResponse doExecute(AgentRun run, TicketDraft draft) {
        long start = System.currentTimeMillis();
        StepOutcome<TicketExtractResult> extractOutcome = ticketExtractService.extract(draft.fullContent());
        TicketExtractResult extract = extractOutcome.value();
        run.setIssueType(extract.issueType().name());
        long extractCostMs = extractOutcome.costMs() > 0 ? extractOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TICKET_EXTRACT, draft.fullContent(), summarizeExtract(extract),
                extractOutcome.llmUsed(), extractOutcome.llmUsed() ? "SpringAI" : null, extractCostMs, null);

        start = System.currentTimeMillis();
        StepOutcome<InfoGapAnalysis> gapOutcome = infoGapAnalysisService.analyze(draft.fullContent(), extract);
        InfoGapAnalysis gap = gapOutcome.value();
        long gapCostMs = gapOutcome.costMs() > 0 ? gapOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.INFO_GAP_ANALYSIS, summarizeExtract(extract),
                summarizeGap(gap), gapOutcome.llmUsed(), gapOutcome.llmUsed() ? "SpringAI" : null,
                gapCostMs, null);

        start = System.currentTimeMillis();
        CompletenessDecision decision = completenessDecisionService.decide(draft.fullContent(), extract, gap);
        auditLogService.recordStep(run, AgentStepName.COMPLETENESS_DECISION, summarizeGap(gap),
                "canProceed=" + decision.canProceed() + ",reason=" + decision.decisionReason()
                        + ",missing=" + decision.missingSchemaFields()
                        + ",semanticGaps=" + decision.semanticGaps(),
                false, null, System.currentTimeMillis() - start, null);

        if (decision.needFollowUp()) {
            start = System.currentTimeMillis();
            List<String> questions = decision.followUpQuestions();
            auditLogService.recordStep(run, AgentStepName.FOLLOW_UP_QUESTION_GENERATE,
                    decision.missingSchemaFields().toString(), questions.toString(),
                    gapOutcome.llmUsed(), gapOutcome.llmUsed() ? "SpringAI" : null,
                    System.currentTimeMillis() - start, null);

            AgentRunResponse response = new AgentRunResponse(
                    run.getId(),
                    AgentRunStatus.WAIT_USER_INPUT,
                    ReplyType.NEED_MORE_INFO,
                    "当前信息不足，暂时无法判断影响范围和责任团队。请补充以下信息：",
                    questions,
                    null,
                    extractOutcome.llmUsed() || gapOutcome.llmUsed()
            );
            transactionTemplate.executeWithoutResult(status -> {
                persistRunContext(run, gap, null, null);
                run.setStatus(AgentRunStatus.WAIT_USER_INPUT);
                agentRunRepository.save(run);
            });
            return response;
        }

        return executeAnalysisPath(run, draft, extract, gap, extractOutcome.llmUsed());
    }

    private AgentRunResponse executeAnalysisPath(
            AgentRun run,
            TicketDraft draft,
            TicketExtractResult extract,
            InfoGapAnalysis gap,
            boolean extractLlmUsed
    ) {
        long start = System.currentTimeMillis();
        StepOutcome<AgentPlan> planOutcome = agentPlanner.plan(draft.fullContent(), extract);
        AgentPlan plan = planOutcome.value();
        long planCostMs = planOutcome.costMs() > 0 ? planOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.AGENT_PLAN, summarizeExtract(extract), plan.auditSummary(),
                planOutcome.llmUsed(), planOutcome.llmUsed() ? "SpringAI" : null, planCostMs, null);

        List<KnowledgeHit> hits = List.of();
        if (plan.includes(AgentAction.KNOWLEDGE_SEARCH)) {
            start = System.currentTimeMillis();
            KnowledgeSearchOutcome searchOutcome = searchKnowledgeSafely(run, draft, extract);
            hits = searchOutcome.hits();
            if (!hits.isEmpty()) {
                agentMetrics.recordRagHit();
            }
            auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(), searchOutcome.auditOutput(),
                    false, null, System.currentTimeMillis() - start,
                    searchOutcome.errorMessage());
        } else {
            auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(),
                    "skipped_by_plan:" + plan.skipped(), false, null, 0, null);
        }

        start = System.currentTimeMillis();
        StepOutcome<ToolSelection> selectionOutcome = toolSelector.select(draft.fullContent(), extract, plan);
        ToolSelection selection = selectionOutcome.value();
        long selectionCostMs = selectionOutcome.costMs() > 0 ? selectionOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TOOL_SELECTION, plan.auditSummary(), selection.auditSummary(),
                selectionOutcome.llmUsed(), selectionOutcome.llmUsed() ? "SpringAI" : null, selectionCostMs, null);

        start = System.currentTimeMillis();
        List<ToolResult> toolResults = collectEvidenceSafely(run, extract, draft, selection);
        String evidenceSummary = summarizeToolResults(toolResults);
        auditLogService.recordStep(run, AgentStepName.EVIDENCE_COLLECTION, summarizeExtract(extract),
                evidenceSummary, false, null, System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        RootCauseResult rootCause = rootCauseAnalysisService.analyze(extract, hits, toolResults);
        auditLogService.recordStep(run, AgentStepName.ROOT_CAUSE_ANALYSIS, evidenceSummary,
                rootCause.hypothesis(), rootCause.llmUsed(), rootCause.llmUsed() ? "SpringAI" : null,
                System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        PriorityResult priority = priorityEvaluationService.evaluate(extract);
        run.setPriority(priority.priority().name());
        auditLogService.recordStep(run, AgentStepName.PRIORITY_EVALUATION, summarizeExtract(extract),
                priority.toString(), false, null, System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        RoutingResult ruleRouting = teamRoutingService.route(extract, hits);
        StepOutcome<RoutingSuggestion> routingSuggestionOutcome = routingSuggestionService.suggest(
                draft.fullContent(), extract, hits, ruleRouting);
        RoutingResult routing = routingPolicyEngine.merge(ruleRouting, routingSuggestionOutcome.value());
        long routingCostMs = routingSuggestionOutcome.costMs() > 0
                ? routingSuggestionOutcome.costMs()
                : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TEAM_ROUTING,
                "rule=" + ruleRouting + ",suggestion=" + routingSuggestionOutcome.value(),
                routing.toString(),
                routingSuggestionOutcome.llmUsed(),
                routingSuggestionOutcome.llmUsed() ? "SpringAI" : null,
                routingCostMs, null);

        start = System.currentTimeMillis();
        StepOutcome<TicketSuggestion> suggestionOutcome = suggestionGenerationService.generate(extract, hits, toolResults, rootCause);
        TicketSuggestion suggestion = suggestionOutcome.value();
        run.setCurrentSummary(suggestion.summary());
        long suggestionCostMs = suggestionOutcome.costMs() > 0
                ? suggestionOutcome.costMs()
                : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.SUGGESTION_GENERATION, hits.toString(), suggestion.toString(),
                suggestionOutcome.llmUsed(), suggestionOutcome.llmUsed() ? "SpringAI" : null, suggestionCostMs, null);

        boolean aiGenerated = extractLlmUsed || rootCause.llmUsed() || routingSuggestionOutcome.llmUsed()
                || suggestionOutcome.llmUsed();

        boolean needConfirm = humanConfirmTrigger.needHumanConfirm(priority, routing, extract);
        String confirmReason = humanConfirmTrigger.reason(priority, routing, extract);

        TicketAnalysisDto analysis = buildAnalysis(extract, priority, routing, rootCause, suggestion, needConfirm, confirmReason);
        AgentRunResponse response = new AgentRunResponse(
                run.getId(),
                needConfirm ? AgentRunStatus.WAIT_HUMAN_CONFIRM : AgentRunStatus.FINAL,
                ReplyType.TICKET_ANALYSIS_RESULT,
                needConfirm ? "分析完成，等待人工确认后分派" : "分析完成",
                null,
                analysis,
                aiGenerated
        );

        if (needConfirm) {
            transactionTemplate.executeWithoutResult(status -> {
                auditLogService.recordStep(run, AgentStepName.WAIT_HUMAN_CONFIRM, analysis.toString(), confirmReason,
                        false, null, 0, null);
                humanConfirmService.createDispatchAction(run, analysis.toString(), confirmReason);
                persistRunContext(run, gap, plan, selection);
                run.setStatus(AgentRunStatus.WAIT_HUMAN_CONFIRM);
                agentRunRepository.save(run);
            });
        } else {
            transactionTemplate.executeWithoutResult(status -> {
                auditLogService.recordStep(run, AgentStepName.FINAL, analysis.toString(), "completed", false, null, 0, null);
                persistRunContext(run, gap, plan, selection);
                run.setStatus(AgentRunStatus.FINAL);
                agentRunRepository.save(run);
            });
        }

        return response;
    }

    /**
     * 向量检索经 {@link ExternalCallGateway} 治理（vector-default：retry/timelimiter/circuitbreaker）。
     * <p>超时/熔断/异常统一由 Gateway 处理，本方法只关心业务：成功用命中，失败降级为空列表。
     * 替代了原先的 {@code CompletableFuture + future.get(timeout)} 模式（后者对阻塞 IO 的 cancel 无效）。
     */
    private KnowledgeSearchOutcome searchKnowledgeSafely(AgentRun run, TicketDraft draft, TicketExtractResult extract) {
        CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
                "vector.knowledge-search",
                () -> knowledgeSearchService.search(
                        draft.fullContent(),
                        extract.affectedSystem(),
                        extract.affectedModule(),
                        extract.issueType().name()
                )
        );
        if (!result.success()) {
            String reason = result.circuitOpen() ? "circuit open"
                    : (result.error() != null ? result.error().getMessage() : "unknown");
            log.warn("Knowledge search degraded, runId={}, circuitOpen={}, reason={}",
                    run.getId(), result.circuitOpen(), reason);
            return new KnowledgeSearchOutcome(
                    Collections.emptyList(),
                    "degraded: " + reason,
                    "Knowledge search failed: " + reason
            );
        }
        List<KnowledgeHit> hits = result.value();
        return new KnowledgeSearchOutcome(hits, hits.toString(), null);
    }

    private AgentRunResponse failRun(AgentRun run, TicketDraft draft, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.recordStep(run, AgentStepName.FINAL, draft.fullContent(), "failed",
                    false, null, 0, ex.getMessage());
            run.setStatus(AgentRunStatus.FAILED);
            agentRunRepository.save(run);
        });
        return new AgentRunResponse(
                run.getId(),
                AgentRunStatus.FAILED,
                ReplyType.TICKET_ANALYSIS_RESULT,
                "分析过程出现异常，请稍后重试或联系值班人员。",
                null,
                null,
                false
        );
    }

    private TicketAnalysisDto buildAnalysis(
            TicketExtractResult extract,
            PriorityResult priority,
            RoutingResult routing,
            RootCauseResult rootCause,
            TicketSuggestion suggestion,
            boolean needConfirm,
            String confirmReason
    ) {
        TicketSummaryDto ticket = new TicketSummaryDto(
                suggestion.summary(),
                extract.issueType().name(),
                priority.priority().name(),
                extract.affectedSystem(),
                extract.affectedModule(),
                extract.impactScope()
        );
        RoutingDto routingDto = new RoutingDto(routing.primaryTeam(), routing.backupTeams(), routing.routingReason());
        RootCauseDto rootCauseDto = new RootCauseDto(
                rootCause.hypothesis(),
                rootCause.evidence(),
                rootCause.unknowns(),
                rootCause.confidence()
        );
        SuggestionDto suggestionDto = new SuggestionDto(
                suggestion.possibleCauses(),
                suggestion.actions(),
                suggestion.runbookSteps(),
                suggestion.sources()
        );
        HumanConfirmDto humanConfirm = new HumanConfirmDto(needConfirm, needConfirm ? confirmReason : null);
        return new TicketAnalysisDto(ticket, routingDto, rootCauseDto, suggestionDto, humanConfirm);
    }

    private String summarizeGap(InfoGapAnalysis gap) {
        return "ready=" + gap.readyForAnalysis()
                + ",schemaMissing=" + gap.schemaMissing()
                + ",semanticGaps=" + gap.semanticGaps()
                + ",confidence=" + gap.confidence();
    }

    private String summarizeExtract(TicketExtractResult extract) {
        return "issueType=" + extract.issueType()
                + ",system=" + extract.affectedSystem()
                + ",env=" + extract.environment()
                + ",api=" + extract.apiName();
    }

    private record KnowledgeSearchOutcome(List<KnowledgeHit> hits, String auditOutput, String errorMessage) {
    }

    private List<ToolResult> collectEvidenceSafely(
            AgentRun run,
            TicketExtractResult extract,
            TicketDraft draft,
            ToolSelection selection
    ) {
        try {
            return evidenceCollectionService.collect(run.getId(), extract, draft.fullContent(), selection);
        } catch (Exception ex) {
            log.warn("Evidence collection failed, runId={}, error={}", run.getId(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    private String summarizeToolResults(List<ToolResult> results) {
        if (results.isEmpty()) {
            return "no_tools_executed";
        }
        long successCount = results.stream().filter(ToolResult::success).count();
        return results.size() + " tools executed, " + successCount + " succeeded";
    }

    private void persistRunContext(
            AgentRun run,
            InfoGapAnalysis gap,
            AgentPlan plan,
            ToolSelection selection
    ) {
        try {
            if (gap != null) {
                run.setGapAnalysisJson(objectMapper.writeValueAsString(gap));
            }
            if (plan != null) {
                run.setAgentPlanJson(objectMapper.writeValueAsString(plan));
            }
            if (selection != null) {
                run.setToolSelectionJson(objectMapper.writeValueAsString(selection));
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize agent context, runId={}, error={}", run.getId(), ex.getMessage());
        }
    }
}
