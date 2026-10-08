# Architecture

The service separates synchronous acceptance from asynchronous processing, using Spring MVC, JDBC transactions, Flyway migrations and Spring AMQP. API and worker run in one process for a small demo; they remain separate classes with a RabbitMQ boundary.

## Submission and idempotency

`POST /api/notifications` validates email, field lengths and the required key. PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` and a unique key constraint serialize competing inserts. The next statement reads the committed record; identical recipient/subject/body returns its current representation. A changed payload rolls back with 409. Keys are scoped to the whole demo and retained indefinitely.

The response is 202 for both new and replayed requests. It returns the same resource ID, not a byte-identical response: status can advance between calls.

## Transactional outbox

The notification row also acts as its outbox entry: `published_at IS NULL` means pending publication. Acceptance needs PostgreSQL, but does not contact RabbitMQ.

The scheduled publisher locks up to 20 records with `FOR UPDATE SKIP LOCKED`. It sends each ID as a persistent AMQP message, awaits a correlated broker confirmation, checks for unroutable returns and then marks the publication. Failed confirmation rolls the database transaction back; the next cycle retries.

A crash after broker acceptance but before the database commit can publish duplicates. The consumer changes `PENDING` to `SENT` through one conditional SQL update. Duplicate or concurrent deliveries do not change the timestamp or increment the database effect count. The Spring transaction commits before the listener acknowledges the message.

## Failures

The listener retries three times with backoff. Exhausted messages go to `notifications.dead` via the dead-letter exchange. Malformed messages are tested against the real broker. Operators must inspect and replay dead letters manually; there is no replay API or FAILED resource state. Unknown well-formed UUIDs are ignored because only persisted IDs should be published.

Readiness checks both PostgreSQL and RabbitMQ. Broker downtime returns 503 for readiness while the API can still accept pending work. Liveness stays UP. PostgreSQL downtime prevents acceptance.

## Limits and next steps

- Delivery is a database simulation. A real provider needs a provider idempotency key, delivery receipts and reconciliation. This implementation does not guarantee exactly-once external delivery.
- Single broker, no clustering or high availability. Database locks are held while waiting for confirms (up to 3 seconds per record), suitable for a small demo; a larger system needs bounded claims and separate publication transactions.
- No authentication, rate limiting, retention policy, tenant ownership or real secrets management. Keep the demo local.
- No throughput or latency claims: load testing has not been performed.
- For real usage, start with authorization, a provider adapter, failure state/replay tooling, outbox metrics and retention.

Confirmation behavior follows the [Spring AMQP reference](https://docs.spring.io/spring-amqp/reference/amqp/template.html). Runtime compatibility follows [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
