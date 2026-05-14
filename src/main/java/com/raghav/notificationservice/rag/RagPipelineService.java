package com.raghav.notificationservice.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the RAG pipeline:
 * 1. Retrieve user context from Pinecone
 * 2. Build a prompt with context + original content
 * 3. Generate personalized content via Gemini
 *
 * Gracefully falls back to original content if any step fails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagPipelineService {

    private final PineconeService pineconeService;
    private final GeminiService geminiService;

    /**
     * Personalizes notification content for a specific user.
     *
     * @param userId          the user to personalize for
     * @param subject         notification subject (used as query for Pinecone)
     * @param originalContent the original notification content
     * @return personalized content, or originalContent if pipeline fails
     */
    public String personalize(String userId, String subject, String originalContent) {
        log.info("[RAG] Starting personalization for userId={}, subject='{}'", userId, subject);

        try {
            // Step 1: Retrieve relevant user context from Pinecone
            String userContext = pineconeService.retrieveUserContext(userId, subject);
            log.debug("[RAG] Retrieved context for userId={}: {}", userId, userContext);

            // Step 2: Build prompt
            String prompt = buildPrompt(subject, originalContent, userContext);

            // Step 3: Generate personalized content via Gemini
            String personalizedContent = geminiService.generateContent(prompt);
            log.info("[RAG] Personalization complete for userId={}", userId);

            return personalizedContent;

        } catch (Exception e) {
            // Graceful fallback — never block the notification send
            log.warn("[RAG] Pipeline failed for userId={}, falling back to original content. Error: {}",
                    userId, e.getMessage());
            return originalContent;
        }
    }

    private String buildPrompt(String subject, String originalContent, String userContext) {
        return """
                You are a notification personalization assistant.
                
                Personalize the following notification for a user based on their context.
                Keep it concise, friendly, and relevant. Do not add any preamble or explanation.
                Just return the personalized notification text directly.
                
                Notification Subject: %s
                
                Original Content: %s
                
                User Context:
                %s
                
                Personalized notification:
                """.formatted(subject, originalContent, userContext);
    }
}