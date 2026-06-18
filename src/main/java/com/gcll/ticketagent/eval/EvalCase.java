package com.gcll.ticketagent.eval;

import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.domain.AgentRunStatus;

import java.util.List;

public record EvalCase(
        String id,
        String scenarioType,
        String title,
        String description,
        ReplyType expectedReplyType,
        String expectedPriority,
        AgentRunStatus expectedStatus,
        int maxQuestions,
        boolean needHumanConfirm,
        boolean expectKnowledgeSearch,
        boolean expectEvidence,
        boolean expectToolLog,
        boolean expectRootCauseEvidence,
        boolean expectGapAnalysis,
        boolean expectSemanticFollowUp,
        boolean expectKnowledgeDegraded,
        List<String> questionKeywords,
        List<String> expectPlanActions,
        List<String> expectSkipPlanActions,
        List<String> expectSelectedTools,
        List<String> expectFailedTools,
        String expectedIssueType,
        /** LLM-as-judge 根因评分标准；null 表示用通用 rubric。仅 expectRootCauseEvidence=true 时参与评分。 */
        String rootCauseRubric
) {
}
