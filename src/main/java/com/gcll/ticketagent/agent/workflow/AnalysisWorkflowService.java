package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.agent.AgentAuditSummaryFormatter;
import com.gcll.ticketagent.agent.AgentResponseAssembler;
import com.gcll.ticketagent.agent.AgentRunContextPersister;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.AgentPlanner;
import com.gcll.ticketagent.analysis.RootCauseAnalysisService;
import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.TicketAnalysisDto;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.eval.EvalFaultInjection;
import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.execution.tool.ToolSelector;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.governance.priority.PriorityEvaluationService;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingPolicyEngine;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.governance.routing.RoutingSuggestion;
import com.gcll.ticketagent.governance.routing.RoutingSuggestionService;
import com.gcll.ticketagent.governance.routing.TeamRoutingService;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.suggestion.SuggestionGenerationService;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.ticket.TicketDraft;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class AnalysisWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorkflowService.class);

    private final AgentPlanner agentPlanner;
    private final ToolSelector toolSelector;
    private final KnowledgeSearchService knowledgeSearchService;
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
    private final AgentRunContextPersister contextPersister;
    private final AgentResponseAssembler responseAssembler;
    private final AgentAuditSummaryFormatter summaryFormatter;

    public AnalysisWorkflowService(
            AgentPlanner agentPlanner,
            ToolSelector toolSelector,
            KnowledgeSearchService knowledgeSearchService,
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
            AgentRunContextPersister contextPersister,
            AgentResponseAssembler responseAssembler,
            AgentAuditSummaryFormatter summaryFormatter
    ) {
        this.agentPlanner = agentPlanner;
        this.toolSelector = toolSelector;
        this.knowledgeSearchService = knowledgeSearchService;
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
        this.contextPersister = contextPersister;
        this.responseAssembler = responseAssembler;
        this.summaryFormatter = summaryFormatter;
    }

    public AgentRunResponse execute(
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
        auditLogService.recordStep(run, AgentStepName.AGENT_PLAN, summaryFormatter.summarizeExtract(extract),
                plan.auditSummary(), planOutcome.llmUsed(), planOutcome.llmUsed() ? "SpringAI" : null, planCostMs, null);

        List<KnowledgeHit> hits = searchKnowledgeIfNeeded(run, draft, extract, plan);

        start = System.currentTimeMillis();
        StepOutcome<ToolSelection> selectionOutcome = toolSelector.select(draft.fullContent(), extract, plan);
        ToolSelection selection = selectionOutcome.value();
        long selectionCostMs = selectionOutcome.costMs() > 0 ? selectionOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TOOL_SELECTION, plan.auditSummary(), selection.auditSummary(),
                selectionOutcome.llmUsed(), selectionOutcome.llmUsed() ? "SpringAI" : null, selectionCostMs, null);

        start = System.currentTimeMillis();
        List<ToolResult> toolResults = collectEvidenceSafely(run, extract, draft, selection);
        String evidenceSummary = summaryFormatter.summarizeToolResults(toolResults);
        auditLogService.recordStep(run, AgentStepName.EVIDENCE_COLLECTION, summaryFormatter.summarizeExtract(extract),
                evidenceSummary, false, null, System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        RootCauseResult rootCause = rootCauseAnalysisService.analyze(extract, hits, toolResults);
        auditLogService.recordStep(run, AgentStepName.ROOT_CAUSE_ANALYSIS, evidenceSummary,
                rootCause.hypothesis(), rootCause.llmUsed(), rootCause.llmUsed() ? "SpringAI" : null,
                System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        PriorityResult priority = priorityEvaluationService.evaluate(extract);
        run.setPriority(priority.priority().name());
        auditLogService.recordStep(run, AgentStepName.PRIORITY_EVALUATION, summaryFormatter.summarizeExtract(extract),
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
        TicketAnalysisDto analysis = responseAssembler.buildAnalysis(
                extract, priority, routing, rootCause, suggestion, needConfirm, confirmReason);

        AgentRunResponse response = responseAssembler.analysisResult(run.getId(), analysis, needConfirm, aiGenerated);
        completeRun(run, gap, plan, selection, analysis, needConfirm, confirmReason, routing);
        return response;
    }

    private List<KnowledgeHit> searchKnowledgeIfNeeded(
            AgentRun run,
            TicketDraft draft,
            TicketExtractResult extract,
            AgentPlan plan
    ) {
        if (!plan.includes(AgentAction.KNOWLEDGE_SEARCH)) {
            auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(),
                    "skipped_by_plan:" + plan.skipped(), false, null, 0, null);
            return List.of();
        }

        long start = System.currentTimeMillis();
        KnowledgeSearchOutcome searchOutcome = searchKnowledgeSafely(run, draft, extract);
        List<KnowledgeHit> hits = searchOutcome.hits();
        if (!hits.isEmpty()) {
            agentMetrics.recordRagHit();
        }
        auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(), searchOutcome.auditOutput(),
                false, null, System.currentTimeMillis() - start, searchOutcome.errorMessage());
        return hits;
    }

    /**
     * Knowledge search is governed by {@link ExternalCallGateway}; failures degrade to an empty hit list.
     */
    private KnowledgeSearchOutcome searchKnowledgeSafely(AgentRun run, TicketDraft draft, TicketExtractResult extract) {
        if (EvalFaultInjection.shouldFailKnowledgeSearch(draft.fullContent())) {
            return new KnowledgeSearchOutcome(
                    Collections.emptyList(),
                    "degraded: injected knowledge search failure",
                    "Knowledge search failed: injected failure for eval"
            );
        }
        CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
                "vector.knowledge-search",
                () -> knowledgeSearchService.search(
                        EvalFaultInjection.sanitize(draft.fullContent()),
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

    private void completeRun(
            AgentRun run,
            InfoGapAnalysis gap,
            AgentPlan plan,
            ToolSelection selection,
            TicketAnalysisDto analysis,
            boolean needConfirm,
            String confirmReason,
            RoutingResult routing
    ) {
        if (needConfirm) {
            transactionTemplate.executeWithoutResult(status -> {
                auditLogService.recordStep(run, AgentStepName.WAIT_HUMAN_CONFIRM, analysis.toString(), confirmReason,
                        false, null, 0, null);
                humanConfirmService.createDispatchAction(run, analysis.toString(), confirmReason, routing.primaryTeam());
                contextPersister.persist(run, gap, plan, selection);
                run.setStatus(AgentRunStatus.WAIT_HUMAN_CONFIRM);
                agentRunRepository.save(run);
            });
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.recordStep(run, AgentStepName.FINAL, analysis.toString(), "completed", false, null, 0, null);
            contextPersister.persist(run, gap, plan, selection);
            run.setStatus(AgentRunStatus.FINAL);
            agentRunRepository.save(run);
        });
    }

    private record KnowledgeSearchOutcome(List<KnowledgeHit> hits, String auditOutput, String errorMessage) {
    }
}
