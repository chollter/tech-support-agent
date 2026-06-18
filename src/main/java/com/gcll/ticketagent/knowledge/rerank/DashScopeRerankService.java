package com.gcll.ticketagent.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope rerank 适配器：调用阿里云百炼 {@code gte-rerank-v2} 文本重排序 API。
 * <p>请求：{@code POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/}
 * body 为 {@code {model, input:{query, documents}, parameters:{return_top}}}。
 * 响应：{@code {output:{results:[{index, relevance_score}]}}}。
 * <p>经 {@link ExternalCallGateway} 治理（{@code rerank.rerank-default} 策略：重试/超时/熔断），
 * 失败或熔断降级返回原始候选顺序（不丢召回结果）。
 * <p>{@code @ConditionalOnProperty(opsmind.rag.rerank.enabled=true)} 时激活并覆盖 {@link NoopRerankService}。
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "opsmind.rag.rerank", name = "enabled", havingValue = "true")
public class DashScopeRerankService implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankService.class);
    private static final String DEFAULT_MODEL = "gte-rerank-v2";
    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/";

    private final ExternalCallGateway externalCallGateway;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public DashScopeRerankService(
            ExternalCallGateway externalCallGateway,
            ObjectMapper objectMapper,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${opsmind.rag.rerank.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${opsmind.rag.rerank.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint
    ) {
        this.externalCallGateway = externalCallGateway;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(endpoint).build();
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public List<RerankEntry> rerank(String query, List<RerankEntry> candidates, int topN) {
        if (candidates.size() <= topN) {
            // 候选数不足以触发 rerank 的收益，直接返回
            return candidates;
        }
        List<String> documents = candidates.stream().map(RerankEntry::text).toList();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", Map.of("query", query, "documents", documents));
        requestBody.put("parameters", Map.of("return_top", topN));

        CallResult<RerankResponse> result = externalCallGateway.execute(
                "rerank.rerank-default",
                () -> restClient.post()
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(RerankResponse.class)
        );
        if (!result.success() || result.value() == null) {
            log.warn("Rerank degraded, query='{}', reason={}", truncate(query),
                    result.circuitOpen() ? "circuit open" : "call failed");
            return candidates.subList(0, topN);
        }
        List<RerankResult> rerankResults = result.value().output() == null
                ? List.of() : result.value().output().results();
        if (rerankResults.isEmpty()) {
            return candidates.subList(0, topN);
        }
        // 按 rerank 返回的 index 回填原始候选（index 指向输入 documents 的位置）
        List<RerankEntry> reranked = new ArrayList<>(rerankResults.size());
        for (RerankResult r : rerankResults) {
            if (r.index() >= 0 && r.index() < candidates.size()) {
                reranked.add(candidates.get(r.index()));
            }
        }
        return reranked;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResponse(Output output) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(List<RerankResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResult(
            int index,
            @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore) {
    }
}
