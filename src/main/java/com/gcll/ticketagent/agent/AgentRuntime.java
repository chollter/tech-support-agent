package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.agent.workflow.AnalysisWorkflowService;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecision;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecisionService;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysisService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.extract.TicketExtractService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.ticket.TicketDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final TicketExtractService ticketExtractService;
    private final InfoGapAnalysisService infoGapAnalysisService;
    private final CompletenessDecisionService completenessDecisionService;
    private final AnalysisWorkflowService analysisWorkflowService;
    private final AuditLogService auditLogService;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunContextPersister contextPersister;
    private final AgentResponseAssembler responseAssembler;
    private final AgentAuditSummaryFormatter summaryFormatter;

    public AgentRuntime(
            TicketExtractService ticketExtractService,
            InfoGapAnalysisService infoGapAnalysisService,
            CompletenessDecisionService completenessDecisionService,
            AnalysisWorkflowService analysisWorkflowService,
            AuditLogService auditLogService,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate,
            AgentRunContextPersister contextPersister,
            AgentResponseAssembler responseAssembler,
            AgentAuditSummaryFormatter summaryFormatter
    ) {
        this.ticketExtractService = ticketExtractService;
        this.infoGapAnalysisService = infoGapAnalysisService;
        this.completenessDecisionService = completenessDecisionService;
        this.analysisWorkflowService = analysisWorkflowService;
        this.auditLogService = auditLogService;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
        this.contextPersister = contextPersister;
        this.responseAssembler = responseAssembler;
        this.summaryFormatter = summaryFormatter;
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
        auditLogService.recordStep(run, AgentStepName.TICKET_EXTRACT, draft.fullContent(),
                summaryFormatter.summarizeExtract(extract), extractOutcome.llmUsed(),
                extractOutcome.llmUsed() ? "SpringAI" : null, extractCostMs, null);

        start = System.currentTimeMillis();
        StepOutcome<InfoGapAnalysis> gapOutcome = infoGapAnalysisService.analyze(draft.fullContent(), extract);
        InfoGapAnalysis gap = gapOutcome.value();
        long gapCostMs = gapOutcome.costMs() > 0 ? gapOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.INFO_GAP_ANALYSIS, summaryFormatter.summarizeExtract(extract),
                summaryFormatter.summarizeGap(gap), gapOutcome.llmUsed(),
                gapOutcome.llmUsed() ? "SpringAI" : null, gapCostMs, null);

        start = System.currentTimeMillis();
        CompletenessDecision decision = completenessDecisionService.decide(draft.fullContent(), extract, gap);
        auditLogService.recordStep(run, AgentStepName.COMPLETENESS_DECISION, summaryFormatter.summarizeGap(gap),
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

            AgentRunResponse response = responseAssembler.needMoreInfo(
                    run.getId(), questions, extractOutcome.llmUsed() || gapOutcome.llmUsed());
            transactionTemplate.executeWithoutResult(status -> {
                contextPersister.persist(run, gap, null, null);
                run.setStatus(AgentRunStatus.WAIT_USER_INPUT);
                agentRunRepository.save(run);
            });
            return response;
        }

        return analysisWorkflowService.execute(run, draft, extract, gap, extractOutcome.llmUsed());
    }

    private AgentRunResponse failRun(AgentRun run, TicketDraft draft, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.recordStep(run, AgentStepName.FINAL, draft.fullContent(), "failed",
                    false, null, 0, ex.getMessage());
            run.setStatus(AgentRunStatus.FAILED);
            agentRunRepository.save(run);
        });
        return responseAssembler.failed(run.getId());
    }
}
