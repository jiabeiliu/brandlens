package com.jiabeiliu.brandlens.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeddings via an OpenAI-compatible {@code POST /v1/embeddings} endpoint.
 *
 * <p><b>Ollama-first (default, free, local):</b> point {@code llm.base-url} at
 * {@code http://localhost:11434/v1} and use {@code nomic-embed-text} — no API
 * key needed.
 *
 * <p>Inputs are sent in batches of 100. The returned vector length is
 * validated against {@code llm.embedding-dimensions} so a model/dimension
 * mismatch fails fast with a clear message.
 */
@Service
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private static final int BATCH_SIZE = 100;

    private final RestClient restClient;
    private final String model;
    private final int expectedDimensions;

    public OpenAiCompatibleEmbeddingService(
            RestClient.Builder builder,
            @Value("${llm.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.embedding-model:nomic-embed-text}") String model,
            @Value("${llm.embedding-dimensions:768}") int expectedDimensions) {
        this.model = model;
        this.expectedDimensions = expectedDimensions;
        RestClient.Builder b = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (apiKey != null && !apiKey.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.restClient = b.build();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            out.addAll(embedOneBatch(batch));
        }
        return out;
    }

    private List<float[]> embedOneBatch(List<String> batch) {
        Map<String, Object> body = Map.of("model", model, "input", batch);
        JsonNode root = restClient.post()
                .uri("/embeddings")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // Response shape: {"data": [{"embedding": [...], "index": 0}, ...]}
        // Order by "index" so results line up with the input order.
        List<float[]> ordered = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            ordered.add(null);
        }
        for (JsonNode item : root.path("data")) {
            int idx = item.path("index").asInt();
            JsonNode vec = item.path("embedding");
            if (vec.size() != expectedDimensions) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch: model '" + model + "' returned "
                                + vec.size() + " dimensions but llm.embedding-dimensions="
                                + expectedDimensions + " (must match vector(N) in schema.sql)");
            }
            float[] arr = new float[vec.size()];
            for (int j = 0; j < vec.size(); j++) {
                arr[j] = (float) vec.get(j).asDouble();
            }
            ordered.set(idx, arr);
        }
        return ordered;
    }
}
