package com.gcll.ticketagent.understanding.gap;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.understanding.completeness.SchemaCompletenessChecker;
import com.gcll.ticketagent.understanding.completeness.SchemaCompletenessResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RuleBasedInfoGapAnalysisService implements InfoGapAnalysisService {

    private final SchemaCompletenessChecker schemaCompletenessChecker;

    public RuleBasedInfoGapAnalysisService(SchemaCompletenessChecker schemaCompletenessChecker) {
        this.schemaCompletenessChecker = schemaCompletenessChecker;
    }

    @Override
    public StepOutcome<InfoGapAnalysis> analyze(String userContent, TicketExtractResult extract) {
        String text = userContent == null ? "" : userContent;
        String lower = text.toLowerCase(Locale.ROOT);

        SchemaCompletenessResult schema = schemaCompletenessChecker.check(extract);
        List<String> schemaMissing = schema.complete() ? List.of() : List.copyOf(schema.missingFields());
        List<String> semanticGaps = new ArrayList<>();
        List<String> suggestedQuestions = new ArrayList<>();

        if (extract.issueType() == IssueType.INCIDENT) {
            detectIncidentSemanticGaps(text, lower, extract, semanticGaps, suggestedQuestions);
        } else if (containsOperationalIncidentSignals(lower)) {
            detectIncidentSemanticGaps(text, lower, extract, semanticGaps, suggestedQuestions);
        } else if (isVagueDescription(text, extract)) {
            semanticGaps.add("描述过于简略，无法判断处理路径");
            suggestedQuestions.add("请补充具体系统、现象和影响范围，方便准确分派。");
        }

        boolean readyForAnalysis = schema.complete() && semanticGaps.isEmpty();
        if (extract.issueType() == IssueType.INCIDENT) {
            readyForAnalysis = readyForAnalysis && extract.confidence() >= 0.55;
        }
        String blockingReason = readyForAnalysis
                ? "信息足够进入分析"
                : buildBlockingReason(schemaMissing, semanticGaps);

        InfoGapAnalysis analysis = new InfoGapAnalysis(
                schemaMissing,
                List.copyOf(semanticGaps),
                List.copyOf(suggestedQuestions),
                blockingReason,
                readyForAnalysis,
                extract.confidence(),
                false
        );
        return StepOutcome.ruleBased(analysis);
    }

    private void detectIncidentSemanticGaps(
            String text,
            String lower,
            TicketExtractResult extract,
            List<String> semanticGaps,
            List<String> suggestedQuestions
    ) {
        if (containsAny(lower, "偶尔", "有时", "偶发", "间歇", "不稳定")
                && !containsAny(lower, "必现", "每次", "一直")
                && needsReproducibilityContext(extract, lower)) {
            semanticGaps.add("未说明问题是必现还是偶发，以及出现频率");
            suggestedQuestions.add("问题是必现还是偶发？若偶发，大概多久出现一次？");
        }
        if (containsAny(lower, "批处理", "定时任务", "job")
                && isBlank(extract.affectedModule())
                && !containsAny(lower, "job", "任务名", "任务名称")) {
            semanticGaps.add("未说明批处理 Job 或任务名称");
            suggestedQuestions.add("具体是哪个批处理 Job 或任务名称？");
        }
        if (containsAny(lower, "结算", "批处理") && containsAny(lower, "跑不完", "失败", "超时")) {
            semanticGaps.add("未说明是否影响当日结算窗口或资金处理");
            suggestedQuestions.add("是否影响当日结算截止窗口或资金入账？");
        }
        if (containsAny(lower, "接口") && containsAny(lower, "慢", "卡顿", "延迟")
                && !containsAny(lower, "查询", "下单", "支付", "写入", "读取", "读", "写")) {
            semanticGaps.add("未说明是读接口还是写接口/交易接口");
            suggestedQuestions.add("影响的是查询类接口还是下单/支付等写接口？");
        }
        if (containsAny(lower, "报错", "异常", "失败") && text.length() < 12 && extract.confidence() < 0.4) {
            semanticGaps.add("故障描述过于笼统");
            suggestedQuestions.add("除了报错外，能否补充具体系统、接口和错误信息？");
        }
    }

    private boolean needsReproducibilityContext(TicketExtractResult extract, String lower) {
        if (containsAny(lower, "批处理", "结算", "慢", "卡顿", "延迟", "超时")) {
            return true;
        }
        return extract.apiName() == null || extract.apiName().isBlank();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsOperationalIncidentSignals(String lower) {
        return containsAny(lower, "批处理", "跑不完", "超时", "失败", "oom", "500", "报错", "异常", "积压", "慢", "不可用");
    }

    private boolean isVagueDescription(String text, TicketExtractResult extract) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return text.length() < 8 && extract.confidence() < 0.35;
    }

    private String buildBlockingReason(List<String> schemaMissing, List<String> semanticGaps) {
        if (!schemaMissing.isEmpty() && !semanticGaps.isEmpty()) {
            return "必填字段不足且存在语义信息缺口";
        }
        if (!schemaMissing.isEmpty()) {
            return "必填字段不足";
        }
        if (!semanticGaps.isEmpty()) {
            return "存在语义信息缺口";
        }
        return "抽取置信度不足";
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
