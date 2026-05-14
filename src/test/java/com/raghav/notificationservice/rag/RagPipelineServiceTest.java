package com.raghav.notificationservice.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagPipelineServiceTest {

    @Mock
    private PineconeService pineconeService;

    @Mock
    private GeminiService geminiService;

    @InjectMocks
    private RagPipelineService ragPipelineService;

    private static final String USER_ID = "user-123";
    private static final String SUBJECT = "Your order has shipped";
    private static final String ORIGINAL_CONTENT = "Your order #456 has been dispatched.";
    private static final String USER_CONTEXT = "category: electronics\nproduct: Sony WH-1000XM5";
    private static final String PERSONALIZED_CONTENT = "Hi! Your Sony headphones order #456 is on its way. Enjoy the music!";

    @BeforeEach
    void setUp() {
        when(pineconeService.retrieveUserContext(USER_ID, SUBJECT)).thenReturn(USER_CONTEXT);
        when(geminiService.generateContent(anyString())).thenReturn(PERSONALIZED_CONTENT);
    }

    @Test
    @DisplayName("personalize: returns Gemini-generated content when pipeline succeeds")
    void personalize_fullPipelineSuccess_returnsPersonalizedContent() {
        String result = ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);
        assertThat(result).isEqualTo(PERSONALIZED_CONTENT);
    }

    @Test
    @DisplayName("personalize: calls Pinecone with correct userId and subject")
    void personalize_callsPineconeWithCorrectArgs() {
        ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);
        verify(pineconeService, times(1)).retrieveUserContext(USER_ID, SUBJECT);
    }

    @Test
    @DisplayName("personalize: prompt includes subject, original content, and user context")
    void personalize_promptContainsAllSections() {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(geminiService.generateContent(promptCaptor.capture())).thenReturn(PERSONALIZED_CONTENT);

        ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);

        String capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt).contains(SUBJECT);
        assertThat(capturedPrompt).contains(ORIGINAL_CONTENT);
        assertThat(capturedPrompt).contains(USER_CONTEXT);
        assertThat(capturedPrompt).containsIgnoringCase("personali");
    }

    @Test
    @DisplayName("personalize: calls Gemini exactly once")
    void personalize_callsGeminiExactlyOnce() {
        ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);
        verify(geminiService, times(1)).generateContent(anyString());
    }

    @Test
    @DisplayName("personalize: falls back to original content when Pinecone throws")
    void personalize_pineconeThrows_fallsBackToOriginalContent() {
        when(pineconeService.retrieveUserContext(anyString(), anyString()))
                .thenThrow(new RuntimeException("Pinecone connection refused"));

        String result = ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);

        assertThat(result).isEqualTo(ORIGINAL_CONTENT);
        verify(geminiService, never()).generateContent(anyString());
    }

    @Test
    @DisplayName("personalize: falls back to original content when Gemini throws")
    void personalize_geminiThrows_fallsBackToOriginalContent() {
        when(geminiService.generateContent(anyString()))
                .thenThrow(new RuntimeException("Gemini rate limit exceeded"));

        String result = ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);

        assertThat(result).isEqualTo(ORIGINAL_CONTENT);
    }

    @Test
    @DisplayName("personalize: still calls Gemini even when Pinecone returns no context")
    void personalize_noUserContext_stillCallsGemini() {
        when(pineconeService.retrieveUserContext(USER_ID, SUBJECT))
                .thenReturn("No user context available.");

        ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);

        verify(geminiService, times(1)).generateContent(anyString());
    }

    @Test
    @DisplayName("personalize: prompt includes fallback context text when Pinecone returns none")
    void personalize_noUserContext_promptIncludesFallbackContextText() {
        String noContext = "No user context available.";
        when(pineconeService.retrieveUserContext(USER_ID, SUBJECT)).thenReturn(noContext);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(geminiService.generateContent(promptCaptor.capture())).thenReturn("some output");

        ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT);

        assertThat(promptCaptor.getValue()).contains(noContext);
    }

    @Test
    @DisplayName("personalize: returns non-null on happy path")
    void personalize_happyPath_returnsNonNull() {
        assertThat(ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT)).isNotNull();
    }

    @Test
    @DisplayName("personalize: returns non-null when Gemini throws")
    void personalize_geminiThrows_returnsNonNull() {
        when(geminiService.generateContent(anyString())).thenThrow(new RuntimeException("down"));
        assertThat(ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT)).isNotNull();
    }

    @Test
    @DisplayName("personalize: returns non-null when Pinecone returns empty context")
    void personalize_emptyContext_returnsNonNull() {
        when(pineconeService.retrieveUserContext(anyString(), anyString()))
                .thenReturn("No user context available.");
        assertThat(ragPipelineService.personalize(USER_ID, SUBJECT, ORIGINAL_CONTENT)).isNotNull();
    }
}