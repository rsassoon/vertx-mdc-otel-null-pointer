# Quarkus MDC + OpenTelemetry Kafka Reproducer

This repository is a fully generic reproducer for Quarkus **3.31.1** where Kafka processing with MDC and OpenTelemetry can trigger:

- `Mutiny had to drop the following exception`
- `java.lang.NullPointerException`
- `io.quarkus.vertx.core.runtime.VertxMDC.putAll`
- `io.quarkus.opentelemetry.runtime.OpenTelemetryUtil.setMDCData`

## Flow Used To Trigger

1. `@Blocking` Kafka consumer with `@WithSpan`
2. Consumer writes MDC + span attributes
3. Synchronous processor calls repository methods
4. Repository methods are annotated with `@WithSpan`
5. Repository methods use reactive Redis and convert with `.await().indefinitely()`
6. High message volume + partitioned topic + concurrency

## Key Classes

- `dev.mdcotel.reproducer.consumer.KafkaMessageConsumer`
- `dev.mdcotel.reproducer.processing.MessageProcessor`
- `dev.mdcotel.reproducer.cache.EntityStateStore`
- `dev.mdcotel.reproducer.cache.EntityResultStore`
- `dev.mdcotel.reproducer.cache.RedisStoreClient`
- `dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT`

## Run

```bash
./gradlew integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

Version comparison:

```bash
./gradlew -PquarkusVersion=3.31.1 integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
./gradlew -PquarkusVersion=3.30.4 integrationTest --tests "dev.mdcotel.reproducer.integration.KafkaMessageConsumerMdcOtelStressIT"
```

Stress knobs:

- `-Dstress.burst.messages` (default `20000`)
- `-Dstress.stream.seconds` (default `180`)
- `-Dstress.await.seconds` (default `900`)
- `-Dstress.topic.partitions` (default `24`)

## Notes

- Topic partitions are configured through Dev Services to activate multi-threaded consumption.
- Per-message INFO logs are enabled so progress is visible during long runs.
- Test succeeds when the target dropped NPE is observed.
