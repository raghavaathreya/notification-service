package com.raghav.notificationservice.integration;

import com.raghav.notificationservice.auth.AuthDtos;
import com.raghav.notificationservice.auth.UserRepository;
import com.raghav.notificationservice.rag.OpenAiService;
import com.raghav.notificationservice.rag.PineconeService;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Base class for all integration tests.
 *
 * Provides:
 * - Three real Docker containers (PostgreSQL, RabbitMQ, Redis) started once
 *   and shared across all test classes via static Testcontainers fields.
 * - MockBeans for OpenAiService and PineconeService (no real API calls in CI).
 * - Auth helper methods so subclasses can easily register/login and get
 *   a Bearer token to use on protected endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class BaseIntegrationTest {

    // ─────────────────────────────────────────────────────────
    // Containers — static so they start once for the whole test suite
    // ─────────────────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("notificationdb_test")
                    .withUsername("test_user")
                    .withPassword("test_pass");

    @Container
    static final RabbitMQContainer rabbitmq =
            new RabbitMQContainer("rabbitmq:3.12-management-alpine")
                    .withUser("guest", "guest");

    @Container
    static final RedisContainer redis =
            new RedisContainer("redis:7-alpine");

    protected static MockWebServer mockOpenAiServer;

    // Counter to generate unique emails across tests without full DB wipes
    private static final AtomicInteger emailCounter = new AtomicInteger(0);

    @BeforeAll
    static void startMockServers() throws IOException {
        mockOpenAiServer = new MockWebServer();
        mockOpenAiServer.start();
    }

    @AfterAll
    static void stopMockServers() throws IOException {
        mockOpenAiServer.shutdown();
    }

    // ─────────────────────────────────────────────────────────
    // Wire container ports into Spring properties at runtime
    // ─────────────────────────────────────────────────────────

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);

        registry.add("openai.api.key", () -> "test-key");
        registry.add("pinecone.api.key", () -> "test-pinecone-key");
        registry.add("pinecone.api.url", () -> mockOpenAiServer.url("/").toString());
    }

    // ─────────────────────────────────────────────────────────
    // MockBeans — external API services
    // ─────────────────────────────────────────────────────────

    @MockBean
    protected OpenAiService openAiService;

    @MockBean
    protected PineconeService pineconeService;

    // ─────────────────────────────────────────────────────────
    // Injected beans
    // ─────────────────────────────────────────────────────────

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected UserRepository userRepository;

    // ─────────────────────────────────────────────────────────
    // Auth helpers — used by all subclasses
    // ─────────────────────────────────────────────────────────

    /**
     * Registers a new user and returns a valid JWT token.
     * Use this in @BeforeEach to get a token for protected endpoint tests.
     *
     * Generates a unique email per call using an atomic counter so tests
     * don't collide even when run in parallel.
     */
    protected String registerAndGetToken() {
        String email = "testuser" + emailCounter.incrementAndGet() + "@example.com";
        return registerAndGetToken(email, "password123");
    }

    protected String registerAndGetToken(String email, String password) {
        // Register
        AuthDtos.RegisterRequest registerRequest = new AuthDtos.RegisterRequest(email, password);
        restTemplate.postForEntity("/api/v1/auth/register", registerRequest, AuthDtos.RegisterResponse.class);

        // Login and return token
        return loginAndGetToken(email, password);
    }

    protected String loginAndGetToken(String email, String password) {
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest(email, password);
        ResponseEntity<AuthDtos.AuthResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, AuthDtos.AuthResponse.class);
        return response.getBody().getToken();
    }

    /**
     * Builds an HttpEntity with the Authorization: Bearer header set.
     * Use for GET/DELETE requests that need auth.
     */
    protected <T> HttpEntity<T> withAuth(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Void> withAuth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    // ─────────────────────────────────────────────────────────
    // RAG stubs
    // ─────────────────────────────────────────────────────────

    protected void stubRagPipeline(String personalizedContent) {
        when(openAiService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        when(openAiService.generateContent(anyString())).thenReturn(personalizedContent);
        when(pineconeService.retrieveUserContext(anyString(), anyString()))
                .thenReturn("category: electronics\nproduct: test-product");
    }

    protected void stubRagPassthrough(String originalContent) {
        when(pineconeService.retrieveUserContext(anyString(), anyString()))
                .thenReturn("No user context available.");
        when(openAiService.generateContent(anyString())).thenReturn(originalContent);
    }
}
