package com.gcll.ticketagent.ticket;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class TicketInputProcessor {

    static final String TRUNCATION_MARKER = "\n[TRUNCATED]";
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\n{3,}");

    private final int maxContentLength;
    private final List<String> sensitiveWords;

    public TicketInputProcessor(int maxContentLength) {
        this(maxContentLength, List.of());
    }

    public TicketInputProcessor(int maxContentLength, List<String> sensitiveWords) {
        this.maxContentLength = maxContentLength;
        this.sensitiveWords = sensitiveWords == null ? List.of() : List.copyOf(sensitiveWords);
    }

    public ProcessedInput process(String title, String content, Map<String, String> metadata) {
        String assembled = assemble(title, content, metadata);
        boolean truncated = assembled.length() > maxContentLength;
        String truncatedContent = truncate(assembled);
        String masked = maskSensitiveWords(truncatedContent);
        String normalized = normalizeWhitespace(masked);
        boolean sensitiveMasked = !masked.equals(truncatedContent);
        String summary = "truncated=" + truncated
                + ",sensitiveMasked=" + sensitiveMasked
                + ",length=" + normalized.length();
        return new ProcessedInput(normalized, summary);
    }

    /** @deprecated use {@link #process(String, String, Map)} */
    public String buildContent(String title, String content, Map<String, String> metadata) {
        return process(title, content, metadata).content();
    }

    private String assemble(String title, String content, Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim()).append("\n");
        }
        if (content != null) {
            sb.append(content.trim());
        }
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    sb.append("\n").append(key).append(": ").append(value);
                }
            });
        }
        return sb.toString().trim();
    }

    private String truncate(String value) {
        if (value.length() <= maxContentLength) {
            return value;
        }
        return value.substring(0, maxContentLength) + TRUNCATION_MARKER;
    }

    private String maskSensitiveWords(String value) {
        if (sensitiveWords.isEmpty() || value.isBlank()) {
            return value;
        }
        String masked = value;
        for (String word : sensitiveWords) {
            if (word == null || word.isBlank()) {
                continue;
            }
            masked = masked.replaceAll("(?i)" + Pattern.quote(word), "***");
        }
        return masked;
    }

    private String normalizeWhitespace(String value) {
        if (value.isBlank()) {
            return value;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return MULTI_BLANK_LINES.matcher(normalized).replaceAll("\n\n");
    }
}
