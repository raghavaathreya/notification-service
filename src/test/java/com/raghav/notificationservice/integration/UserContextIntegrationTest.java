package com.raghav.notificationservice.integration;

import com.raghav.notificationservice.dto.UserContextRequest;
import com.raghav.notificationservice.dto.UserContextResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserContextIntegrationTest extends BaseIntegrationTest {

    private String authToken;

    @BeforeEach
    void setUp() {
        authToken = registerAndGetToken();
    }

    @Test
    @DisplayName("POST /user-context: returns 201 and upserted status")
    void upsertUserContext_validRequest_returns201() {
        when(openAiService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        doNothing().when(pineconeService).upsertVector(any(), any(), any(), any());

        UserContextRequest request = buildRequest("user-123", "User purchased Sony headphones");

        ResponseEntity<UserContextResponse> response = restTemplate.exchange(
                "/api/v1/user-context", HttpMethod.POST,
                withAuth(authToken, request), UserContextResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getStatus()).isEqualTo("upserted");
        assertThat(response.getBody().getDimension()).isEqualTo(3);
    }

    @Test
    @DisplayName("POST /user-context: embedding is generated from the event text")
    void upsertUserContext_generatesEmbeddingFromText() {
        String eventText = "User browsed premium audio for 15 minutes";
        when(openAiService.generateEmbedding(eventText)).thenReturn(List.of(0.5f, 0.6f));
        doNothing().when(pineconeService).upsertVector(any(), any(), any(), any());

        restTemplate.exchange("/api/v1/user-context", HttpMethod.POST,
                withAuth(authToken, buildRequest("user-123", eventText)), UserContextResponse.class);

        verify(openAiService, times(1)).generateEmbedding(eventText);
    }

    @Test
    @DisplayName("POST /user-context/batch: processes all items")
    void upsertBatch_multipleItems_returnsAllResponses() {
        when(openAiService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));
        doNothing().when(pineconeService).upsertVector(any(), any(), any(), any());

        List<UserContextRequest> requests = List.of(
                buildRequest("user-1", "bought headphones"),
                buildRequest("user-2", "browsed cameras"),
                buildRequest("user-3", "clicked sale email")
        );

        ResponseEntity<UserContextResponse[]> response = restTemplate.exchange(
                "/api/v1/user-context/batch", HttpMethod.POST,
                withAuth(authToken, requests), UserContextResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody()).allMatch(r -> "upserted".equals(r.getStatus()));
    }

    @Test
    @DisplayName("DELETE /user-context/{userId}: returns 204")
    void deleteUserContext_returns204() {
        doNothing().when(pineconeService).deleteNamespace("user-123");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/user-context/user-123", HttpMethod.DELETE,
                withAuth(authToken), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(pineconeService, times(1)).deleteNamespace("user-123");
    }

    @Test
    @DisplayName("No token: returns 401")
    void upsertUserContext_noToken_returns401() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/user-context", buildRequest("user-123", "some event"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /user-context: missing userId returns 400")
    void upsertUserContext_missingUserId_returns400() {
        UserContextRequest request = UserContextRequest.builder()
                .text("some event")
                .metadata(Map.of("key", "val"))
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/user-context", HttpMethod.POST,
                withAuth(authToken, request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("userId");
    }

    @Test
    @DisplayName("POST /user-context: returns failed status when embedding throws")
    void upsertUserContext_embeddingFails_returnsFailedStatus() {
        when(openAiService.generateEmbedding(anyString()))
                .thenThrow(new RuntimeException("OpenAI rate limit"));

        ResponseEntity<UserContextResponse> response = restTemplate.exchange(
                "/api/v1/user-context", HttpMethod.POST,
                withAuth(authToken, buildRequest("user-123", "some event")), UserContextResponse.class);

        assertThat(response.getBody().getStatus()).isEqualTo("failed");
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private UserContextRequest buildRequest(String userId, String text) {
        return UserContextRequest.builder()
                .userId(userId)
                .text(text)
                .metadata(Map.of("source", "integration-test"))
                .build();
    }
}
