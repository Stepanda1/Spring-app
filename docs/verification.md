# Verification evidence

Verified locally on 2026-10-08 using Docker Compose with real PostgreSQL 17 and RabbitMQ 4.1. No external email provider was used.

## Integration tests

Command: `docker compose --profile test run --rm tests`.

Result: **19 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

Covered: HTTP acceptance/readback, broker delivery, repeated and conflicting idempotency keys, eight concurrent requests with one key, duplicate consumer delivery, negative publisher confirmation and retry, malformed JSON/UUID, missing/invalid/oversized keys, invalid emails, blank/oversized fields, valid maximum lengths, missing resources, and poison-message dead-letter routing.

Publisher rejection is injected with a mock RabbitTemplate while the database transaction and subsequent successful retry use real infrastructure. The poison-message check uses the actual broker and listener retries.

## Running-container check

Commands: `docker compose up -d --build` and `pwsh ./scripts/verify.ps1`.

```text
PASS: HTTP 202 -> SENT, idempotent replay, conflicting replay 409
PASS: broker outage, readiness 503, app restart, persistent outbox recovery
VERIFIED: all HTTP and recovery checks passed.
```

The script stopped RabbitMQ, accepted a PENDING notification, verified readiness 503, restarted the app while the broker was offline, reread both PENDING and previously SENT records, restored RabbitMQ and observed SENT with deliveryCount=1. The stack remained running after verification.

## CI

The [Verify workflow](https://github.com/Stepanda1/Spring-app/actions/workflows/verify.yml) repeats the integration suite, builds the app and runs the same HTTP/outage script on Linux. Check a run's commit and result before treating it as evidence for a later revision. JUnit XML reports are attached to workflow runs.

These checks demonstrate the specified local scenarios. They do not establish production security, external email delivery, load capacity or high availability.
