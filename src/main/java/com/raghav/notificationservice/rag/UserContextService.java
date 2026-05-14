package com.raghav.notificationservice.rag;

import com.raghav.notificationservice.dto.UserContextRequest;
import com.raghav.notificationservice.dto.UserContextResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/**
 * Handles indexing of user behaviour events into Pinecone.
 * Uses GeminiService to generate embeddings before upserting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserContextService {

    private final GeminiService geminiService;
    private final PineconeService pineconeService;

    // ─────────────────────────────────────────────────────────
    // Single upsert
    // ─────────────────────────────────────────────────────────

    public UserContextResponse upsert(UserContextRequest request) {
        log.info("[USER CONTEXT] Upserting context for userId={}", request.getUserId());

        try {
            // Generate embedding via Gemini
            List<Float> vector = geminiService.generateEmbedding(request.getText());

            // Resolve vector ID — use caller-supplied or generate deterministic one
            String vectorId = request.getVectorId() != null
                    ? request.getVectorId()
                    : generateDeterministicId(request.getUserId(), request.getText());

            // Upsert to Pinecone
            pineconeService.upsertVector(
                    request.getUserId(),
                    vectorId,
                    vector,
                    request.getMetadata()
            );

            log.info("[USER CONTEXT] Upserted successfully vectorId={}", vectorId);

            return UserContextResponse.builder()
                    .userId(request.getUserId())
                    .vectorId(vectorId)
                    .status("upserted")
                    .dimension(vector.size())
                    .message("Context indexed successfully")
                    .build();

        } catch (Exception e) {
            log.error("[USER CONTEXT] Upsert failed for userId={}: {}", request.getUserId(), e.getMessage());

            return UserContextResponse.builder()
                    .userId(request.getUserId())
                    .status("failed")
                    .dimension(0)
                    .message("Upsert failed: " + e.getMessage())
                    .build();
        }
    }

    // ─────────────────────────────────────────────────────────
    // Batch upsert
    // ─────────────────────────────────────────────────────────

    public List<UserContextResponse> upsertBatch(List<UserContextRequest> requests) {
        log.info("[USER CONTEXT] Batch upsert for {} items", requests.size());

        List<UserContextResponse> responses = new ArrayList<>();
        for (UserContextRequest request : requests) {
            responses.add(upsert(request));
        }
        return responses;
    }

    // ─────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────

    public void deleteUserContext(String userId) {
        log.info("[USER CONTEXT] Deleting all context for userId={}", userId);
        pineconeService.deleteNamespace(userId);
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Generates a deterministic vectorId from userId + text using SHA-256.
     * Same input always produces the same ID — prevents duplicate vectors
     * if the same event is indexed twice.
     */
    private String generateDeterministicId(String userId, String text) {
        try {
            String combined = userId + ":" + text;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return userId + ":" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return userId + ":" + System.currentTimeMillis();
        }
    }
}