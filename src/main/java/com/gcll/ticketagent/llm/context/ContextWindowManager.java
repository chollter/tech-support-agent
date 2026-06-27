package com.gcll.ticketagent.llm.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上下文窗口管理：控制喂给 LLM 的内容长度，防 context 膨胀。
 *
 * <p>三个问题驱动：
 * <ul>
 *   <li><b>token 成本</b>：ReAct 多轮后对话历史累积，输入 token 暴增</li>
 *   <li><b>注意力稀释</b>：长 context 导致 lost-in-the-middle，LLM 忽略中间信息</li>
 *   <li><b>超窗口</b>：超模型 context 上限直接报错</li>
 * </ul>
 *
 * <p><b>策略</b>（按优先级）：
 * <ol>
 *   <li>硬截断：单段内容超 {@code maxChars} 时截断，尾部保留省略标记</li>
 *   <li>头部+尾部保留：优先保留首尾（LLM 对首尾注意力更强），中间折叠</li>
 *   <li>多段拼接上限：工具结果/证据多段时，按段截断，保留每段头尾</li>
 * </ol>
 *
 * <p><b>token 估算</b>：用 Spring AI 自带的 {@link JTokkitTokenCountEstimator}（基于 jtokkit，
 * cl100k_base 编码）替代旧的字符近似。注意 jtokkit 词表偏 OpenAI，对通义 qwen 等国产模型有
 * 10-20% 误差（尤其中文），因此本类返回值用于"阈值触发"而非精确计费——调用方应配合
 * {@code SummarizingChatMemoryAdvisor} 的阈值余量（见阶段 A 设计）使用。截断部分仍是字符级，
 * 因为截断目标是大段原文（工单内容），字符级 + 余量在该场景够用。
 */
@Component
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    /** 单段内容最大字符数（默认 4000，约 6000 token，留足 prompt 模板空间）。 */
    private final int maxCharsPerSegment;

    /** 多段拼接时每段最大字符数（默认 800，防证据段过多撑爆）。 */
    private final int maxCharsPerMultiSegment;

    /** 多段拼接的最大段数（默认 5，超出丢弃尾部低相关段）。 */
    private final int maxSegments;

    /**
     * token 估算器（Spring AI 自带，jtokkit cl100k）。线程安全单例。
     * 对通义 qwen 有 10-20% 误差，仅用于阈值触发，不用于计费（见类注释）。
     */
    private final TokenCountEstimator tokenEstimator = new JTokkitTokenCountEstimator();

    public ContextWindowManager(
            @Value("${opsmind.llm.context.max-chars-per-segment:4000}") int maxCharsPerSegment,
            @Value("${opsmind.llm.context.max-chars-per-multi-segment:800}") int maxCharsPerMultiSegment,
            @Value("${opsmind.llm.context.max-segments:5}") int maxSegments) {
        this.maxCharsPerSegment = maxCharsPerSegment;
        this.maxCharsPerMultiSegment = maxCharsPerMultiSegment;
        this.maxSegments = maxSegments;
    }

    /**
     * 截断单段长内容。保留首尾（LLM 对首尾注意力强），中间折叠。
     *
     * @param content 原始内容
     * @return 截断后的内容；未超限原样返回
     */
    public String truncate(String content) {
        if (content == null || content.length() <= maxCharsPerSegment) {
            return content;
        }
        int keep = maxCharsPerSegment / 2;
        String truncated = content.substring(0, keep)
                + "\n...[已截断 " + (content.length() - maxCharsPerSegment) + " 字符]...\n"
                + content.substring(content.length() - keep);
        log.debug("Context truncated, originalLen={}, keptLen={}", content.length(), truncated.length());
        return truncated;
    }

    /**
     * 截断多段拼接内容（如多个工具结果）。每段截断到 maxCharsPerMultiSegment，
     * 总段数超 maxSegments 丢弃尾部。
     *
     * @param segments 多段内容
     * @param separator 段间分隔符
     * @return 截断后的拼接串
     */
    public String truncateMulti(java.util.List<String> segments, String separator) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        java.util.List<String> truncated = new java.util.ArrayList<>();
        int count = 0;
        for (String seg : segments) {
            if (count >= maxSegments) {
                log.debug("Context multi-segment exceeded max {}, dropped {} tail segments",
                        maxSegments, segments.size() - maxSegments);
                break;
            }
            truncated.add(truncateSegment(seg));
            count++;
        }
        return String.join(separator == null ? "\n---\n" : separator, truncated);
    }

    private String truncateSegment(String seg) {
        if (seg == null) return "";
        if (seg.length() <= maxCharsPerMultiSegment) return seg;
        return seg.substring(0, maxCharsPerMultiSegment)
                + "...[截断 " + (seg.length() - maxCharsPerMultiSegment) + " 字符]";
    }

    /**
     * 估算字符串的 token 数。用 jtokkit（cl100k_base）替代旧的字符近似。
     *
     * <p>注意 jtokkit 词表偏 OpenAI，对通义 qwen 有 10-20% 误差（尤其中文），
     * 仅用于阈值触发（如摘要决策），不用于精确计费——调用方应配合阈值余量使用。
     */
    public int estimateTokens(String content) {
        if (content == null || content.isEmpty()) return 0;
        return tokenEstimator.estimate(content);
    }

    public int getMaxCharsPerSegment() { return maxCharsPerSegment; }
}
