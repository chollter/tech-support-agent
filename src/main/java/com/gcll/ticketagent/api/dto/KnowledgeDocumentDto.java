package com.gcll.ticketagent.api.dto;

import java.time.LocalDateTime;

public record KnowledgeDocumentDto(
        String id,
        String sourceType,
        String sourceId,
        String title,
        String summary,
        String systemName,
        String moduleName,
        String tags,
        LocalDateTime updatedAt
) {
}
