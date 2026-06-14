package com.gcll.ticketagent.governance.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyEngineTest {

    private final RoutingPolicyEngine engine = new RoutingPolicyEngine();

    @Test
    void emptySuggestionKeepsRuleResult() {
        RoutingResult rule = new RoutingResult(
                "支付研发组",
                List.of("订单研发组"),
                "规则路由",
                0.92
        );

        RoutingResult merged = engine.merge(rule, RoutingSuggestion.empty());

        assertThat(merged).isEqualTo(rule);
    }

    @Test
    void conflictingSuggestionKeepsRulePrimaryTeam() {
        RoutingResult rule = new RoutingResult(
                "支付研发组",
                List.of("订单研发组"),
                "支付系统归属支付研发组",
                0.92
        );
        RoutingSuggestion suggestion = new RoutingSuggestion(
                "订单研发组",
                List.of("DBA"),
                "LLM 认为应转订单组",
                0.8,
                true
        );

        RoutingResult merged = engine.merge(rule, suggestion);

        assertThat(merged.primaryTeam()).isEqualTo("支付研发组");
        assertThat(merged.routingReason()).contains("规则裁决");
        assertThat(merged.confidence()).isEqualTo(0.92);
    }

    @Test
    void agreeingSuggestionBoostsConfidenceAndAppendsReason() {
        RoutingResult rule = new RoutingResult(
                "支付研发组",
                List.of("订单研发组"),
                "支付系统归属支付研发组",
                0.7
        );
        RoutingSuggestion suggestion = new RoutingSuggestion(
                "支付研发组",
                List.of(),
                "支付回调接口异常",
                0.9,
                true
        );

        RoutingResult merged = engine.merge(rule, suggestion);

        assertThat(merged.primaryTeam()).isEqualTo("支付研发组");
        assertThat(merged.routingReason()).contains("支付回调接口异常");
        assertThat(merged.confidence()).isEqualTo(0.8);
    }

    @Test
    void fillsBackupTeamsFromSuggestionWhenRuleHasNone() {
        RoutingResult rule = new RoutingResult(
                "平台运维组",
                List.of(),
                "默认路由",
                0.4
        );
        RoutingSuggestion suggestion = new RoutingSuggestion(
                "平台运维组",
                List.of("研发值班组"),
                "历史案例协同",
                0.6,
                true
        );

        RoutingResult merged = engine.merge(rule, suggestion);

        assertThat(merged.backupTeams()).containsExactly("研发值班组");
    }
}
