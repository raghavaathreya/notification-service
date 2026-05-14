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

/**
 * GeminiService replaces OpenAiService.
 *
 * Handles two things:
 * 1. generateEmbedding() - calls Gemini text-embedding-004 (768 dimensions, free)
 * 2. generateContent()   - calls Gemini 1.5 Flash for personalized text (free)
 *
 * Both APIs are completely free with a Google account.
 * Base URL: https://generativelanguage.googleapis.com/v1beta
 */
@Service
@Slf4j
public class GeminiService {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.embedding.model:text-embedding-004}")
    private String embeddingModel;

    @Value("${gemini.generation.model:gemini-1.5-flash}")
    private String generationModel;

    @Value("${gemini.generation.max-tokens:300}")
    private int maxTokens;

    public GeminiService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────
    // Embeddings
    // ─────────────────────────────────────────────────────────

    /**
     * Generates a 768-dimensional embedding vector for the given text
     * using Gemini's text-embedding-004 model.
     *
     * API: POST /models/text-embedding-004:embedContent?key={apiKey}
     */
    public List<Float> generateEmbedding(String text) {
        log.debug("[GEMINI] Generating embedding for text length={}", text.length());

        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode partsArray = objectMapper.createArrayNode();
            ObjectNode partNode = objectMapper.createObjectNode();

            partNode.put("text", text);
            partsArray.add(partNode);
            contentNode.set("parts", partsArray);
            requestBody.set("content", contentNode);

            // Call Gemini embeddings API
            String responseJson = webClient.post()
                    .uri("/models/" + embeddingModel + ":embedContent?key=" + geminiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // Parse response
            JsonNode responseNode = objectMapper.readTree(responseJson);
            JsonNode valuesNode = responseNode
                    .path("embedding")
                    .path("values");

            if (valuesNode.isMissingNode() || !valuesNode.isArray() || valuesNode.size() == 0) {
                throw new RuntimeException("Empty or missing embedding values in Gemini response");
            }

            List<Float> vector = new ArrayList<>();
            for (JsonNode val : valuesNode) {
                vector.add((float) val.asDouble());
            }

            log.debug("[GEMINI] Embedding generated successfully, dimensions={}", vector.size());
            return vector;

        } catch (Exception e) {
            log.error("[GEMINI] Embedding generation failed: {}", e.getMessage());
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Content Generation
    // ─────────────────────────────────────────────────────────

    /**
     * Generates personalized notification content using Gemini 1.5 Flash.
     *
     * API: POST /models/gemini-1.5-flash:generateContent?key={apiKey}
     */
    public String generateContent(String prompt) {
        log.debug("[GEMINI] Generating content for prompt length={}", prompt.length());

        try {
            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode contentsArray = objectMapper.createArrayNode();
            ObjectNode contentNode = objectMapper.createObjectNode();
            ArrayNode partsArray = objectMapper.createArrayNode();
            ObjectNode partNode = objectMapper.createObjectNode();

            partNode.put("text", prompt);
            partsArray.add(partNode);
            contentNode.set("parts", partsArray);
            contentsArray.add(contentNode);
            requestBody.set("contents", contentsArray);

            // Generation config
            ObjectNode generationConfig = objectMapper.createObjectNode();
            generationConfig.put("maxOutputTokens", maxTokens);
            generationConfig.put("temperature", 0.7);
            requestBody.set("generationConfig", generationConfig);

            // Call Gemini generation API
            String responseJson = webClient.post()
                    .uri("/models/" + generationModel + ":generateContent?key=" + geminiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // Parse response
            JsonNode responseNode = objectMapper.readTree(responseJson);
            String generatedText = responseNode
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText();

            if (generatedText == null || generatedText.isEmpty()) {
                throw new RuntimeException("Empty content in Gemini generation response");
            }

            log.debug("[GEMINI] Content generated successfully, length={}", generatedText.length());
            return generatedText.trim();

        } catch (Exception e) {
            log.error("[GEMINI] Content generation failed: {}", e.getMessage());
            throw new RuntimeException("Gemini generation failed: " + e.getMessage(), e);
        }
    }
}