package com.gcll.ticketagent.eval.adversarial;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.eval.EvalCase;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 红队编排器：用 LLM 自动生成对抗工单 → 跑进现有 Agent → 用 LLM 裁判判定是否失当 →
 * 把判定 fail 的输入沉淀成 {@link EvalCase}，逐步沉淀为回归基线。
 *
 * <p>把"系统在真实分布下哪里会崩"从脑内模拟变成可反复运行的自动化流程。
 *
 * <h3>复用约定</h3>
 * <ul>
 *   <li>Agent 执行入口：{@link TicketApplicationService#submit}（与 {@code EvalRunner} 完全相同的 in-process 入口）。</li>
 *   <li>LLM 调用：{@link LlmCallExecutor#execute}（callName {@code llm.adversarial-generate} /
 *       {@code llm.adversarial-judge}，命中 {@code llm.*} 前缀，自动继承 Retry/Timeout/CircuitBreaker）。</li>
 *   <li>JSON 解析：{@link StructuredOutputParser#parse}（自动剥 markdown 围栏）。</li>
 * </ul>
 *
 * <h3>降级策略（沿用项目韧性约定）</h3>
 * <ul>
 *   <li><b>生成降级</b>：无 API key（{@code CallResult.fail}）→ 用内置模板集生成对抗输入，闭环仍可跑、可演示。</li>
 *   <li><b>裁判降级</b>：无 API key → 用锚定规则裁判（生成器标了 {@code anchorExpectedReplyType}
 *       而实际不符 → fail；否则 pass-through，如实记录"无法判定"）。</li>
 * </ul>
 *
 * <p>整个闭环吞掉单条异常（记录 warn、继续下一条），不让一条坏输入/一次 LLM 抖动废掉整轮。
 */
@Service
public class AdversarialRedTeamService {

    private static final Logger log = LoggerFactory.getLogger(AdversarialRedTeamService.class);

    private static final String GENERATE_CALL = "llm.adversarial-generate";
    private static final String GENERATE_PROMPT = "adversarial-generate.txt";
    private static final String JUDGE_CALL = "llm.adversarial-judge";
    private static final String JUDGE_PROMPT = "adversarial-judge.txt";
    private static final String SOURCE = "ADV-REDTEAM";

    private static final List<GeneratedInput> FALLBACK_INPUTS = List.of(
            new GeneratedInput("线上挂了，麻烦看下", "OMIT_FIELD",
                    ReplyType.NEED_MORE_INFO, AgentRunStatus.WAIT_USER_INPUT, null),
            new GeneratedInput("想问下支付回调接口的超时时间现在默认是多少", "NON_INCIDENT",
                    ReplyType.NEED_MORE_INFO, AgentRunStatus.WAIT_USER_INPUT, null),
            new GeneratedInput("钱扣了单子还没动，催一下", "OMIT_FIELD",
                    ReplyType.NEED_MORE_INFO, AgentRunStatus.WAIT_USER_INPUT, null),
            new GeneratedInput("生产环境但是测试账号，影响所有线上用户", "CONTRADICTION",
                    null, null, null),
            new GeneratedInput("那个东西不行了，加个开关", "NOISE",
                    ReplyType.NEED_MORE_INFO, AgentRunStatus.WAIT_USER_INPUT, null)
    );

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final TicketApplicationService ticketApplicationService;
    private final AgentRunRepository agentRunRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final AdversarialCaseStore store;

    public AdversarialRedTeamService(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            TicketApplicationService ticketApplicationService,
            AgentRunRepository agentRunRepository,
            ToolExecutionLogRepository toolExecutionLogRepository,
            AdversarialCaseStore store
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.ticketApplicationService = ticketApplicationService;
        this.agentRunRepository = agentRunRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.store = store;
    }

    /**
     * 运行一轮红队闭环。
     *
     * @param count      想生成的对抗输入数量
     * @param strategies 攻击策略提示（透传给生成器作为 user content 引导），可为空
     * @return 红队报告（含逐条结果 + 沉淀计数）
     */
    public RedTeamReport generate(int count, List<String> strategies) {
        int n = Math.max(1, count);
        List<GeneratedInput> inputs = generateInputs(n, strategies);

        List<RedTeamReport.Item> items = new ArrayList<>();
        List<EvalCase> toSink = new ArrayList<>();
        int judged = 0;
        int failed = 0;

        for (GeneratedInput input : inputs) {
            AgentRunResponse response;
            try {
                response = submit(input);
            } catch (Exception ex) {
                log.warn("Adversarial submit failed for strategy={}, skipping: {}",
                        input.attackStrategy(), ex.getMessage());
                continue;
            }
            JudgeVerdict verdict = judge(input, response);
            judged++;
            boolean pass = verdict == null || verdict.pass;
            if (!pass) {
                failed++;
            }
            EvalCase sinkCase = pass ? null : deriveEvalCase(input, verdict, response);
            boolean sunk = sinkCase != null;
            if (sunk) {
                toSink.add(sinkCase);
            }
            items.add(new RedTeamReport.Item(
                    input.description(),
                    input.attackStrategy(),
                    response.replyType() == null ? null : response.replyType().name(),
                    response.status() == null ? null : response.status().name(),
                    pass,
                    verdict == null ? "unknown" : verdict.severity,
                    verdict == null ? "judge unavailable (anchor rule applied)" : verdict.reason,
                    sunk
            ));
        }

        int sunk = store.appendAll(toSink);
        return new RedTeamReport(inputs.size(), judged, failed, sunk, items);
    }

    // ---- 生成 ----

    private List<GeneratedInput> generateInputs(int count, List<String> strategies) {
        List<GeneratedInput> collected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GeneratedInput input = generateOne(i, strategies);
            if (input != null) {
                collected.add(input);
            }
        }
        if (collected.isEmpty()) {
            // LLM 完全不可用 → 降级到内置模板（按 count 循环取，不超量）
            for (int i = 0; i < count; i++) {
                collected.add(FALLBACK_INPUTS.get(i % FALLBACK_INPUTS.size()));
            }
        }
        return collected;
    }

    private GeneratedInput generateOne(int index, List<String> strategies) {
        String userContent = buildGenerateUserContent(index, strategies);
        CallResult<LlmResponse> result = llmCallExecutor.execute(GENERATE_CALL, GENERATE_PROMPT, userContent);
        if (!result.success() || result.value() == null) {
            return null;
        }
        try {
            GenerateJson json = parser.parse(result.value().content(), GenerateJson.class);
            if (json.description() == null || json.description().isBlank()) {
                return null;
            }
            return new GeneratedInput(
                    json.description().trim(),
                    json.attackStrategy() == null ? "UNKNOWN" : json.attackStrategy(),
                    parseReplyType(json.anchorExpectedReplyType()),
                    parseStatus(json.anchorExpectedStatus()),
                    json.anchorExpectedPriority()
            );
        } catch (Exception ex) {
            log.warn("Adversarial generate parse failed for index={}, skipping: {}", index, ex.getMessage());
            return null;
        }
    }

    private String buildGenerateUserContent(int index, List<String> strategies) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是第 ").append(index + 1).append(" 条，请生成一条与之前不同的工单输入。");
        if (strategies != null && !strategies.isEmpty()) {
            sb.append("\n本轮希望侧重以下攻击策略之一：").append(String.join("、", strategies)).append("。");
        }
        return sb.toString();
    }

    // ---- 提交 ----

    private AgentRunResponse submit(GeneratedInput input) {
        // 唯一 sessionId 规避 submit 的幂等缓存，保证同一描述可重复跑
        return ticketApplicationService.submit(new SubmitAgentRunRequest(
                "adv-" + UUID.randomUUID(),
                "u-redteam",
                null,
                input.description(),
                SOURCE,
                null,
                null
        ));
    }

    // ---- 裁判 ----

    private JudgeVerdict judge(GeneratedInput input, AgentRunResponse response) {
        String userContent = buildJudgeUserContent(input, response);
        CallResult<LlmResponse> result = llmCallExecutor.execute(JUDGE_CALL, JUDGE_PROMPT, userContent);
        if (!result.success() || result.value() == null) {
            return anchorJudge(input, response);
        }
        try {
            JudgeJson json = parser.parse(result.value().content(), JudgeJson.class);
            String severity = json.severity() == null ? "none" : json.severity();
            String reason = json.reason() == null ? "" : json.reason();
            boolean suggestedPass = json.pass() != null && json.pass();
            JudgeVerdict verdict = new JudgeVerdict(
                    suggestedPass,
                    severity,
                    reason,
                    parseReplyType(json.suggestedExpectedReplyType()),
                    parseStatus(json.suggestedExpectedStatus())
            );
            // LLM 裁判未给出明确 pass 但也给出了 suggested 期望 → 视为 fail 并采用其建议
            if (json.pass() == null && (verdict.suggestedReplyType != null || verdict.suggestedStatus != null)) {
                return new JudgeVerdict(false, severity, reason,
                        verdict.suggestedReplyType, verdict.suggestedStatus);
            }
            return verdict;
        } catch (Exception ex) {
            log.warn("Adversarial judge parse failed, falling back to anchor rule: {}", ex.getMessage());
            return anchorJudge(input, response);
        }
    }

    private String buildJudgeUserContent(GeneratedInput input, AgentRunResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户输入（工单内容）\n").append(input.description());
        sb.append("\n\n## Agent 实际处理结果");
        sb.append("\n- replyType: ").append(safe(response.replyType()));
        sb.append("\n- status: ").append(safe(response.status()));
        if (response.questions() != null && !response.questions().isEmpty()) {
            sb.append("\n- 追问问题: ").append(String.join(" / ", response.questions()));
        }
        if (response.analysis() != null) {
            sb.append("\n- priority: ").append(safe(response.analysis().ticket().priority()));
            sb.append("\n- issueType: ").append(safe(response.analysis().ticket().issueType()));
            if (response.analysis().rootCause() != null) {
                sb.append("\n- 根因假设: ").append(safe(response.analysis().rootCause().hypothesis()));
                if (response.analysis().rootCause().evidence() != null) {
                    sb.append("\n- 证据: ").append(String.join("; ", response.analysis().rootCause().evidence()));
                }
            }
            if (response.analysis().humanConfirm() != null) {
                sb.append("\n- 需人工确认: ").append(response.analysis().humanConfirm().required());
            }
        }
        sb.append("\n\n## Agent 执行步骤摘要\n").append(buildStepSummary(response.runId()));
        return sb.toString();
    }

    private String buildStepSummary(String runId) {
        try {
            AgentRun run = agentRunRepository.findById(runId).orElse(null);
            if (run == null || run.getSteps() == null || run.getSteps().isEmpty()) {
                return "(无步骤记录)";
            }
            List<String> lines = new ArrayList<>();
            for (AgentStep step : run.getSteps()) {
                String line = step.getStepName() + "[" + safe(step.getStatus()) + "]";
                if (step.getErrorMessage() != null && !step.getErrorMessage().isBlank()) {
                    line += " err=" + truncate(step.getErrorMessage(), 80);
                }
                lines.add(line);
            }
            List<ToolResult> tools = toolExecutionLogRepository.findByRunId(runId);
            if (!tools.isEmpty()) {
                lines.add("工具执行:");
                for (ToolResult t : tools) {
                    lines.add("  - " + t.toolName() + " success=" + t.success()
                            + (t.errorMessage() != null ? " err=" + truncate(t.errorMessage(), 60) : ""));
                }
            }
            return String.join("\n", lines);
        } catch (Exception ex) {
            return "(步骤摘要读取失败: " + ex.getMessage() + ")";
        }
    }

    /**
     * 裁判降级：无 LLM 时用生成器锚定的期望做规则判定。
     * 仅当生成器标注了 anchorExpectedReplyType 且与实际不符 → fail（高置信场景）；
     * 否则 pass-through（无法判定，不沉淀）。
     */
    private JudgeVerdict anchorJudge(GeneratedInput input, AgentRunResponse response) {
        if (input.anchorReplyType() != null && response.replyType() != input.anchorReplyType()) {
            return new JudgeVerdict(false, "medium",
                    "anchor rule: expected " + input.anchorReplyType() + " but got " + response.replyType(),
                    input.anchorReplyType(), input.anchorStatus());
        }
        if (input.anchorStatus() != null && response.status() != input.anchorStatus()) {
            return new JudgeVerdict(false, "medium",
                    "anchor rule: expected " + input.anchorStatus() + " but got " + response.status(),
                    input.anchorReplyType(), input.anchorStatus());
        }
        return new JudgeVerdict(true, "none", "judge unavailable; anchor matched or absent", null, null);
    }

    // ---- 沉淀 ----

    /**
     * 把"判定 fail"的输入派生为回归 {@link EvalCase}。
     * 期望优先取自裁判的建议（suggestedExpected*），其次取生成器的锚定值。
     * 关键：scenarioType="adversarial"，让 EvalRunner 把它归入独立的 group。
     */
    private EvalCase deriveEvalCase(GeneratedInput input, JudgeVerdict verdict, AgentRunResponse response) {
        ReplyType expectedReply = verdict.suggestedReplyType() != null
                ? verdict.suggestedReplyType()
                : (input.anchorReplyType() != null ? input.anchorReplyType() : response.replyType());
        AgentRunStatus expectedStatus = verdict.suggestedStatus() != null
                ? verdict.suggestedStatus()
                : (input.anchorStatus() != null ? input.anchorStatus() : response.status());
        String expectedPriority = input.anchorExpectedPriority();

        String caseId = "adv-" + UUID.randomUUID().toString().substring(0, 8);
        return new EvalCase(
                caseId,
                "adversarial",
                truncate(input.description(), 24),
                input.description(),
                expectedReply == null ? ReplyType.NEED_MORE_INFO : expectedReply,
                expectedPriority,
                expectedStatus == null ? AgentRunStatus.WAIT_USER_INPUT : expectedStatus,
                6,
                false,
                false,
                false,
                false,
                false,
                true,   // expectGapAnalysis：对抗 case 至少应跑缺口分析
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    // ---- 辅助 ----

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(Object o) {
        return o == null ? "(无)" : o.toString();
    }

    private static ReplyType parseReplyType(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return ReplyType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static AgentRunStatus parseStatus(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- 内部数据载体 ----

    private record GeneratedInput(
            String description,
            String attackStrategy,
            ReplyType anchorReplyType,
            AgentRunStatus anchorStatus,
            String anchorExpectedPriority
    ) {
    }

    private record JudgeVerdict(
            boolean pass,
            String severity,
            String reason,
            ReplyType suggestedReplyType,
            AgentRunStatus suggestedStatus
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerateJson(
            String description,
            String attackStrategy,
            String anchorExpectedReplyType,
            String anchorExpectedStatus,
            String anchorExpectedPriority,
            String rationale
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JudgeJson(
            Boolean pass,
            String severity,
            String reason,
            String suggestedExpectedReplyType,
            String suggestedExpectedStatus
    ) {
    }
}
