package com.raghav.notificationservice.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PineconeService handles all vector store operations.
 * Uses GeminiService for generating embeddings (768 dimensions).
 *
 * Operations:
 * - retrieveUserContext: query vectors by userId namespace
 * - upsertVector: store a vector with metadata
 * - deleteNamespace: GDPR deletion of all user vectors
 */
@Service
@Slf4j
public class PineconeService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GeminiService geminiService;

    @Value("${pinecone.api.key}")
    private String pineconeApiKey;

    @Value("${pinecone.top-k:3}")
    private int topK;

    // Score threshold — only use matches with similarity >= 0.70
    private static final float SCORE_THRESHOLD = 0.70f;

    public PineconeService(WebClient.Builder webClientBuilder,
                           ObjectMapper objectMapper,
                           GeminiService geminiService,
                           @Value("${pinecone.api.url}") String pineconeUrl) {
        this.webClient = webClientBuilder.baseUrl(pineconeUrl).build();
        this.objectMapper = objectMapper;
        this.geminiService = geminiService;
    }

    // ─────────────────────────────────────────────────────────
    // Query
    // ─────────────────────────────────────────────────────────

    /**
     * Retrieves relevant user context from Pinecone for a given subject.
     *
     * 1. Embeds the subject using Gemini (768 dims)
     * 2. Queries the user's namespace in Pinecone
     * 3. Filters matches with score >= 0.70
     * 4. Returns metadata as a context string
     */
    public String retrieveUserContext(String userId, String subject) {
        log.debug("[PINECONE] Retrieving context for userId={}, subject='{}'", userId, subject);

        try {
            // Generate embedding for the subject
            List<Float> queryVector = geminiService.generateEmbedding(subject);

            // Build Pinecone query request
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("namespace", userId);
            requestBody.put("topK", topK);
            requestBody.put("includeMetadata", true);

            ArrayNode vectorArray = objectMapper.createArrayNode();
            for (Float val : queryVector) {
                vectorArray.add(val);
            }
            requestBody.set("vector", vectorArray);

            // Call Pinecone query endpoint
            String responseJson = webClient.post()
                    .uri("/query")
                    .header("Api-Key", pineconeApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseContextFromResponse(responseJson);

        } catch (Exception e) {
            log.warn("[PINECONE] Failed to retrieve context for userId={}: {}", userId, e.getMessage());
            return "No user context available.";
        }
    }

    private String parseContextFromResponse(String responseJson) throws Exception {
        JsonNode responseNode = objectMapper.readTree(responseJson);
        JsonNode matches = responseNode.path("matches");

        if (matches.isMissingNode() || !matches.isArray() || matches.size() == 0) {
            return "No user context available.";
        }

        StringBuilder context = new StringBuilder();
        boolean hasRelevantMatch = false;

        for (JsonNode match : matches) {
            float score = (float) match.path("score").asDouble();

            // Only use matches above the relevance threshold
            if (score >= SCORE_THRESHOLD) {
                hasRelevantMatch = true;
                JsonNode metadata = match.path("metadata");
                metadata.fields().forEachRemaining(entry ->
                        context.append(entry.getKey())
                                .append(": ")
                                .append(entry.getValue().asText())
                                .append("\n")
                );
            }
        }

        if (!hasRelevantMatch) {
            return "No relevant user context found.";
        }

        return context.toString().trim();
    }

    // ─────────────────────────────────────────────────────────
    // Upsert
    // ─────────────────────────────────────────────────────────

    /**
     * Stores a vector with metadata in the user's Pinecone namespace.
     */
    public void upsertVector(String userId, String vectorId,
                             List<Float> vector, Map<String, String> metadata) {
        log.debug("[PINECONE] Upserting vector id={} for userId={}", vectorId, userId);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("namespace", userId);

            ArrayNode vectorsArray = objectMapper.createArrayNode();
            ObjectNode vectorNode = objectMapper.createObjectNode();
            vectorNode.put("id", vectorId);

            ArrayNode valuesArray = objectMapper.createArrayNode();
            for (Float val : vector) {
                valuesArray.add(val);
            }
            vectorNode.set("values", valuesArray);

            ObjectNode metadataNode = objectMapper.createObjectNode();
            metadata.forEach(metadataNode::put);
            vectorNode.set("metadata", metadataNode);

            vectorsArray.add(vectorNode);
            requestBody.set("vectors", vectorsArray);

            webClient.post()
                    .uri("/vectors/upsert")
                    .header("Api-Key", pineconeApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[PINECONE] Vector upserted successfully id={}", vectorId);

        } catch (Exception e) {
            log.error("[PINECONE] Upsert failed for vectorId={}: {}", vectorId, e.getMessage());
            throw new RuntimeException("Pinecone upsert failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────

    /**
     * Deletes all vectors for a user — used for GDPR compliance.
     */
    public void deleteNamespace(String userId) {
        log.info("[PINECONE] Deleting all vectors for userId={}", userId);

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("deleteAll", true);
            requestBody.put("namespace", userId);

            webClient.post()
                    .uri("/vectors/delete")
                    .header("Api-Key", pineconeApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[PINECONE] Namespace deleted for userId={}", userId);

        } catch (Exception e) {
            log.error("[PINECONE] Delete failed for userId={}: {}", userId, e.getMessage());
            throw new RuntimeException("Pinecone delete failed: " + e.getMessage(), e);
        }
    }
}