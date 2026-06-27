package com.gcll.ticketagent.tool.react;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 原生 ToolCallback 适配器：把现有 {@link ToolGateway} 包装成 Spring AI 原生工具，
 * 供 ReAct 循环里 LLM 自主调用（推理时自己决定调哪个工具、调几次）。
 *
 * <h3>设计：适配器而非重写</h3>
 * 现有工具签名是 {@code execute(TicketExtractResult, String)}——参数由代码用 extract 兜底，
 * LLM 不传参。原生 ToolCallback 要求工具方法接收 LLM 传的具体参数。
 * 这里不重写现有工具（保留为线性流水线兜底），而是建 @ToolBean 适配器：
 * <ul>
 *   <li>工具方法签名暴露给 LLM 的参数（受影响系统、模块、错误码等），带 {@code @ToolParam} 描述；</li>
 *   <li>方法体内通过 {@link ToolContext} 拿到本次 runId 对应的 extract/originalContent，
 *       委托底层 {@link ToolGateway} 执行（复用现有 MCP/向量检索逻辑 + ExternalCallGateway 治理）；</li>
 *   <li>治理（超时/熔断/重试）仍由底层 EvidenceCollectionService 路径保证，
 *       这里只做"LLM 自主触发 → 取 ToolContext → 委托 → 回文本结果"。</li>
 * </ul>
 *
 * <h3>ToolContext 携带 runId 上下文</h3>
 * ReAct 循环发起请求时，用 {@code .toolContext(Map)} 把 {@code extract} 和 {@code originalContent}
 * 注入，工具方法从 ToolContext 取，避免 LLM 传错参数（extract 是已结构化的可信数据）。
 *
 * <p><b>注意</b>：当前是"伪参数"——LLM 传的参数被忽略，实际用 ToolContext 里的 extract。
 * 这是为了复用现有工具逻辑（extract 已经做了去重/校验）。后续可演进为 LLM 真正传参覆盖 extract，
 * 但那需要重写工具内部逻辑（用 LLM 参数查日志，而非 extract），属于阶段2 增强项。
 */
@Component
public class ReActToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(ReActToolAdapter.class);

    /** ToolContext 里 extract 的 key。 */
    public static final String CTX_EXTRACT = "extract";
    /** ToolContext 里 originalContent 的 key。 */
    public static final String CTX_ORIGINAL_CONTENT = "originalContent";

    private final ToolRegistry toolRegistry;

    public ReActToolAdapter(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Tool(name = "query_logs", description = "查询受影响系统/接口的实时运维日志，定位错误发生位置与堆栈。用于排查故障根因。")
    public String queryLogs(
            ToolContext toolContext,
            @ToolParam(description = "受影响的系统名（如 payment-service），用于定位查询范围", required = false) String system,
            @ToolParam(description = "受影响的模块或接口名（如 /pay/callback），进一步缩小范围", required = false) String module) {
        return delegate("query_logs", toolContext);
    }

    @Tool(name = "query_metric", description = "查询受影响系统的运行指标（CPU/内存/QPS/延迟等），判断是否有资源瓶颈或异常波动。")
    public String queryMetric(
            ToolContext toolContext,
            @ToolParam(description = "受影响的系统名", required = false) String system,
            @ToolParam(description = "关注的指标类型，如 memory/cpu/qps", required = false) String metric) {
        return delegate("query_metric", toolContext);
    }

    @Tool(name = "searchSimilarCases", description = "从历史案件知识库检索相似案例，提供可参考的根因与处置经验。检索不到不阻塞。")
    public String searchSimilarCases(
            ToolContext toolContext,
            @ToolParam(description = "工单原文关键词，用于语义检索", required = false) String query,
            @ToolParam(description = "问题类型（INCIDENT/CONSULT/REQUEST），用于过滤", required = false) String issueType) {
        return delegate("searchSimilarCases", toolContext);
    }

    /**
     * 统一委托：从 ToolContext 取 extract/originalContent，调底层 ToolGateway。
     * 当前忽略 LLM 传的参数（用 extract 兜底，复用现有工具逻辑）。
     */
    private String delegate(String toolName, ToolContext toolContext) {
        try {
            TicketExtractResult extract = (TicketExtractResult) toolContext.getContext().get(CTX_EXTRACT);
            String originalContent = (String) toolContext.getContext().get(CTX_ORIGINAL_CONTENT);
            if (extract == null || originalContent == null) {
                log.warn("ReAct tool [{}] called but ToolContext missing extract/originalContent", toolName);
                return "工具调用缺少必要上下文（extract/originalContent 未注入），无法执行。";
            }
            Optional<ToolGateway> toolOpt = toolRegistry.find(toolName);
            if (toolOpt.isEmpty()) {
                log.warn("ReAct tool [{}] not registered in ToolRegistry", toolName);
                return "工具 " + toolName + " 未注册，无法执行。";
            }
            // 委托底层工具（治理由调用方 ReActLoop 经 EvidenceCollectionService 包裹，这里直接执行）
            long start = System.currentTimeMillis();
            ToolResult result = toolOpt.get().execute(extract, originalContent);
            long durationMs = System.currentTimeMillis() - start;
            // 标注成功/失败：失败结果明确标"非工单证据"，防 LLM 把工具报错当根因（呼应防幻觉主线）
            if (result.success()) {
                return "【工具执行成功·" + durationMs + "ms】\n" + (result.output() == null ? "(无输出)" : result.output());
            } else {
                String err = result.errorMessage() == null ? "unknown error" : result.errorMessage();
                return "【工具执行失败·非工单证据】工具 " + toolName + " 调用失败：" + err
                        + "。此结果不可作为根因依据。";
            }
        } catch (Exception ex) {
            log.warn("ReAct tool [{}] delegate failed: {}", toolName, ex.getMessage());
            return "【工具执行异常·非工单证据】" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + "。此结果不可作为根因依据。";
        }
    }

    /** 工具类型查询（ReActLoop 记录用）。 */
    public ToolType toolTypeOf(String toolName) {
        return toolRegistry.find(toolName).map(ToolGateway::toolType).orElse(null);
    }
}
