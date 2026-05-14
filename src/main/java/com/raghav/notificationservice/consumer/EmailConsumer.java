package com.raghav.notificationservice.consumer;

import com.raghav.notificationservice.dto.NotificationMessage;
import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.rag.RagPipelineService;
import com.raghav.notificationservice.repository.NotificationRepository;
import com.raghav.notificationservice.sender.EmailSender;
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
public class EmailConsumer {

    private final NotificationRepository notificationRepository;
    private final RagPipelineService ragPipelineService;
    private final DeduplicationService deduplicationService;
    private final EmailSender emailSender;

    @RabbitListener(queues = "${rabbitmq.queue.email}", ackMode = "MANUAL")
    public void consume(NotificationMessage message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        log.info("[EMAIL CONSUMER] Received id={}, userId={}", message.getNotificationId(), message.getUserId());

        try {
            // Idempotency guard — consumer may receive the same message twice on requeue
            if (deduplicationService.isAlreadyProcessed(message.getNotificationId())) {
                log.warn("[EMAIL CONSUMER] Already processed id={}, acking and skipping", message.getNotificationId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            Notification notification = notificationRepository.findById(message.getNotificationId())
                    .orElseThrow(() -> new RuntimeException("Notification not found: " + message.getNotificationId()));

            // Personalize content via RAG pipeline if requested
            String finalContent = message.getContent();
            if (message.isPersonalize()) {
                log.info("[EMAIL CONSUMER] Running RAG pipeline for userId={}", message.getUserId());
                finalContent = ragPipelineService.personalize(
                        message.getUserId(), message.getSubject(), message.getContent());
                notification.setPersonalizedContent(finalContent);
            }

            // Send via JavaMail / SMTP
            emailSender.send(message.getRecipient(), message.getSubject(), finalContent);

            // Update status in PostgreSQL
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);

            // Mark as processed in Redis — prevents duplicate sends on requeue
            deduplicationService.markIdAsProcessed(message.getNotificationId());

            channel.basicAck(deliveryTag, false);
            log.info("[EMAIL CONSUMER] Successfully sent email for id={}", message.getNotificationId());

        } catch (Exception e) {
            log.error("[EMAIL CONSUMER] Failed to process id={}: {}", message.getNotificationId(), e.getMessage());
            handleFailure(message, e);
            // nack without requeue — sends to DLQ after max retry attempts
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
