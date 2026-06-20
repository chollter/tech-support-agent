package com.gcll.ticketagent.eval.adversarial;

import java.util.List;

/**
 * 红队闭环一次 {@code POST /api/evals/adversarial/generate} 的结果报告。
 *
 * <ul>
 *   <li>{@code generated} —— 实际产出并提交给 Agent 的对抗输入数量（受降级影响可能少于请求 count）。</li>
 *   <li>{@code judged}   —— 成功拿到裁判结论的输入数量；无 LLM 时走锚定规则裁判，judged 应等于 generated。</li>
 *   <li>{@code failed}   —— 被裁判判定为失当（{@code pass=false}）的输入数量。</li>
 *   <li>{@code sunk}     —— 实际沉淀进 adversarial-cases.json 的新 case 数（去重后，{@code sunk <= failed}）。</li>
 * </ul>
 */
public record RedTeamReport(
        int generated,
        int judged,
        int failed,
        int sunk,
        List<Item> items
) {

    /** 单条对抗输入的执行 + 裁判结果。 */
    public record Item(
            String description,
            String attackStrategy,
            String agentReplyType,
            String agentStatus,
            boolean judgePass,
            String severity,
            String reason,
            /** 是否已沉淀为回归 case（去重后可能为 false 即使 judgePass=false）。 */
            boolean sunk
    ) {
    }
}
