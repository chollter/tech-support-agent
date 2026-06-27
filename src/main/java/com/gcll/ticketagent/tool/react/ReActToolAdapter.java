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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 原生 ToolCallback 适配器：把现有 {@link ToolGateway} 包装成 Spring AI 原生工具，
 * 供 ReAct 循环里 LLM 自主调用（推理时自己决定调哪个工具、调几次、传什么参数）。
 *
 * <h3>设计：适配器而非重写</h3>
 * 现有工具签名是 {@code execute(TicketExtractResult, String)}——用 extract 的字段查询。
 * 这里建 @ToolBean 适配器，工具方法暴露参数给 LLM（system/module 等，带 @ToolParam 描述），
 * 方法体内把 LLM 参数<b>合并进 extract</b>（{@link ToolArgMerger}），合成修正版后委托底层执行。
 *
 * <h3>阶段B：真·自主传参（不再是伪参数）</h3>
 * LLM 传的 system/module 现在被真正使用——通过 {@link ToolArgMerger} 覆盖 extract 对应字段。
 * 价值：LLM 能修正/补充 extract 没抽准的字段（如 extract.affectedSystem=null 时 LLM 推断补充）。
 * LLM 没传参时保留 extract 原值，退化为现状行为（不比现在差）。
 *
 * <h3>ToolContext 携带 runId 上下文</h3>
 * ReAct 循环发起请求时，用 {@code .toolContext(Map)} 把 {@code extract} 和 {@code originalContent}
 * 注入，工具方法从 ToolContext 取。
 */
@Component
public class ReActToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(ReActToolAdapter.class);

    /** ToolContext 里 extract 的 key。 */
    public static final String CTX_EXTRACT = "extract";
    /** ToolContext 里 originalContent 的 key。 */
    public static final String CTX_ORIGINAL_CONTENT = "originalContent";

    private final ToolRegistry toolRegistry;
    private final ToolArgMerger argMerger;

    public ReActToolAdapter(ToolRegistry toolRegistry, ToolArgMerger argMerger) {
        this.toolRegistry = toolRegistry;
        this.argMerger = argMerger;
    }

    @Tool(name = "query_logs", description = "查询受影响系统/接口的实时运维日志，定位错误发生位置与堆栈。用于排查故障根因。传入 system/module 缩小查询范围；不传则用已抽取字段。")
    public String queryLogs(
            ToolContext toolContext,
            @ToolParam(description = "受影响的系统名（如 payment-service），用于定位查询范围。若工单未明确可从上下文推断。", required = false) String system,
            @ToolParam(description = "受影响的模块或接口名（如 /pay/callback），进一步缩小范围。", required = false) String module) {
        return delegate("query_logs", toolContext, argsMap(system, module));
    }

    @Tool(name = "query_metric", description = "查询受影响系统的运行指标（CPU/内存/QPS/延迟等），判断是否有资源瓶颈或异常波动。传入 system 缩小范围；不传则用已抽取字段。")
    public String queryMetric(
            ToolContext toolContext,
            @ToolParam(description = "受影响的系统名。若工单未明确可从上下文推断。", required = false) String system,
            @ToolParam(description = "关注的指标类型，如 memory/cpu/qps。", required = false) String metric) {
        // query_metric 的 metric 参数当前底层不直接用（底层查全部指标），只合并 system
        return delegate("query_metric", toolContext, argsMap(system, null));
    }

    @Tool(name = "searchSimilarCases", description = "从历史案件知识库检索相似案例，提供可参考的根因与处置经验。检索不到不阻塞。")
    public String searchSimilarCases(
            ToolContext toolContext,
            @ToolParam(description = "工单原文关键词，用于语义检索。", required = false) String query,
            @ToolParam(description = "问题类型（INCIDENT/CONSULT/REQUEST），用于过滤。", required = false) String issueType) {
        // 语义检索工具用 originalContent，不依赖 extract 字段，无需合并参数
        return delegate("searchSimilarCases", toolContext, Map.of());
    }

    /**
     * 统一委托：从 ToolContext 取 extract/originalContent，合并 LLM 参数，调底层 ToolGateway。
     */
    private String delegate(String toolName, ToolContext toolContext, Map<String, String> llmArgs) {
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
            // 阶段B：合并 LLM 参数到 extract（非空参数覆盖对应字段），合成修正版
            TicketExtractResult mergedExtract = argMerger.merge(extract, llmArgs);
            long start = System.currentTimeMillis();
            ToolResult result = toolOpt.get().execute(mergedExtract, originalContent);
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

    /** 组装 LLM 参数 map（过滤 null）。 */
    private Map<String, String> argsMap(String system, String module) {
        Map<String, String> args = new LinkedHashMap<>();
        if (system != null && !system.isBlank()) {
            args.put(ToolArgMerger.PARAM_SYSTEM, system.trim());
        }
        if (module != null && !module.isBlank()) {
            args.put(ToolArgMerger.PARAM_MODULE, module.trim());
        }
        return args;
    }

    /** 工具类型查询（ReActLoop 记录用）。 */
    public ToolType toolTypeOf(String toolName) {
        return toolRegistry.find(toolName).map(ToolGateway::toolType).orElse(null);
    }
}
