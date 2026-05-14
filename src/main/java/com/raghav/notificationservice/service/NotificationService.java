package com.raghav.notificationservice.service;

import com.raghav.notificationservice.dto.NotificationMessage;
import com.raghav.notificationservice.dto.NotificationRequest;
import com.raghav.notificationservice.dto.NotificationResponse;
import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.producer.NotificationProducer;
import com.raghav.notificationservice.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationProducer notificationProducer;
    private final DeduplicationService deduplicationService;
    private final EntityManager entityManager;

    @Transactional
    public NotificationResponse send(NotificationRequest request) {
        log.info("Received notification request: userId={}, type={}", request.getUserId(), request.getType());

        // 1. Deduplication check
        if (deduplicationService.isDuplicate(request.getUserId(), request.getType().name(), request.getSubject())) {
            log.warn("Duplicate notification detected for userId={}, subject={}", request.getUserId(), request.getSubject());
            return NotificationResponse.builder()
                    .userId(request.getUserId())
                    .type(request.getType())
                    .status(NotificationStatus.DUPLICATE)
                    .subject(request.getSubject())
                    .message("Duplicate notification suppressed")
                    .build();
        }

        // 2. Persist notification with PENDING status
        Notification notification = Notification.builder()
                .userId(request.getUserId())
                .type(request.getType())
                .status(NotificationStatus.PENDING)
                .recipient(request.getRecipient())
                .subject(request.getSubject())
                .content(request.getContent())
                .retryCount(0)
                .build();

        notification = notificationRepository.save(notification);

        // 3. Update status to QUEUED
        notification.setStatus(NotificationStatus.QUEUED);
        notification = notificationRepository.save(notification);

        // 4. Flush to DB immediately so consumer can find the record
        // This writes to DB within the transaction before RabbitMQ publish
        entityManager.flush();

        log.info("Saved notification id={}", notification.getId());

        // 5. Build message for queue
        NotificationMessage message = NotificationMessage.builder()
                .notificationId(notification.getId())
                .userId(request.getUserId())
                .type(request.getType())
                .recipient(request.getRecipient())
                .subject(request.getSubject())
                .content(request.getContent())
                .personalize(request.isPersonalize())
                .build();

        // 6. Publish to appropriate queue — DB record is already visible
        switch (request.getType()) {
            case EMAIL -> notificationProducer.publishEmailNotification(message);
            case SMS -> notificationProducer.publishSmsNotification(message);
            case PUSH -> notificationProducer.publishPushNotification(message);
        }

        // 7. Mark in Redis to prevent duplicates
        deduplicationService.markAsProcessed(request.getUserId(), request.getType().name(), request.getSubject());

        return NotificationResponse.builder()
                .id(notification.getId())
                .userId(notification.getUserId())
                .type(notification.getType())
                .status(notification.getStatus())
                .recipient(notification.getRecipient())
                .subject(notification.getSubject())
                .message("Notification queued successfully")
                .createdAt(notification.getCreatedAt())
                .build();
    }

    public List<NotificationResponse> getByUserId(String userId) {
        return notificationRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public NotificationResponse getById(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Notification not found: " + id));
        return toResponse(notification);
    }

    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .userId(n.getUserId())
                .type(n.getType())
                .status(n.getStatus())
                .recipient(n.getRecipient())
                .subject(n.getSubject())
                .createdAt(n.getCreatedAt())
                .build();
    }
}