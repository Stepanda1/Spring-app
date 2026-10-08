# Development

The existing Maven module path is retained: `rabbitmq-amqp-tutorials/`.

For local Java development, install JDK 17 or later, start dependencies, then run the Maven wrapper:

```sh
docker compose up -d postgres rabbitmq
cd rabbitmq-amqp-tutorials
./mvnw spring-boot:run
```

On Windows use `./mvnw.cmd spring-boot:run`. Local defaults match Compose ports 55432 (PostgreSQL), 55672 (AMQP) and 8088 (HTTP). Ensure the Compose app is stopped before launching a second app on 8088.

Run `./mvnw verify` (`./mvnw.cmd verify` on Windows) against the same dependencies. Tests use actual PostgreSQL and RabbitMQ, not H2. Each test creates unique resource IDs and keys. Test records remain until you reset the volumes. Do not run tests against a production database.

Configuration: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `PORT`.

## Original learning example

The earlier RabbitMQ tutorial classes remain under `tut1`. With dependencies running, use `./mvnw spring-boot:run -Dspring-boot.run.profiles=tutorial,hello-world,sender,receiver`. This enables the original Hello World exchange and closes the application after its original 10-second demonstration. The default notification service runs continuously.

## Code map

- `NotificationController`, `ApiErrors`: HTTP contract and input errors.
- `NotificationService`: transactional acceptance and idempotency.
- `OutboxPublisher`: confirmed publication and retries.
- `NotificationConsumer`: duplicate-safe simulated delivery.
- `MessagingConfig`: durable topology and dead-letter routing.
- `db/migration/V1__notifications.sql`: schema and constraints.
- `NotificationTests`: HTTP, concurrency, broker and failure checks.
- `scripts/verify.ps1`: running-container HTTP and outage scenario.
