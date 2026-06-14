package com.gcll.ticketagent.governance.priority;

import com.gcll.ticketagent.extract.TicketExtractResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PriorityEvaluationService {

    public PriorityResult evaluate(TicketExtractResult extract) {
        List<String> reasonCodes = new ArrayList<>();
        boolean production = "生产".equals(extract.environment());
        boolean corePayment = hasSignal(extract.severitySignals(), "CORE_PAYMENT");
        boolean multiUser = hasImpact(extract.impactScope(), "多个用户", "部分用户");
        boolean allUsers = hasImpact(extract.impactScope(), "所有用户", "全量");
        boolean financial = hasSignal(extract.severitySignals(), "FINANCIAL_INCONSISTENCY");
        boolean testEnv = "测试".equals(extract.environment()) || "开发".equals(extract.environment());
        boolean singleUser = hasImpact(extract.impactScope(), "单个用户", "单用户");
        boolean resourceExhausted = hasSignal(extract.severitySignals(), "RESOURCE_EXHAUSTED");
        boolean dependencyTimeout = hasSignal(extract.severitySignals(), "DEPENDENCY_TIMEOUT");

        if (production && corePayment && allUsers) {
            reasonCodes.add("PROD_CORE_ALL_USERS");
            return new PriorityResult(
                    TicketPriority.P0,
                    reasonCodes,
                    "生产环境核心交易链路全量不可用",
                    true,
                    true
            );
        }
        if (production && corePayment && multiUser) {
            reasonCodes.add("PROD_CORE_MULTI_USER");
            return new PriorityResult(
                    TicketPriority.P1,
                    reasonCodes,
                    "生产环境核心交易链路，多用户受影响",
                    true,
                    true
            );
        }
        if (financial && production) {
            reasonCodes.add("FINANCIAL_INCONSISTENCY_PROD");
            return new PriorityResult(
                    TicketPriority.P1,
                    reasonCodes,
                    "生产环境涉及资金/订单状态不一致",
                    true,
                    true
            );
        }
        if (production && multiUser && (resourceExhausted || dependencyTimeout)) {
            reasonCodes.add(resourceExhausted ? "PROD_RESOURCE_EXHAUSTED_MULTI_USER" : "PROD_DEPENDENCY_TIMEOUT_MULTI_USER");
            return new PriorityResult(
                    TicketPriority.P1,
                    reasonCodes,
                    "生产环境资源耗尽/依赖超时，多用户受影响",
                    true,
                    true
            );
        }
        if (testEnv && singleUser) {
            reasonCodes.add("TEST_SINGLE_USER");
            return new PriorityResult(
                    TicketPriority.P3,
                    reasonCodes,
                    "测试环境单用户问题，影响范围有限",
                    false,
                    false
            );
        }
        reasonCodes.add("DEFAULT_P2");
        return new PriorityResult(
                TicketPriority.P2,
                reasonCodes,
                "一般优先级工单",
                false,
                false
        );
    }

    private boolean hasSignal(List<String> signals, String signal) {
        return signals != null && signals.contains(signal);
    }

    private boolean hasImpact(String impactScope, String... keywords) {
        if (impactScope == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (impactScope.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
