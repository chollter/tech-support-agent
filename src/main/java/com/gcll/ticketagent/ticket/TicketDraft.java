package com.gcll.ticketagent.ticket;

public class TicketDraft {
    private final StringBuilder content = new StringBuilder();

    public TicketDraft(String initialContent) {
        if (initialContent != null && !initialContent.isBlank()) {
            content.append(initialContent.trim());
        }
    }

    public void append(String additionalContent) {
        if (additionalContent == null || additionalContent.isBlank()) {
            return;
        }
        if (!content.isEmpty()) {
            content.append("\n");
        }
        content.append(additionalContent.trim());
    }

    public String fullContent() {
        return content.toString();
    }
}
