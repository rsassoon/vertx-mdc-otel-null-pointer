# Quarkus Issue Report: VertxMDC/OpenTelemetry NPE During Kafka Processing

## Summary

This project reproduces an intermittent `NullPointerException` on Quarkus `3.31.x` while processing Kafka messages with:

- `@Blocking` reactive messaging consumer
- OpenTelemetry tracing (`@WithSpan`)
- MDC usage
- Redis reactive operations converted to blocking with `.await().indefinitely()`
- High concurrency and sustained message load

The failure appears as a dropped exception in Mutiny and includes:

- `io.quarkus.vertx.core.runtime.VertxMDC.putAll`
- `io.quarkus.opentelemetry.runtime.OpenTelemetryUtil.setMDCData`

Repository for Quarkus (where ticket will be created): [quarkusio/quarkus](https://github.com/quarkusio/quarkus)

---

## Affected vs Baseline

- **Affected**: `3.31.1` (and likely `3.31.x`)
- **Baseline (last known good)**: `3.30.4`

---

## Minimal Failure Signature

```text
Mutiny had to drop the following exception
java.lang.NullPointerException
    at java.util.concurrent.ConcurrentHashMap.putVal(...)
    at java.util.concurrent.ConcurrentHashMap.putAll(...)
    at io.quarkus.vertx.core.runtime.VertxMDC.putAll(...)
    at io.quarkus.vertx.core.runtime.VertxMDC.reinitializeVertxMdc(...)
    at io.quarkus.opentelemetry.runtime.OpenTelemetryUtil.setMDCData(...)
```

---

## Reproducer Design

### Runtime Flow

1. Kafka consumer method is annotated with `@Incoming`, `@Blocking`, and `@WithSpan`.
2. Consumer writes span attributes and MDC values.
3. Consumer delegates to a synchronous processor.
4. Processor calls multiple store/repository methods (`find/save`) in sequence.
5. Each store method has `@WithSpan`.
6. Store implementation uses reactive Redis APIs, then blocks via:
   - retry + backoff
   - timeout
   - `.await().indefinitely()`
7. Stress test sends burst + sustained message streams.
8. Test captures Mutiny logs and asserts dropped VertxMDC/OpenTelemetry NPE is observed.

### Why this shape matters

The reproducer intentionally creates many context transitions and MDC/span interactions under load, which is where the NPE appears.

---

## Project Entry Points

- Consumer: `src/main/java/dev/mdcotel/reproducer/consumer/KafkaMessageConsumer.java`
- Processor: `src/main/java/dev/mdcotel/reproducer/processing/MessageProcessor.java`
- Redis wrapper: `src/main/java/dev/mdcotel/reproducer/cache/RedisStoreClient.java`
- Integration test: `src/test/java/dev/mdcotel/reproducer/integration/KafkaMessageConsumerMdcOtelStressIT.java`
- Test config: `src/test/resources/application.properties`

---

## Prerequisites

- Java 21
- Docker (for Quarkus Dev Services: Kafka + Redis)
- Linux/macOS shell (commands below use `bash` style)

---

## How To Run

### 1) Clean and compile

```bash
./gradlew clean compileJava compileTestJava
```

### 2) Run reproducer test on affected version

```bash
./gradlew -PquarkusVersion=3.31.1 integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

### 3) Run with explicit stress knobs (recommended)

```bash
./gradlew -PquarkusVersion=3.31.1 integrationTest \
  --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT" \
  -Dstress.burst.messages=2500 \
  -Dstress.stream.seconds=35 \
  -Dstress.await.seconds=220 \
  -Dstress.topic.partitions=24
```

### 4) Compare with baseline

```bash
./gradlew -PquarkusVersion=3.30.4 integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

---

## Expected vs Actual

### Expected

No dropped `NullPointerException` related to VertxMDC/OpenTelemetry during normal Kafka processing.

### Actual on 3.31.x

Under stress, Mutiny logs dropped NPE containing `VertxMDC.putAll` and `OpenTelemetryUtil.setMDCData`.

---

## Test Behavior

The integration test:

- Creates/ensures topic partition count
- Sends burst + sustained Kafka load
- Captures Mutiny logger records
- Detects the exact regression signature (VertxMDC/OpenTelemetry frames)

The test is intentionally written to confirm the regression signal in logs.

---

## Configuration Details

### Messaging

- Incoming channel: `input-events`
- Outgoing stress producer channel: `stress-producer`
- Topic: `generic-input-topic`
- Concurrency and poll settings configured for high pressure

### Redis

- Dev Service Redis container
- Retry/backoff/timeout in `RedisStoreClient`
- Blocking boundary via `.await().indefinitely()`

### OpenTelemetry

- Traces enabled for context behavior
- Test exporter disabled to avoid external network dependency noise

---

## Useful Commands For Diagnostics

```bash
# Show only this test with stack traces
./gradlew -PquarkusVersion=3.31.1 integrationTest \
  --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT" \
  --stacktrace --info

# Retry same run quickly
./gradlew integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

---

## Suggested GitHub Ticket Body (Copy/Paste)

```md
### Description
We can reproduce a dropped `NullPointerException` in Quarkus 3.31.x under Kafka + MDC + OpenTelemetry load.

### Failure signature
`Mutiny had to drop the following exception` with stack frames including:
- `io.quarkus.vertx.core.runtime.VertxMDC.putAll`
- `io.quarkus.opentelemetry.runtime.OpenTelemetryUtil.setMDCData`

### Reproducer
Attached repository includes a generic reproducer:
- consumer uses `@Blocking` + `@WithSpan`
- MDC and span attributes set per message
- processor calls multiple `@WithSpan` store methods
- reactive Redis operations with `.await().indefinitely()`
- stress test sends burst + sustained traffic

### How to run
```bash
./gradlew -PquarkusVersion=3.31.1 integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

Optional stress knobs:
```bash
-Dstress.burst.messages=2500 -Dstress.stream.seconds=35 -Dstress.await.seconds=220 -Dstress.topic.partitions=24
```

### Comparison
- Affected: `3.31.1`
- Baseline: `3.30.4`
```

---

## Notes

- The reproducer is intentionally generic and does not include product/domain-specific logic.
- Per-message progress is logged at INFO to make stress run progress visible.
