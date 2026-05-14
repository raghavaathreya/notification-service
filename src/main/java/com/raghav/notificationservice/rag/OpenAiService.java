package com.raghav.notificationservice.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Kept for backward compatibility with existing test references.
 * All actual work is delegated to GeminiService.
 *
 * Tests that mock OpenAiService will continue to work without changes.
 */
@Service
@RequiredArgsConstructor
public class OpenAiService {

    private final GeminiService geminiService;

    public List<Float> generateEmbedding(String text) {
        return geminiService.generateEmbedding(text);
    }

    public String generateContent(String prompt) {
        return geminiService.generateContent(prompt);
    }
}