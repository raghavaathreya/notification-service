package com.raghav.notificationservice.rag;

import com.raghav.notificationservice.dto.UserContextRequest;
import com.raghav.notificationservice.dto.UserContextResponse;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserContextServiceTest {

    @Mock
    private GeminiService geminiService;

    @Mock
    private PineconeService pineconeService;

    @InjectMocks
    private UserContextService userContextService;

    private static final List<Float> STUB_VECTOR = List.of(0.1f, 0.2f, 0.3f);

    @BeforeEach
    void setUp() {
        when(geminiService.generateEmbedding(anyString())).thenReturn(STUB_VECTOR);
    }

    @Test
    @DisplayName("upsert: returns upserted status on successful pipeline")
    void upsert_success_returnsUpsertedStatus() {
        UserContextRequest request = buildRequest("user-123", "User purchased Sony headphones", null);

        UserContextResponse response = userContextService.upsert(request);

        assertThat(response.getStatus()).isEqualTo("upserted");
        assertThat(response.getUserId()).isEqualTo("user-123");
        assertThat(response.getDimension()).isEqualTo(3);
        assertThat(response.getMessage()).contains("successfully");
    }

    @Test
    @DisplayName("upsert: generates embedding from the event text")
    void upsert_generatesEmbeddingFromText() {
        String eventText = "User browsed electronics category for 15 minutes";
        UserContextRequest request = buildRequest("user-123", eventText, null);

        userContextService.upsert(request);

        verify(geminiService, times(1)).generateEmbedding(eventText);
    }

    @Test
    @DisplayName("upsert: calls Pinecone with correct userId, vector, and metadata")
    void upsert_callsPineconeWithCorrectArgs() {
        Map<String, String> metadata = Map.of("category", "electronics", "action", "browse");
        UserContextRequest request = buildRequest("user-123", "browsed electronics", null);
        request.setMetadata(metadata);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<List<Float>> vectorCaptor = ArgumentCaptor.forClass(List.class);

        userContextService.upsert(request);

        verify(pineconeService, times(1)).upsertVector(
                eq("user-123"), anyString(),
                vectorCaptor.capture(), metadataCaptor.capture());

        assertThat(vectorCaptor.getValue()).isEqualTo(STUB_VECTOR);
        assertThat(metadataCaptor.getValue()).containsEntry("category", "electronics");
    }

    @Test
    @DisplayName("upsert: uses caller-supplied vectorId when provided")
    void upsert_withExplicitVectorId_usesThatId() {
        UserContextRequest request = buildRequest("user-123", "some event", "user-123:order-789");
        ArgumentCaptor<String> vectorIdCaptor = ArgumentCaptor.forClass(String.class);

        userContextService.upsert(request);

        verify(pineconeService).upsertVector(anyString(), vectorIdCaptor.capture(), any(), any());
        assertThat(vectorIdCaptor.getValue()).isEqualTo("user-123:order-789");
    }

    @Test
    @DisplayName("upsert: generates deterministic vectorId when none provided")
    void upsert_withoutVectorId_generatesDeterministicId() {
        UserContextRequest r1 = buildRequest("user-123", "User bought headphones", null);
        UserContextRequest r2 = buildRequest("user-123", "User bought headphones", null);
        ArgumentCaptor<String> vectorIdCaptor = ArgumentCaptor.forClass(String.class);

        userContextService.upsert(r1);
        userContextService.upsert(r2);

        verify(pineconeService, times(2)).upsertVector(anyString(), vectorIdCaptor.capture(), any(), any());
        List<String> ids = vectorIdCaptor.getAllValues();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
    }

    @Test
    @DisplayName("upsert: generated vectorId starts with userId prefix")
    void upsert_generatedVectorId_startsWithUserId() {
        UserContextRequest request = buildRequest("user-456", "some event", null);
        ArgumentCaptor<String> vectorIdCaptor = ArgumentCaptor.forClass(String.class);

        userContextService.upsert(request);

        verify(pineconeService).upsertVector(anyString(), vectorIdCaptor.capture(), any(), any());
        assertThat(vectorIdCaptor.getValue()).startsWith("user-456:");
    }

    @Test
    @DisplayName("upsert: returns failed status when Gemini embedding throws")
    void upsert_embeddingFails_returnsFailedStatus() {
        when(geminiService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("Gemini rate limit"));

        UserContextResponse response = userContextService.upsert(
                buildRequest("user-123", "some event", null));

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getMessage()).contains("Upsert failed");
        assertThat(response.getDimension()).isEqualTo(0);
        verify(pineconeService, never()).upsertVector(any(), any(), any(), any());
    }

    @Test
    @DisplayName("upsert: returns failed status when Pinecone upsert throws")
    void upsert_pineconeUpsertFails_returnsFailedStatus() {
        doThrow(new RuntimeException("Pinecone dimension mismatch"))
                .when(pineconeService).upsertVector(any(), any(), any(), any());

        UserContextResponse response = userContextService.upsert(
                buildRequest("user-123", "some event", null));

        assertThat(response.getStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("upsertBatch: processes all requests and returns response per item")
    void upsertBatch_processesAllRequests() {
        List<UserContextRequest> requests = List.of(
                buildRequest("user-1", "event one", null),
                buildRequest("user-2", "event two", null),
                buildRequest("user-3", "event three", null)
        );

        List<UserContextResponse> responses = userContextService.upsertBatch(requests);

        assertThat(responses).hasSize(3);
        assertThat(responses).allMatch(r -> "upserted".equals(r.getStatus()));
        verify(geminiService, times(3)).generateEmbedding(anyString());
        verify(pineconeService, times(3)).upsertVector(any(), any(), any(), any());
    }

    @Test
    @DisplayName("upsertBatch: partial failures do not block other items")
    void upsertBatch_oneItemFails_othersStillProcess() {
        when(geminiService.generateEmbedding(anyString()))
                .thenReturn(STUB_VECTOR)
                .thenThrow(new RuntimeException("API error"))
                .thenReturn(STUB_VECTOR);

        List<UserContextResponse> responses = userContextService.upsertBatch(List.of(
                buildRequest("user-1", "event one", null),
                buildRequest("user-2", "event two", null),
                buildRequest("user-3", "event three", null)
        ));

        assertThat(responses.get(0).getStatus()).isEqualTo("upserted");
        assertThat(responses.get(1).getStatus()).isEqualTo("failed");
        assertThat(responses.get(2).getStatus()).isEqualTo("upserted");
    }

    @Test
    @DisplayName("deleteUserContext: delegates to Pinecone deleteNamespace")
    void deleteUserContext_callsPineconeDelete() {
        userContextService.deleteUserContext("user-123");
        verify(pineconeService, times(1)).deleteNamespace("user-123");
    }

    private UserContextRequest buildRequest(String userId, String text, String vectorId) {
        return UserContextRequest.builder()
                .userId(userId).text(text).vectorId(vectorId)
                .metadata(Map.of("source", "test"))
                .build();
    }
}