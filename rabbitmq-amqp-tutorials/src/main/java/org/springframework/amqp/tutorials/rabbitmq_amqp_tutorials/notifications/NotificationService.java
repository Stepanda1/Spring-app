package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Notification> MAPPER = (rs, row) -> new Notification(
            rs.getObject("id", UUID.class), rs.getString("recipient"), rs.getString("subject"),
            rs.getString("body"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
            rs.getInt("delivery_count"));

    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Notification create(String key, NotificationRequest request) {
        jdbc.update("""
                INSERT INTO notifications (id, idempotency_key, recipient, subject, body)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING
                """, UUID.randomUUID(), key, request.recipient(), request.subject(), request.body());
        Notification saved = jdbc.queryForObject(
                "SELECT * FROM notifications WHERE idempotency_key = ?", MAPPER, key);
        if (!saved.recipient().equals(request.recipient()) || !saved.subject().equals(request.subject())
                || !saved.body().equals(request.body())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used with a different request");
        }
        return saved;
    }

    public Notification get(UUID id) {
        return jdbc.query("SELECT * FROM notifications WHERE id = ?", MAPPER, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification not found"));
    }
}
