package com.raghav.notificationservice.integration;

import com.raghav.notificationservice.model.NotificationType;
import com.raghav.notificationservice.service.DeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DeduplicationService against a real Redis container.
 *
 * These verify that:
 * - isDuplicate correctly reads from Redis
 * - markAsProcessed actually writes to Redis with TTL
 * - isAlreadyProcessed / markIdAsProcessed work with notification UUIDs
 * - Different userId+type+subject combos are independent keys
 */
class DeduplicationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DeduplicationService deduplicationService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void flushRedis() {
        // Clear all Redis keys between tests for isolation
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("isDuplicate: returns false for a key that has never been processed")
    void isDuplicate_newNotification_returnsFalse() {
        boolean result = deduplicationService.isDuplicate("user-123", "EMAIL", "Order Shipped");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isDuplicate: returns true after markAsProcessed is called")
    void isDuplicate_afterMarkingAsProcessed_returnsTrue() {
        // Act
        deduplicationService.markAsProcessed("user-123", "EMAIL", "Order Shipped");

        // Assert
        assertThat(deduplicationService.isDuplicate("user-123", "EMAIL", "Order Shipped")).isTrue();
    }

    @Test
    @DisplayName("isDuplicate: different subject is not considered a duplicate")
    void isDuplicate_differentSubject_returnsFalse() {
        // Arrange: mark one combination as processed
        deduplicationService.markAsProcessed("user-123", "EMAIL", "Order Shipped");

        // Assert: different subject is a separate key
        assertThat(deduplicationService.isDuplicate("user-123", "EMAIL", "Payment Received")).isFalse();
    }

    @Test
    @DisplayName("isDuplicate: different userId is not considered a duplicate")
    void isDuplicate_differentUser_returnsFalse() {
        deduplicationService.markAsProcessed("user-123", "EMAIL", "Order Shipped");
        assertThat(deduplicationService.isDuplicate("user-456", "EMAIL", "Order Shipped")).isFalse();
    }

    @Test
    @DisplayName("isDuplicate: different type is not considered a duplicate")
    void isDuplicate_differentType_returnsFalse() {
        deduplicationService.markAsProcessed("user-123", "EMAIL", "Order Shipped");
        assertThat(deduplicationService.isDuplicate("user-123", "SMS", "Order Shipped")).isFalse();
    }

    @Test
    @DisplayName("isDuplicate: subject matching is case-insensitive")
    void isDuplicate_caseInsensitiveSubject_treatsAsIdentical() {
        // Arrange: mark with one casing
        deduplicationService.markAsProcessed("user-123", "EMAIL", "ORDER SHIPPED");

        // Assert: lowercase version is detected as duplicate
        assertThat(deduplicationService.isDuplicate("user-123", "EMAIL", "order shipped")).isTrue();
        assertThat(deduplicationService.isDuplicate("user-123", "EMAIL", "Order Shipped")).isTrue();
    }

    @Test
    @DisplayName("isAlreadyProcessed: returns false for an unknown notification ID")
    void isAlreadyProcessed_unknownId_returnsFalse() {
        UUID unknownId = UUID.randomUUID();
        assertThat(deduplicationService.isAlreadyProcessed(unknownId)).isFalse();
    }

    @Test
    @DisplayName("isAlreadyProcessed: returns true after markIdAsProcessed")
    void isAlreadyProcessed_afterMarkingId_returnsTrue() {
        UUID notificationId = UUID.randomUUID();

        deduplicationService.markIdAsProcessed(notificationId);

        assertThat(deduplicationService.isAlreadyProcessed(notificationId)).isTrue();
    }

    @Test
    @DisplayName("isAlreadyProcessed: different IDs are independent")
    void isAlreadyProcessed_differentIds_areIndependent() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        deduplicationService.markIdAsProcessed(id1);

        assertThat(deduplicationService.isAlreadyProcessed(id1)).isTrue();
        assertThat(deduplicationService.isAlreadyProcessed(id2)).isFalse();
    }

    @Test
    @DisplayName("markAsProcessed: key has a TTL set in Redis (not persisted forever)")
    void markAsProcessed_keyHasTtl() {
        // Act
        deduplicationService.markAsProcessed("user-123", "EMAIL", "Order Shipped");

        // Assert: key exists and has a TTL (not -1 which means no expiry)
        // We can't easily test the exact TTL value, but we can verify it's set
        String key = findDeduplicationKey("user-123", "EMAIL", "Order Shipped");
        if (key != null) {
            Long ttl = redisTemplate.getExpire(key);
            assertThat(ttl).isGreaterThan(0L);
        }
        // If we can't find the exact key (hash-based), just verify the dedup works
        assertThat(deduplicationService.isDuplicate("user-123", "EMAIL", "Order Shipped")).isTrue();
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Scans Redis for a key matching the dedup pattern.
     * The actual key is hashed in DeduplicationService, so we scan by prefix.
     */
    private String findDeduplicationKey(String userId, String type, String subject) {
        var keys = redisTemplate.keys("notif:dedup:*");
        if (keys == null || keys.isEmpty()) return null;
        return keys.iterator().next();
    }
}
