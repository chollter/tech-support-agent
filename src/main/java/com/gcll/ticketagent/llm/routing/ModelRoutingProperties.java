package com.gcll.ticketagent.llm.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 多模型路由配置。按 callName 前缀映射到不同模型（按复杂度选型），
 * 实现"简单环节用快模型省钱、复杂环节用强模型保效果"的分级调用。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * opsmind:
 *   llm:
 *     routing:
 *       default-model: qwen-plus              # 兜底模型
 *       mappings:                              # callName 前缀 → 模型名
 *         llm.ticket-extract: qwen-turbo       # 抽取：简单，用快模型
 *         llm.info-gap:      qwen-turbo        # 缺口：简单，用快模型
 *         llm.tool-select:   qwen-turbo        # 工具选择：简单，用快模型
 *         llm.follow-up:     qwen-turbo        # 追问：简单，用快模型
 *         llm.root-cause:    qwen-plus         # 根因：复杂，用强模型
 *         llm.routing-suggest: qwen-plus       # 路由建议：复杂，用强模型
 *         llm.suggestion:    qwen-plus         # 处置建议：复杂，用强模型
 *       chat-memory:
 *         enabled: true                        # 工单级短期记忆开关
 *         history-size: 10                     # 每个工单保留最近 N 条消息
 * </pre>
 *
 * <p><b>设计原则</b>：路由表配置化，不硬编码在 ModelRouter 里，可按成本/效果调参。
 * 未命中的 callName 用 default-model 兜底，避免漏配导致调用失败。
 */
@ConfigurationProperties(prefix = "opsmind.llm.routing")
public class ModelRoutingProperties {

    /** 兜底模型：callName 未命中任何前缀时用。默认沿用现有 qwen-plus，保证不配也能跑。 */
    private String defaultModel = "qwen-plus";

    /** callName（精确或前缀匹配）→ 模型名。 */
    private Map<String, String> mappings = new HashMap<>();

    private ChatMemory chatMemory = new ChatMemory();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, String> getMappings() {
        return mappings;
    }

    public void setMappings(Map<String, String> mappings) {
        this.mappings = mappings;
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    public void setChatMemory(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    /** 工单级短期记忆配置。 */
    public static class ChatMemory {
        /** 是否启用 ChatMemory advisor。关闭时走单轮（现状）。 */
        private boolean enabled = true;

        /** 每个工单（conversationId=runId）保留最近多少条消息。超出的旧消息被丢弃。 */
        private int historySize = 10;

        /** 摘要模式：true=用 SummarizingChatMemoryAdvisor（LLM 摘要旧消息），false=退化为按条数裁剪。 */
        private boolean summarize = true;

        /** 目标模型的上下文窗口（token）。超此阈值触发摘要。qwen-plus 默认 28000（窗口 32k 留 12% 安全垫）。 */
        private int contextWindowTokens = 28000;

        /** 触发摘要的阈值比例（相对 contextWindowTokens）。默认 0.65，留 35% 余量吸收 jtokkit 对 qwen 的误差。 */
        private double summarizeThresholdRatio = 0.65;

        /** 摘要时保留最近多少条原文（不摘要）。兼顾近期精度与历史连续性。 */
        private int keepRecentMessages = 6;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getHistorySize() {
            return historySize;
        }

        public void setHistorySize(int historySize) {
            this.historySize = historySize;
        }

        public boolean isSummarize() {
            return summarize;
        }

        public void setSummarize(boolean summarize) {
            this.summarize = summarize;
        }

        public int getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }

        public double getSummarizeThresholdRatio() {
            return summarizeThresholdRatio;
        }

        public void setSummarizeThresholdRatio(double summarizeThresholdRatio) {
            this.summarizeThresholdRatio = summarizeThresholdRatio;
        }

        public int getKeepRecentMessages() {
            return keepRecentMessages;
        }

        public void setKeepRecentMessages(int keepRecentMessages) {
            this.keepRecentMessages = keepRecentMessages;
        }
    }
}
