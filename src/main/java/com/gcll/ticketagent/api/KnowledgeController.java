package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.KnowledgeDocumentDto;
import com.gcll.ticketagent.api.dto.KnowledgeImportResponse;
import com.gcll.ticketagent.knowledge.KnowledgeIngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService knowledgeIngestionService;

    public KnowledgeController(KnowledgeIngestionService knowledgeIngestionService) {
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeImportResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String systemName,
            @RequestParam(required = false) String moduleName,
            @RequestParam(required = false) String tags
    ) throws IOException {
        return knowledgeIngestionService.importText(
                file.getOriginalFilename(),
                file.getBytes(),
                title,
                systemName,
                moduleName,
                tags
        );
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentDto> recent(@RequestParam(defaultValue = "20") int limit) {
        return knowledgeIngestionService.recent(limit);
    }
}
