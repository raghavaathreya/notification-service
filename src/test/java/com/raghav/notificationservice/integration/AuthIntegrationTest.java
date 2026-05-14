package com.raghav.notificationservice.integration;

import com.raghav.notificationservice.auth.AuthDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the full auth flow:
 * - Register → Login → get JWT → use JWT on protected endpoint
 * - Invalid credentials, duplicate registration, missing/invalid tokens
 *
 * Uses real PostgreSQL container (via BaseIntegrationTest).
 */
class AuthIntegrationTest extends BaseIntegrationTest {

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────
    // Register
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/register: creates user and returns 201")
    void register_validRequest_returns201() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest("user@example.com", "password123");

        ResponseEntity<AuthDtos.RegisterResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, AuthDtos.RegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getEmail()).isEqualTo("user@example.com");
        assertThat(response.getBody().getUserId()).isNotNull();
        assertThat(userRepository.existsByEmail("user@example.com")).isTrue();
    }

    @Test
    @DisplayName("POST /auth/register: duplicate email returns 409 Conflict")
    void register_duplicateEmail_returns409() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest("dup@example.com", "password123");

        // First registration succeeds
        restTemplate.postForEntity("/api/v1/auth/register", request, AuthDtos.RegisterResponse.class);

        // Second registration with same email
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").toString()).contains("already registered");
    }

    @Test
    @DisplayName("POST /auth/register: invalid email returns 400")
    void register_invalidEmail_returns400() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest("not-an-email", "password123");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /auth/register: short password returns 400")
    void register_shortPassword_returns400() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest("user@example.com", "short");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─────────────────────────────────────────────────────────
    // Login
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login: valid credentials return JWT token")
    void login_validCredentials_returnsToken() {
        // Arrange: register first
        registerUser("login@example.com", "password123");

        // Act
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest("login@example.com", "password123");
        ResponseEntity<AuthDtos.AuthResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, AuthDtos.AuthResponse.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getToken()).isNotNull().isNotEmpty();
        assertThat(response.getBody().getEmail()).isEqualTo("login@example.com");
        assertThat(response.getBody().getRole()).isEqualTo("ROLE_USER");
        assertThat(response.getBody().getExpiresInMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("POST /auth/login: token has correct JWT structure")
    void login_returnsWellFormedJwt() {
        registerUser("jwt@example.com", "password123");
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest("jwt@example.com", "password123");

        ResponseEntity<AuthDtos.AuthResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, AuthDtos.AuthResponse.class);

        String token = response.getBody().getToken();
        // JWTs have exactly 3 dot-separated base64url segments
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("POST /auth/login: wrong password returns 401")
    void login_wrongPassword_returns401() {
        registerUser("user2@example.com", "correctpassword");

        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest("user2@example.com", "wrongpassword");
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Error message should NOT reveal whether the email exists
        assertThat(response.getBody().get("error").toString())
                .isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("POST /auth/login: non-existent email returns 401")
    void login_nonExistentEmail_returns401() {
        AuthDtos.LoginRequest loginRequest = new AuthDtos.LoginRequest("ghost@example.com", "password123");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error").toString())
                .isEqualTo("Invalid email or password");
    }

    // ─────────────────────────────────────────────────────────
    // Protected endpoint access
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Protected endpoint: no token returns 401")
    void protectedEndpoint_noToken_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/notifications/user/user-123", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected endpoint: valid token grants access")
    void protectedEndpoint_validToken_grantsAccess() {
        // Arrange: register + login to get a real token
        registerUser("access@example.com", "password123");
        String token = loginAndGetToken("access@example.com", "password123");

        // Act: hit a protected endpoint with the JWT
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications/user/user-123", HttpMethod.GET, entity, String.class);

        // Assert: 200 OK (empty list is fine, we just need auth to pass)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Protected endpoint: malformed token returns 401")
    void protectedEndpoint_malformedToken_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer this.is.garbage");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications/user/user-123", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected endpoint: token from wrong secret returns 401")
    void protectedEndpoint_tokenFromWrongSecret_returns401() {
        // A valid-looking JWT but signed with a different key
        String foreignToken = "eyJhbGciOiJIUzI1NiJ9" +
                ".eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIn0" +
                ".differentSignature";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(foreignToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications/user/user-123", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // register helper delegates to base class registerAndGetToken
    private void registerUser(String email, String password) {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest(email, password);
        restTemplate.postForEntity("/api/v1/auth/register", request, AuthDtos.RegisterResponse.class);
    }
}
