package com.gcll.ticketagent.understanding.completeness;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SchemaCompletenessChecker {

    public SchemaCompletenessResult check(TicketExtractResult extract) {
        if (extract.issueType() != IssueType.INCIDENT) {
            return new SchemaCompletenessResult(true, List.of());
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(extract.affectedSystem()) && isBlank(extract.affectedModule())) {
            missing.add("systemOrModule");
        }
        if (isBlank(extract.environment())) {
            missing.add("environment");
        }
        if (isBlank(extract.apiName())) {
            missing.add("apiOrFeature");
        }
        if (isBlank(extract.errorCode()) && isBlank(extract.errorMessage())) {
            missing.add("errorDetail");
        }
        if (isBlank(extract.timeRange())) {
            missing.add("timeRange");
        }
        if (isBlank(extract.impactScope())) {
            missing.add("impactScope");
        }
        return new SchemaCompletenessResult(missing.isEmpty(), missing);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
