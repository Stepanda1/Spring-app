package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "notifications.publish-initial-delay=3600000")
@AutoConfigureMockMvc
class NotificationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationService service;
    @Autowired NotificationConsumer consumer;
    @Autowired OutboxPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private NotificationRequest request() {
        return new NotificationRequest("demo@example.com", "Order ready", "Your order is ready.");
    }
    private String key() { return UUID.randomUUID().toString(); }

    @Test
    void acceptedRequestTravelsThroughRealBroker() throws Exception {
        var result = mvc.perform(post("/api/notifications").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request())))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andReturn();
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        publisher.publish();
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(service.get(id).status()).isEqualTo("SENT");
            assertThat(service.get(id).deliveryCount()).isEqualTo(1);
        });
        mvc.perform(get("/api/notifications/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sentAt").isNotEmpty());
    }

    @Test
    void identicalRequestReturnsSameId() {
        String key = key();
        var first = service.create(key, request());
        var second = service.create(key, request());
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void changedBodyWithSameKeyReturnsConflict() throws Exception {
        String key = key();
        service.create(key, request());
        mvc.perform(post("/api/notifications").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NotificationRequest("demo@example.com", "Different", "Body"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void concurrentRequestsCreateOneNotification() throws Exception {
        String key = key();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = IntStream.range(0, 8).mapToObj(i -> (Callable<UUID>)
                    () -> service.create(key, request()).id()).toList();
            var results = pool.invokeAll(calls);
            UUID expected = results.get(0).get();
            for (var result : results) assertThat(result.get()).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE idempotency_key = ?",
                    Integer.class, key)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void duplicateDeliveryChangesDatabaseOnlyOnce() {
        var saved = service.create(key(), request());
        consumer.receive(saved.id().toString());
        var first = service.get(saved.id());
        consumer.receive(saved.id().toString());
        assertThat(service.get(saved.id()).sentAt()).isEqualTo(first.sentAt());
        assertThat(service.get(saved.id()).deliveryCount()).isEqualTo(1);
    }

    @Test
    void brokerNackLeavesRecordPendingAndRetryDeliversIt() {
        var saved = service.create(key(), request());
        var failedBroker = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(false, "test nack"));
            return null;
        }).when(failedBroker).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
        new OutboxPublisher(jdbc, failedBroker, transactions).publish();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM notifications WHERE id = ?",
                Boolean.class, saved.id())).isTrue();
        publisher.publish();
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(service.get(saved.id()).status()).isEqualTo("SENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad key", "a/b"})
    void invalidIdempotencyKeyIsRejected(String key) throws Exception {
        mvc.perform(post("/api/notifications").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mvc.perform(post("/api/notifications").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request()))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-email", "", "   "})
    void invalidEmailIsRejected(String email) throws Exception {
        mvc.perform(post("/api/notifications").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NotificationRequest(email, "Subject", "Body"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankSubjectAndOversizedBodyAreRejected() throws Exception {
        for (var request : new NotificationRequest[] {
                new NotificationRequest("demo@example.com", " ", "Body"),
                new NotificationRequest("demo@example.com", "s".repeat(201), "Body"),
                new NotificationRequest("demo@example.com", "Subject", "x".repeat(4001))}) {
            mvc.perform(post("/api/notifications").header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void maximumLengthsAreAccepted() throws Exception {
        mvc.perform(post("/api/notifications").header("Idempotency-Key", key() + "k".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NotificationRequest("demo@example.com", "s".repeat(200), "b".repeat(4000)))))
                .andExpect(status().isAccepted());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post("/api/notifications").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingNotificationReturns404() throws Exception {
        mvc.perform(get("/api/notifications/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void malformedIdAndOversizedKeyAreRejected() throws Exception {
        mvc.perform(get("/api/notifications/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/notifications").header("Idempotency-Key", "k".repeat(101))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void poisonMessageIsMovedToDeadLetterQueue() {
        String poison = "invalid-uuid-" + key();
        rabbit.convertAndSend(MessagingConfig.EXCHANGE, "send", poison);
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(rabbit.receiveAndConvert(MessagingConfig.DEAD_QUEUE)).isEqualTo(poison));
    }
}
