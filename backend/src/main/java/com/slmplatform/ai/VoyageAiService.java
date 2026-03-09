package com.slmplatform.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.slmplatform.logging.ExternalApiLoggingService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.Duration;

@Service
public class VoyageAiService {

    private final RestClient restClient;
    private final String model;
    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final double relevanceThreshold;
    private final int topK;
    private final ExternalApiLoggingService externalApiLoggingService;

    public VoyageAiService(
            @Value("${voyageai.api-key}") String apiKey,
            @Value("${voyageai.model}") String model,
            @Value("${voyageai.relevance-threshold:0.5}") double relevanceThreshold,
            @Value("${voyageai.top-k:3}") int topK,
            @Value("${platform.api.timeout-connect:300000}") long connectTimeout,
            @Value("${platform.api.timeout-read:300000}") long readTimeout,
            ObjectMapper objectMapper,
            ExternalApiLoggingService externalApiLoggingService) {
        this.model = model;
        this.enabled = apiKey != null && !apiKey.equals("default") && !apiKey.isEmpty();
        this.relevanceThreshold = relevanceThreshold;
        this.topK = topK;
        this.objectMapper = objectMapper;
        this.externalApiLoggingService = externalApiLoggingService;

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(readTimeout));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl("https://api.voyageai.com/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public List<Double> getEmbedding(String text, String sessionId) {
        if (!enabled)
            return Collections.emptyList();
        long startTime = System.currentTimeMillis();
        String rawResponse = null;
        int statusCode = 200;
        String errorDetails = null;
        String requestBody = "Text: " + text;

        try {
            Map<String, Object> payload = Map.of("input", List.of(text), "model", model);
            try {
                requestBody = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                // Ignore serialization error for logging
            }

            rawResponse = restClient.post().uri("/embeddings").body(payload).retrieve().body(String.class);

            VoyageResponse response = objectMapper.readValue(rawResponse, VoyageResponse.class);
            return response != null && response.data() != null && !response.data().isEmpty()
                    ? response.data().get(0).embedding()
                    : Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Embedding failed: " + e.getMessage());
            statusCode = 500;
            errorDetails = e.getMessage();
            return Collections.emptyList();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            externalApiLoggingService.logApiCall(
                    "VoyageAI", "/embeddings", requestBody, rawResponse, statusCode, duration,
                    errorDetails, sessionId);
        }
    }

    public List<List<Double>> getEmbeddings(List<String> texts, String sessionId) {
        if (!enabled || texts.isEmpty())
            return Collections.emptyList();
        long startTime = System.currentTimeMillis();
        String rawResponse = null;
        int statusCode = 200;
        String errorDetails = null;
        String requestBody = "Text batch: " + texts.size();

        try {
            Map<String, Object> payload = Map.of("input", texts, "model", model);
            try {
                requestBody = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
            }

            rawResponse = restClient.post().uri("/embeddings").body(payload).retrieve().body(String.class);

            VoyageResponse response = objectMapper.readValue(rawResponse, VoyageResponse.class);
            if (response != null && response.data() != null) {
                return response.data().stream()
                        .sorted(java.util.Comparator.comparingInt(VoyageData::index))
                        .map(VoyageData::embedding)
                        .toList();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Embedding batch failed: " + e.getMessage());
            statusCode = 500;
            errorDetails = e.getMessage();
            return Collections.emptyList();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            externalApiLoggingService.logApiCall(
                    "VoyageAI", "/embeddings (batch)", requestBody, rawResponse, statusCode, duration,
                    errorDetails, sessionId);
        }
    }

    public List<String> getTopRerankedDocuments(String query, List<String> documents, String rerankerModel,
            String sessionId) {
        if (!enabled && !documents.isEmpty())
            return documents.stream().limit(topK).toList();
        if (documents.isEmpty())
            return Collections.emptyList();

        long startTime = System.currentTimeMillis();
        String rawResponse = null;
        int statusCode = 200;
        String errorDetails = null;
        String requestBody = "Query: " + query + ", Docs: " + documents.size();

        try {
            Map<String, Object> payload = Map.of(
                    "query", query,
                    "documents", documents,
                    "model", rerankerModel,
                    "top_k", topK);
            try {
                requestBody = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
            }

            rawResponse = restClient.post().uri("/rerank").body(payload).retrieve().body(String.class);
            VoyageRerankResponse response = objectMapper.readValue(rawResponse, VoyageRerankResponse.class);

            if (response != null && response.data() != null && !response.data().isEmpty()) {
                return response.data().stream()
                        .filter(match -> match.relevance_score() >= relevanceThreshold)
                        .map(match -> documents.get(match.index()))
                        .toList();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Reranking failed: " + e.getMessage());
            statusCode = 500;
            errorDetails = e.getMessage();
            return Collections.emptyList();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            externalApiLoggingService.logApiCall(
                    "VoyageAI", "/rerank", requestBody, rawResponse, statusCode,
                    duration, errorDetails, sessionId);
        }
    }

    public List<Integer> getTopRerankedDocumentIndices(String query, List<String> documents, String rerankerModel,
            String sessionId) {
        if (!enabled && !documents.isEmpty()) {
            return java.util.stream.IntStream.range(0, Math.min(documents.size(), topK))
                    .boxed()
                    .toList();
        }
        if (documents.isEmpty())
            return Collections.emptyList();

        long startTime = System.currentTimeMillis();
        String rawResponse = null;
        int statusCode = 200;
        String errorDetails = null;
        String requestBody = "Query: " + query + ", Docs: " + documents.size();

        try {
            Map<String, Object> payload = Map.of(
                    "query", query,
                    "documents", documents,
                    "model", rerankerModel,
                    "top_k", topK);
            try {
                requestBody = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
            }

            rawResponse = restClient.post().uri("/rerank").body(payload).retrieve().body(String.class);
            VoyageRerankResponse response = objectMapper.readValue(rawResponse, VoyageRerankResponse.class);

            if (response != null && response.data() != null && !response.data().isEmpty()) {
                return response.data().stream()
                        .filter(match -> match.relevance_score() >= relevanceThreshold)
                        .map(match -> match.index())
                        .toList();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Reranking failed: " + e.getMessage());
            statusCode = 500;
            errorDetails = e.getMessage();
            return Collections.emptyList();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            externalApiLoggingService.logApiCall(
                    "VoyageAI", "/rerank", requestBody, rawResponse, statusCode,
                    duration, errorDetails, sessionId);
        }
    }
}

// Add IgnoreProperties to prevent mapping errors from unexpected API response
// fields
@JsonIgnoreProperties(ignoreUnknown = true)
record VoyageResponse(List<VoyageData> data) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record VoyageData(int index, List<Double> embedding) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record VoyageRerankResponse(List<VoyageRerankData> data) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record VoyageRerankData(int index, double relevance_score) {
}