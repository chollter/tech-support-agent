package com.gcll.ticketagent.understanding.followup;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateFollowUpProvider {

    private static final Map<String, String> QUESTION_TEMPLATES = new LinkedHashMap<>();

    static {
        QUESTION_TEMPLATES.put("systemOrModule", "具体是哪个系统或业务模块的接口？");
        QUESTION_TEMPLATES.put("apiOrFeature", "接口名、URL 或调用链路是什么？");
        QUESTION_TEMPLATES.put("environment", "报错发生在生产环境还是测试环境？");
        QUESTION_TEMPLATES.put("errorDetail", "请提供错误码、错误信息或日志片段。");
        QUESTION_TEMPLATES.put("timeRange", "大概是什么时间发生的？");
        QUESTION_TEMPLATES.put("impactScope", "影响范围是单个用户、部分用户，还是所有用户？");
    }

    public List<String> questionsForMissingFields(List<String> missingFields) {
        List<String> questions = new ArrayList<>();
        if (missingFields == null) {
            return questions;
        }
        for (String field : missingFields) {
            String question = QUESTION_TEMPLATES.get(field);
            if (question != null) {
                questions.add(question);
            }
        }
        return questions;
    }
}
