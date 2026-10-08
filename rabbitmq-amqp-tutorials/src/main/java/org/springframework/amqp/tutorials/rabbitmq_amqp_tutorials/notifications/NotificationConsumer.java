package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumer.class);
    private final JdbcTemplate jdbc;
    public NotificationConsumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @RabbitListener(queues = MessagingConfig.QUEUE)
    @Transactional
    public void receive(String message) {
        UUID id = UUID.fromString(message);
        int changed = jdbc.update("""
                UPDATE notifications SET status = 'SENT', sent_at = NOW(), delivery_count = 1
                WHERE id = ? AND status = 'PENDING'
                """, id);
        // This database transition is the demo delivery. No real email provider is called.
        if (changed == 1) LOG.info("Simulated notification delivery: id={}", id);
    }
}
