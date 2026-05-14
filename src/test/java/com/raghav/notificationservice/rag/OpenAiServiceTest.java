package com.raghav.notificationservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OpenAiServiceTest {

    private static MockWebServer mockWebServer;
    private GeminiService geminiService;
    private OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void startServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockWebServer.shutdown();
    }

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = mockWebServer.url("/").toString();
        WebClient mockWebClient = WebClient.builder().baseUrl(baseUrl).build();

        geminiService = new GeminiService(WebClient.builder().baseUrl(baseUrl), objectMapper);
        injectField(geminiService, "geminiApiKey", "test-api-key");
        injectField(geminiService, "embeddingModel", "text-embedding-004");
        injectField(geminiService, "generationModel", "gemini-1.5-flash");
        injectField(geminiService, "maxTokens", 300);
        injectField(geminiService, "webClient", mockWebClient);

        openAiService = new OpenAiService(geminiService);
    }

    @Test
    @DisplayName("generateEmbedding: returns correct vector from valid response")
    void generateEmbedding_validResponse_returnsVector() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"embedding\": {\"values\": [0.023, -0.187, 0.441, 0.012, -0.334]}}")
                .addHeader("Content-Type", "application/json"));

        List<Float> vector = openAiService.generateEmbedding("Your order has shipped");

        assertThat(vector).hasSize(5);
        assertThat(vector.get(0)).isCloseTo(0.023f, within(0.0001f));
    }

    @Test
    @DisplayName("generateEmbedding: sends request with text in body")
    void generateEmbedding_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"embedding\": {\"values\": [0.1, 0.2, 0.3]}}")
                .addHeader("Content-Type", "application/json"));

        openAiService.generateEmbedding("test input text");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).contains("embedContent");
        assertThat(request.getBody().readUtf8()).contains("test input text");
    }

    @Test
    @DisplayName("generateEmbedding: throws RuntimeException on API error")
    void generateEmbedding_apiError_throwsException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\": {\"message\": \"Invalid API key\"}}"));

        assertThatThrownBy(() -> openAiService.generateEmbedding("test"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("generateContent: returns personalized text from valid response")
    void generateContent_validResponse_returnsContent() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hi! Your Sony headphones order is on its way.\"}]}}]}")
                .addHeader("Content-Type", "application/json"));

        String result = openAiService.generateContent("Personalize this");

        assertThat(result).isEqualTo("Hi! Your Sony headphones order is on its way.");
    }

    @Test
    @DisplayName("generateContent: sends request with prompt in body")
    void generateContent_sendsCorrectRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"content\"}]}}]}")
                .addHeader("Content-Type", "application/json"));

        openAiService.generateContent("test prompt");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).contains("generateContent");
        assertThat(request.getBody().readUtf8()).contains("test prompt");
    }

    @Test
    @DisplayName("generateContent: throws RuntimeException on API error")
    void generateContent_apiError_throwsException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\": {\"message\": \"Internal server error\"}}"));

        assertThatThrownBy(() -> openAiService.generateContent("test"))
                .isInstanceOf(RuntimeException.class);
    }

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