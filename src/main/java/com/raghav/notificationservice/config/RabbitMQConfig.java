package com.raghav.notificationservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange.notification}")
    private String notificationExchange;

    @Value("${rabbitmq.queue.email}")
    private String emailQueue;

    @Value("${rabbitmq.queue.sms}")
    private String smsQueue;

    @Value("${rabbitmq.queue.push}")
    private String pushQueue;

    @Value("${rabbitmq.routing-key.email}")
    private String emailRoutingKey;

    @Value("${rabbitmq.routing-key.sms}")
    private String smsRoutingKey;

    @Value("${rabbitmq.routing-key.push}")
    private String pushRoutingKey;

    // Exchange
    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(notificationExchange, true, false);
    }

    // Queues - each with a Dead Letter Queue for failed messages
    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(emailQueue)
                .withArgument("x-dead-letter-exchange", notificationExchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", emailQueue + ".dead")
                .build();
    }

    @Bean
    public Queue smsQueue() {
        return QueueBuilder.durable(smsQueue)
                .withArgument("x-dead-letter-exchange", notificationExchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", smsQueue + ".dead")
                .build();
    }

    @Bean
    public Queue pushQueue() {
        return QueueBuilder.durable(pushQueue)
                .withArgument("x-dead-letter-exchange", notificationExchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", pushQueue + ".dead")
                .build();
    }

    // Dead Letter Exchange + Queues
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(notificationExchange + ".dlx");
    }

    @Bean
    public Queue emailDeadQueue() {
        return QueueBuilder.durable(emailQueue + ".dead").build();
    }

    @Bean
    public Queue smsDeadQueue() {
        return QueueBuilder.durable(smsQueue + ".dead").build();
    }

    @Bean
    public Queue pushDeadQueue() {
        return QueueBuilder.durable(pushQueue + ".dead").build();
    }

    // Bindings
    @Bean
    public Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(notificationExchange()).with(emailRoutingKey);
    }

    @Bean
    public Binding smsBinding() {
        return BindingBuilder.bind(smsQueue()).to(notificationExchange()).with(smsRoutingKey);
    }

    @Bean
    public Binding pushBinding() {
        return BindingBuilder.bind(pushQueue()).to(notificationExchange()).with(pushRoutingKey);
    }

    @Bean
    public Binding emailDeadBinding() {
        return BindingBuilder.bind(emailDeadQueue()).to(deadLetterExchange()).with(emailQueue + ".dead");
    }

    @Bean
    public Binding smsDeadBinding() {
        return BindingBuilder.bind(smsDeadQueue()).to(deadLetterExchange()).with(smsQueue + ".dead");
    }

    @Bean
    public Binding pushDeadBinding() {
        return BindingBuilder.bind(pushDeadQueue()).to(deadLetterExchange()).with(pushQueue + ".dead");
    }

    // JSON message converter
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
