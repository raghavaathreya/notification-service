package com.raghav.notificationservice.integration;

import com.raghav.notificationservice.dto.NotificationRequest;
import com.raghav.notificationservice.dto.NotificationResponse;
import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.model.NotificationType;
import com.raghav.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

class NotificationFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    private String authToken;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        // Get a fresh auth token before each test
        authToken = registerAndGetToken();
    }

    // ─────────────────────────────────────────────────────────
    // EMAIL
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Email notification: API returns 202, consumer marks SENT")
    void sendEmailNotification_fullFlow_statusUpdateToSent() {
        stubRagPassthrough("Your order has been dispatched.");
        NotificationRequest request = buildRequest("user-123", NotificationType.EMAIL,
                "test@example.com", "Order Shipped", "Your order #456 has shipped.", false);

        ResponseEntity<NotificationResponse> response = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getStatus()).isEqualTo(NotificationStatus.QUEUED);

        UUID notificationId = response.getBody().getId();

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Notification saved = notificationRepository.findById(notificationId).orElseThrow();
                    assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
                    assertThat(saved.getSentAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("Email notification: with personalize=true, personalized content is stored")
    void sendEmailNotification_withPersonalization_storesPersonalizedContent() {
        String personalizedContent = "Hi! Your Sony headphones order is on its way.";
        stubRagPipeline(personalizedContent);

        NotificationRequest request = buildRequest("user-123", NotificationType.EMAIL,
                "test@example.com", "Order Shipped", "Your order has shipped.", true);

        ResponseEntity<NotificationResponse> response = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        UUID notificationId = response.getBody().getId();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification saved = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(saved.getPersonalizedContent()).isEqualTo(personalizedContent);
        });
    }

    // ─────────────────────────────────────────────────────────
    // SMS
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("SMS notification: full flow results in SENT status")
    void sendSmsNotification_fullFlow_statusUpdateToSent() {
        stubRagPassthrough("Your OTP is 123456.");
        NotificationRequest request = buildRequest("user-456", NotificationType.SMS,
                "+919876543210", "OTP", "Your OTP is 123456.", false);

        ResponseEntity<NotificationResponse> response = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID notificationId = response.getBody().getId();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification saved = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    // ─────────────────────────────────────────────────────────
    // PUSH
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Push notification: full flow results in SENT status")
    void sendPushNotification_fullFlow_statusUpdateToSent() {
        stubRagPassthrough("You have a new message.");
        NotificationRequest request = buildRequest("user-789", NotificationType.PUSH,
                "device-token-abc123", "New Message", "You have a new message.", false);

        ResponseEntity<NotificationResponse> response = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID notificationId = response.getBody().getId();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Notification saved = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    // ─────────────────────────────────────────────────────────
    // Deduplication
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate notification: second send returns DUPLICATE, only one DB record")
    void sendDuplicateNotification_secondRequestReturnsDuplicate() {
        stubRagPassthrough("Your order has shipped.");
        NotificationRequest request = buildRequest("user-123", NotificationType.EMAIL,
                "test@example.com", "Order Shipped", "Your order has shipped.", false);

        ResponseEntity<NotificationResponse> first = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        ResponseEntity<NotificationResponse> second = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), NotificationResponse.class);

        assertThat(first.getBody().getStatus()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(second.getBody().getStatus()).isEqualTo(NotificationStatus.DUPLICATE);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long nonDuplicates = notificationRepository.findAll().stream()
                    .filter(n -> n.getStatus() != NotificationStatus.DUPLICATE)
                    .count();
            assertThat(nonDuplicates).isEqualTo(1);
        });
    }

    // ─────────────────────────────────────────────────────────
    // Auth protection
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("No token: returns 401")
    void sendNotification_noToken_returns401() {
        NotificationRequest request = buildRequest("user-123", NotificationType.EMAIL,
                "test@example.com", "Test", "Content", false);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/notifications", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invalid request: missing userId returns 400")
    void sendNotification_missingUserId_returns400() {
        NotificationRequest request = NotificationRequest.builder()
                .type(NotificationType.EMAIL)
                .recipient("test@example.com")
                .subject("Test")
                .content("Content")
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("userId");
    }

    // ─────────────────────────────────────────────────────────
    // GET endpoints
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /user/{userId}: returns all notifications for the user")
    void getNotificationsByUser_returnsCorrectList() {
        stubRagPassthrough("content");

        restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, buildRequest("user-AAA", NotificationType.EMAIL, "a@test.com", "S1", "C1", false)),
                NotificationResponse.class);
        restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                withAuth(authToken, buildRequest("user-AAA", NotificationType.SMS, "+1234", "S2", "C2", false)),
                NotificationResponse.class);

        await().atMost(5, TimeUnit.SECONDS).until(() ->
                notificationRepository.findByUserId("user-AAA").size() == 2);

        ResponseEntity<NotificationResponse[]> response = restTemplate.exchange(
                "/api/v1/notifications/user/user-AAA", HttpMethod.GET,
                withAuth(authToken), NotificationResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private NotificationRequest buildRequest(String userId, NotificationType type,
                                             String recipient, String subject,
                                             String content, boolean personalize) {
        return NotificationRequest.builder()
                .userId(userId).type(type).recipient(recipient)
                .subject(subject).content(content).personalize(personalize)
                .build();
    }
}
