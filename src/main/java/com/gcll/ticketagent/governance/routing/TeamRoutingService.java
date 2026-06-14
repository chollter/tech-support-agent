package com.gcll.ticketagent.governance.routing;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TeamRoutingService {

    public RoutingResult route(TicketExtractResult extract, List<KnowledgeHit> hits) {
        String system = extract.affectedSystem();
        String module = extract.affectedModule();

        if (system != null && system.contains("支付")) {
            return new RoutingResult(
                    "支付研发组",
                    List.of("订单研发组", "中间件运维组"),
                    "支付系统/" + module + " 归属支付研发组，订单与中间件为协同团队",
                    0.92
            );
        }
        if (system != null && system.contains("订单")) {
            return new RoutingResult(
                    "订单研发组",
                    List.of("DBA", "中间件运维组"),
                    "订单系统/" + module + " 归属订单研发组，数据库与中间件团队协同",
                    0.88
            );
        }
        if (system != null && system.contains("账号")) {
            return new RoutingResult(
                    "账号平台组",
                    List.of("安全合规组"),
                    "账号系统/" + module + " 归属账号平台组",
                    0.86
            );
        }
        if (system != null && system.contains("结算")) {
            return new RoutingResult(
                    "结算研发组",
                    List.of("财务系统组", "DBA"),
                    "结算系统涉及资金一致性，结算研发组主责",
                    0.9
            );
        }
        if (system != null && system.contains("基础平台")) {
            return new RoutingResult(
                    "基础平台组",
                    List.of("业务研发值班组"),
                    "基础平台组件异常由平台组主责",
                    0.82
            );
        }
        if (system != null && system.contains("中间件")) {
            return new RoutingResult(
                    "中间件运维组",
                    List.of("业务研发值班组"),
                    "消息队列/中间件异常由中间件运维组主责",
                    0.84
            );
        }
        if (hits != null && !hits.isEmpty()) {
            return new RoutingResult(
                    "平台运维组",
                    List.of("研发值班组"),
                    "基于历史案例归属推荐",
                    0.65
            );
        }
        return new RoutingResult(
                "平台运维组",
                List.of(),
                "未匹配到明确路由规则，默认平台运维组",
                0.4
        );
    }
}
