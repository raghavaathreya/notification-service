package com.raghav.notificationservice.producer;

import com.raghav.notificationservice.dto.NotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange.notification}")
    private String exchange;

    @Value("${rabbitmq.routing-key.email}")
    private String emailRoutingKey;

    @Value("${rabbitmq.routing-key.sms}")
    private String smsRoutingKey;

    @Value("${rabbitmq.routing-key.push}")
    private String pushRoutingKey;

    public void publishEmailNotification(NotificationMessage message) {
        log.info("Publishing EMAIL notification for userId={} to exchange={}", message.getUserId(), exchange);
        rabbitTemplate.convertAndSend(exchange, emailRoutingKey, message);
    }

    public void publishSmsNotification(NotificationMessage message) {
        log.info("Publishing SMS notification for userId={} to exchange={}", message.getUserId(), exchange);
        rabbitTemplate.convertAndSend(exchange, smsRoutingKey, message);
    }

    public void publishPushNotification(NotificationMessage message) {
        log.info("Publishing PUSH notification for userId={} to exchange={}", message.getUserId(), exchange);
        rabbitTemplate.convertAndSend(exchange, pushRoutingKey, message);
    }
}
