package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${notifications.publish-delay:1000}",
            initialDelayString = "${notifications.publish-initial-delay:1000}")
    public void publish() {
        try {
            transactions.executeWithoutResult(transaction -> {
                var ids = jdbc.query("""
                        SELECT id FROM notifications WHERE published_at IS NULL
                        ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED
                        """, (rs, row) -> rs.getObject(1, UUID.class));
                for (UUID id : ids) {
                    confirm(id);
                    jdbc.update("UPDATE notifications SET published_at = NOW() WHERE id = ?", id);
                }
            });
        } catch (RuntimeException ex) {
            LOG.warn("Outbox publication failed; pending records will be retried: {}", ex.getMessage());
        }
    }

    private void confirm(UUID id) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.convertAndSend(MessagingConfig.EXCHANGE, "send", id.toString(), correlation);
        try {
            var result = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (!result.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Broker did not accept/routably store the message");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication interrupted", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Publisher confirmation failed", ex);
        }
    }
}
