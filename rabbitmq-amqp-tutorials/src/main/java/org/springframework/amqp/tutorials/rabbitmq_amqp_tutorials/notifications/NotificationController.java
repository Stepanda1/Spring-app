package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Notification> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String key,
            @Valid @RequestBody NotificationRequest request) {
        Notification notification = service.create(key, request);
        return ResponseEntity.accepted().location(URI.create("/api/notifications/" + notification.id()))
                .body(notification);
    }

    @GetMapping("/{id}")
    public Notification get(@PathVariable UUID id) { return service.get(id); }
}
