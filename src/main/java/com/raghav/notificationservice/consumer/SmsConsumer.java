package com.raghav.notificationservice.consumer;

import com.raghav.notificationservice.dto.NotificationMessage;
import com.raghav.notificationservice.model.Notification;
import com.raghav.notificationservice.model.NotificationStatus;
import com.raghav.notificationservice.rag.RagPipelineService;
import com.raghav.notificationservice.repository.NotificationRepository;
import com.raghav.notificationservice.sender.SmsSender;
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
public class SmsConsumer {

    private final NotificationRepository notificationRepository;
    private final RagPipelineService ragPipelineService;
    private final DeduplicationService deduplicationService;
    private final SmsSender smsSender;

    @RabbitListener(queues = "${rabbitmq.queue.sms}", ackMode = "MANUAL")
    public void consume(NotificationMessage message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        log.info("[SMS CONSUMER] Received id={}, userId={}", message.getNotificationId(), message.getUserId());

        try {
            if (deduplicationService.isAlreadyProcessed(message.getNotificationId())) {
                log.warn("[SMS CONSUMER] Already processed id={}, skipping", message.getNotificationId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            Notification notification = notificationRepository.findById(message.getNotificationId())
                    .orElseThrow(() -> new RuntimeException("Notification not found: " + message.getNotificationId()));

            // RAG personalization — SMS content is always truncated to 160 chars by SmsSender
            String finalContent = message.getContent();
            if (message.isPersonalize()) {
                log.info("[SMS CONSUMER] Running RAG pipeline for userId={}", message.getUserId());
                finalContent = ragPipelineService.personalize(
                        message.getUserId(), message.getSubject(), message.getContent());
                notification.setPersonalizedContent(finalContent);
            }

            // Send via Twilio
            smsSender.send(message.getRecipient(), finalContent);

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);

            deduplicationService.markIdAsProcessed(message.getNotificationId());

            channel.basicAck(deliveryTag, false);
            log.info("[SMS CONSUMER] Successfully sent SMS for id={}", message.getNotificationId());

        } catch (Exception e) {
            log.error("[SMS CONSUMER] Failed to process id={}: {}", message.getNotificationId(), e.getMessage());
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
