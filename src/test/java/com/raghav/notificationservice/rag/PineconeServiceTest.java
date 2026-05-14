package com.raghav.notificationservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PineconeServiceTest {

    // Fresh MockWebServer per test to avoid response queue bleed-over
    private MockWebServer mockWebServer;
    private PineconeService pineconeService;
    private GeminiService mockGeminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<Float> STUB_VECTOR = List.of(0.023f, -0.187f, 0.441f, 0.012f, -0.334f);

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);

        mockGeminiService = mock(GeminiService.class);
        when(mockGeminiService.generateEmbedding(anyString())).thenReturn(STUB_VECTOR);

        pineconeService = new PineconeService(builder, objectMapper, mockGeminiService, baseUrl);
        injectField(pineconeService, "pineconeApiKey", "test-pinecone-key");
        injectField(pineconeService, "topK", 3);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ─────────────────────────────────────────────────────────
    // retrieveUserContext() tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("retrieveUserContext: extracts metadata from high-score matches")
    void retrieveUserContext_highScoreMatches_returnsContext() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "matches": [
                            {"id": "u1", "score": 0.87, "metadata": {"category": "electronics", "product": "Sony WH-1000XM5"}},
                            {"id": "u2", "score": 0.55, "metadata": {"preference": "morning_notifications"}}
                          ]
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        String context = pineconeService.retrieveUserContext("user-123", "Your order has shipped");

        assertThat(context).contains("category: electronics");
        assertThat(context).contains("product: Sony WH-1000XM5");
        assertThat(context).doesNotContain("morning_notifications");
    }

    @Test
    @DisplayName("retrieveUserContext: calls GeminiService to embed the subject")
    void retrieveUserContext_alwaysGeneratesEmbedding() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"matches\": []}")
                .addHeader("Content-Type", "application/json"));

        pineconeService.retrieveUserContext("user-123", "Product recommendation");

        verify(mockGeminiService, times(1)).generateEmbedding("Product recommendation");
    }

    @Test
    @DisplayName("retrieveUserContext: sends vector and namespace in query body")
    void retrieveUserContext_sendsCorrectPayload() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"matches\": []}")
                .addHeader("Content-Type", "application/json"));

        pineconeService.retrieveUserContext("user-456", "some subject");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/query");
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("Api-Key")).isEqualTo("test-pinecone-key");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"namespace\":\"user-456\"");
        assertThat(body).contains("\"topK\":3");
        assertThat(body).contains("\"includeMetadata\":true");
    }

    @Test
    @DisplayName("retrieveUserContext: returns fallback when no matches found")
    void retrieveUserContext_noMatches_returnsFallback() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"matches\": []}")
                .addHeader("Content-Type", "application/json"));

        String context = pineconeService.retrieveUserContext("user-123", "test subject");

        assertThat(context).isEqualTo("No user context available.");
    }

    @Test
    @DisplayName("retrieveUserContext: returns fallback when all matches below threshold")
    void retrieveUserContext_allLowScoreMatches_returnsFallback() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"matches\": [{\"id\": \"v1\", \"score\": 0.65, \"metadata\": {\"key\": \"value\"}}]}")
                .addHeader("Content-Type", "application/json"));

        String context = pineconeService.retrieveUserContext("user-123", "test subject");

        assertThat(context).isEqualTo("No relevant user context found.");
    }

    @Test
    @DisplayName("retrieveUserContext: returns fallback gracefully when Pinecone fails")
    void retrieveUserContext_pineconeFailure_returnsFallback() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        String context = pineconeService.retrieveUserContext("user-123", "test subject");

        assertThat(context).isEqualTo("No user context available.");
    }

    // ─────────────────────────────────────────────────────────
    // upsertVector() tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("upsertVector: sends correct payload to /vectors/upsert")
    void upsertVector_sendsCorrectPayload() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"upsertedCount\": 1}")
                .addHeader("Content-Type", "application/json"));

        pineconeService.upsertVector("user-123", "user-123:order-456",
                STUB_VECTOR, Map.of("category", "electronics"));

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/vectors/upsert");
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("Api-Key")).isEqualTo("test-pinecone-key");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"namespace\":\"user-123\"");
        assertThat(body).contains("\"id\":\"user-123:order-456\"");
        assertThat(body).contains("electronics");
    }

    @Test
    @DisplayName("upsertVector: throws RuntimeException on Pinecone error")
    void upsertVector_pineconeError_throwsException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"message\": \"Vector dimension mismatch\"}"));

        assertThatThrownBy(() -> pineconeService.upsertVector(
                "user-123", "vec-id", STUB_VECTOR, Map.of("key", "val")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Pinecone upsert failed");
    }

    // ─────────────────────────────────────────────────────────
    // deleteNamespace() tests
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteNamespace: sends deleteAll=true with correct namespace")
    void deleteNamespace_sendsCorrectPayload() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{}")
                .addHeader("Content-Type", "application/json"));

        pineconeService.deleteNamespace("user-123");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/vectors/delete");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"deleteAll\":true");
        assertThat(body).contains("\"namespace\":\"user-123\"");
    }

    @Test
    @DisplayName("deleteNamespace: throws RuntimeException on failure")
    void deleteNamespace_failure_throwsException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> pineconeService.deleteNamespace("user-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Pinecone delete failed");
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field: " + fieldName, e);
        }
    }
}