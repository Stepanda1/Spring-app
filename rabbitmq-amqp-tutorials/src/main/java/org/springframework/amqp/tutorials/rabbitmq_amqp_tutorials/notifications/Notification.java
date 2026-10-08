package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import java.time.Instant;
import java.util.UUID;

public record Notification(UUID id, String recipient, String subject, String body,
                           String status, Instant createdAt, Instant sentAt, int deliveryCount) {}
