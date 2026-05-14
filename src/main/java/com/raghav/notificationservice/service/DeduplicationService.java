package com.raghav.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeduplicationService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${notification.dedup.ttl}")
    private long dedupTtlSeconds;

    private static final String DEDUP_PREFIX = "notif:dedup:";

    /**
     * Generates a dedup key from userId + type + subject to catch duplicate sends.
     * Returns true if this is a duplicate (key already exists).
     */
    public boolean isDuplicate(String userId, String type, String subject) {
        String key = buildKey(userId, type, subject);
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Marks a notification as processed in Redis with a TTL.
     */
    public void markAsProcessed(String userId, String type, String subject) {
        String key = buildKey(userId, type, subject);
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(dedupTtlSeconds));
        log.debug("Marked notification as processed: key={}", key);
    }

    /**
     * Idempotency check using notification ID.
     */
    public boolean isAlreadyProcessed(UUID notificationId) {
        String key = DEDUP_PREFIX + "id:" + notificationId.toString();
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void markIdAsProcessed(UUID notificationId) {
        String key = DEDUP_PREFIX + "id:" + notificationId.toString();
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(dedupTtlSeconds));
    }

    private String buildKey(String userId, String type, String subject) {
        // Normalize and hash to keep key short
        String raw = userId + ":" + type + ":" + subject.toLowerCase().trim();
        return DEDUP_PREFIX + raw.hashCode();
    }
}
