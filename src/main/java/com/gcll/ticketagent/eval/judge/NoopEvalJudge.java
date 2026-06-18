package com.gcll.ticketagent.eval.judge;

import com.gcll.ticketagent.eval.EvalCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认裁判实现：不可用，跳过质量评测。
 * <p>当 {@code opsmind.eval.judge.enabled=false}（默认）或未启用时激活（与 {@link DashScopeEvalJudge}
 * 的 {@code enabled=true} 条件互补）。{@link #available()} 返回 false，EvalRunner 据此跳过整个质量评测。
 * <p>用 {@code matchIfMissing=true} 确保未配置时也激活（默认关闭）。
 */
@Service
@ConditionalOnProperty(prefix = "opsmind.eval.judge", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopEvalJudge implements EvalJudgeService {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public EvalQualityScore score(EvalCase evalCase, String rootCauseText) {
        return null;
    }
}
