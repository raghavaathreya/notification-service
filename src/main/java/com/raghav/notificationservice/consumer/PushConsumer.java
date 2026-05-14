package com.raghav.notificationservice.consumer;

import com.raghav.notificationservice.dto.NotificationMessage;
import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.rag.RagPipelineService;
import com.raghav.notificationservice.repository.NotificationRepository;
import com.raghav.notificationservice.sender.PushSender;
import com.raghav.notificationservice.service.DeduplicationService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class PushConsumer {

    private final NotificationRepository notificationRepository;
    private final RagPipelineService ragPipelineService;
    private final DeduplicationService deduplicationService;
    private final PushSender pushSender;

    @RabbitListener(queues = "${rabbitmq.queue.push}", ackMode = "MANUAL")
    public void consume(NotificationMessage message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        log.info("[PUSH CONSUMER] Received id={}, userId={}", message.getNotificationId(), message.getUserId());

        try {
            if (deduplicationService.isAlreadyProcessed(message.getNotificationId())) {
                log.warn("[PUSH CONSUMER] Already processed id={}, skipping", message.getNotificationId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            Notification notification = notificationRepository.findById(message.getNotificationId())
                    .orElseThrow(() -> new RuntimeException("Notification not found: " + message.getNotificationId()));

            // RAG personalization
            String finalContent = message.getContent();
            if (message.isPersonalize()) {
                log.info("[PUSH CONSUMER] Running RAG pipeline for userId={}", message.getUserId());
                finalContent = ragPipelineService.personalize(
                        message.getUserId(), message.getSubject(), message.getContent());
                notification.setPersonalizedContent(finalContent);
            }

            // Send via Firebase Cloud Messaging
            // recipient here is the FCM device registration token
            try {
                pushSender.send(message.getRecipient(), message.getSubject(), finalContent);
            } catch (PushSender.InvalidDeviceTokenException e) {
                // Token is permanently invalid — mark as failed but don't DLQ
                // In production: trigger async token cleanup for this userId
                log.warn("[PUSH CONSUMER] Invalid device token for userId={}, marking FAILED without retry",
                        message.getUserId());
                notification.setStatus(NotificationStatus.FAILED);
                notification.setErrorMessage("Invalid/unregistered device token");
                notificationRepository.save(notification);
                channel.basicAck(deliveryTag, false); // ack — no point retrying a bad token
                return;
            }

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);

            deduplicationService.markIdAsProcessed(message.getNotificationId());

            channel.basicAck(deliveryTag, false);
            log.info("[PUSH CONSUMER] Successfully sent push for id={}", message.getNotificationId());

        } catch (Exception e) {
            log.error("[PUSH CONSUMER] Failed to process id={}: {}", message.getNotificationId(), e.getMessage());
            handleFailure(message, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void handleFailure(NotificationMessage message, Exception e) {
        notificationRepository.findById(message.getNotificationId()).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification.setRetryCount(notification.getRetryCount() + 1);
            notificationRepository.save(notification);
        });
    }
}
