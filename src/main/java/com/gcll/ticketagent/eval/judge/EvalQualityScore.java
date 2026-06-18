package com.gcll.ticketagent.eval.judge;

/**
 * LLM-as-judge 裁判打分结果。score 为 1-5 整数，reason 为一句话说明。
 */
public record EvalQualityScore(int score, String reason) {
}
