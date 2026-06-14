package com.gcll.ticketagent.knowledge;

import java.util.List;

public interface KnowledgeSearchService {
    List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType);
}
