package com.gcll.ticketagent.governance.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@Primary
public class SpringAiRoutingSuggestionService implements RoutingSuggestionService {

    private static final Set<String> KNOWN_TEAMS = Set.of(
            "支付研发组", "订单研发组", "账号平台组", "结算研发组",
            "基础平台组", "中间件运维组", "平台运维组", "DBA",
            "安全合规组", "财务系统组", "业务研发值班组", "研发值班组"
    );

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final RuleBasedRoutingSuggestionService fallback;

    public SpringAiRoutingSuggestionService(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            RuleBasedRoutingSuggestionService fallback
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<RoutingSuggestion> suggest(
            String userContent,
            TicketExtractResult extract,
            List<KnowledgeHit> hits,
            RoutingResult ruleBaseline
    ) {
        try {
            String input = buildInput(userContent, extract, hits, ruleBaseline);
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.routing-suggest", "routing-suggest.txt", input);
            if (!result.success()) {
                return fallback.suggest(userContent, extract, hits, ruleBaseline);
            }
            SuggestJson json = parser.parse(result.value().content(), SuggestJson.class);
            if (json.suggestedPrimaryTeam() == null || json.suggestedPrimaryTeam().isBlank()) {
                return fallback.suggest(userContent, extract, hits, ruleBaseline);
            }
            List<String> backupTeams = json.suggestedBackupTeams() == null ? List.of() : json.suggestedBackupTeams();
            RoutingSuggestion suggestion = new RoutingSuggestion(
                    json.suggestedPrimaryTeam(),
                    backupTeams,
                    json.reason() == null ? "LLM routing suggestion" : json.reason(),
                    json.confidence(),
                    true
            );
            return StepOutcome.llm(suggestion, result.durationMs());
        } catch (Exception ex) {
            return fallback.suggest(userContent, extract, hits, ruleBaseline);
        }
    }

    private String buildInput(
            String userContent,
            TicketExtractResult extract,
            List<KnowledgeHit> hits,
            RoutingResult ruleBaseline
    ) throws Exception {
        String hitsSummary = hits == null || hits.isEmpty()
                ? "无知识库命中"
                : hits.stream()
                .map(hit -> hit.title() + " (" + hit.matchedReason() + ")")
                .reduce((a, b) -> a + "; " + b)
                .orElse("无");
        return """
                规则基线路由（不可被覆盖，仅供参考对比）：
                primaryTeam=%s
                backupTeams=%s
                reason=%s
                confidence=%s

                已知团队白名单：
                %s

                抽取结果 JSON：
                %s

                知识库命中摘要：
                %s

                用户原文：
                %s
                """.formatted(
                ruleBaseline.primaryTeam(),
                ruleBaseline.backupTeams(),
                ruleBaseline.routingReason(),
                ruleBaseline.confidence(),
                KNOWN_TEAMS,
                objectMapper.writeValueAsString(extract),
                hitsSummary,
                userContent
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SuggestJson(
            String suggestedPrimaryTeam,
            List<String> suggestedBackupTeams,
            String reason,
            double confidence
    ) {
    }
}
