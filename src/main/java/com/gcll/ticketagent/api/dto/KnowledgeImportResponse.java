package com.gcll.ticketagent.api.dto;

import java.util.List;

public record KnowledgeImportResponse(
        String sourceId,
        String title,
        int chunks,
        boolean vectorIndexed,
        List<KnowledgeDocumentDto> documents
) {
}
