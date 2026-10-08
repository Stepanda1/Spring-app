package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationRequest(
        @NotBlank @Email @Size(max = 254) String recipient,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 4000) String body) {}
