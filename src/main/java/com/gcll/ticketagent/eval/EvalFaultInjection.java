package com.gcll.ticketagent.eval;

public final class EvalFaultInjection {

    private static final String PREFIX = "[inject-failure:";
    private static final String KNOWLEDGE_SEARCH = "knowledge-search";

    private EvalFaultInjection() {
    }

    public static boolean shouldFail(String originalContent, String toolName) {
        if (originalContent == null || toolName == null) {
            return false;
        }
        String normalized = originalContent.toLowerCase();
        String marker = PREFIX + toolName.toLowerCase() + "]";
        return normalized.contains(marker);
    }

    public static String sanitize(String originalContent) {
        if (originalContent == null || originalContent.isBlank()) {
            return originalContent;
        }
        return originalContent.replaceAll("(?i)\\[inject-failure:[a-z_]+\\]\\s*", "").trim();
    }

    public static boolean shouldFailKnowledgeSearch(String originalContent) {
        return shouldFail(originalContent, KNOWLEDGE_SEARCH);
    }
}
