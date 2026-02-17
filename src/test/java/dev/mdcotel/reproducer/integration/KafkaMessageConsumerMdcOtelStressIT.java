package dev.mdcotel.reproducer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.mdcotel.reproducer.processing.MessageProcessor;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("integration")
class KafkaMessageConsumerMdcOtelStressIT {

    private static final String MUTINY_LOGGER = "io.quarkus.mutiny.runtime.MutinyInfrastructure";
    private static final String VERTX_MDC_CLASS = "io.quarkus.vertx.core.runtime.VertxMDC";
    private static final String OTEL_UTIL_CLASS = "io.quarkus.opentelemetry.runtime.OpenTelemetryUtil";
    private static final String DROPPED_EXCEPTION_MESSAGE = "Mutiny had to drop the following exception";
    private static final String MESSAGE_TYPE_HEADER = "messageType";
    private static final String GENERIC_MESSAGE_TYPE = "GENERIC_EVENT";
    private static final String TOPIC_NAME = "generic-input-topic";
    private static final int TARGET_PARTITIONS = Integer.getInteger("stress.topic.partitions", 24);
    private static final int BURST_MESSAGE_COUNT = Integer.getInteger("stress.burst.messages", 20_000);
    private static final Duration SUSTAINED_STREAM_DURATION =
            Duration.ofSeconds(Long.getLong("stress.stream.seconds", 180L));
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(Long.getLong("stress.await.seconds", 900L));

    @Inject
    @Channel("stress-producer")
    MutinyEmitter<byte[]> emitter;

    @Inject
    MessageProcessor messageProcessor;

    @ConfigProperty(name = "bootstrap.servers")
    String bootstrapServers;

    private Logger mutinyLogger;
    private CapturingHandler capturingHandler;
    private int messageCounter;

    @BeforeEach
    void setUp() {
        messageCounter = 0;
        messageProcessor.resetCount();
        mutinyLogger = Logger.getLogger(MUTINY_LOGGER);
        capturingHandler = new CapturingHandler();
        capturingHandler.setLevel(Level.ALL);
        mutinyLogger.addHandler(capturingHandler);
    }

    @AfterEach
    void tearDown() {
        if (mutinyLogger != null && capturingHandler != null) {
            mutinyLogger.removeHandler(capturingHandler);
        }
    }

    @Test
    void reproduces_vertxmdc_otel_drop_with_generic_kafka_flow() {
        ensureTopicHasAtLeastPartitions(TARGET_PARTITIONS);

        int sentBurst = sendBurst(BURST_MESSAGE_COUNT);
        int sentSustained = sendContinuouslyFor(SUSTAINED_STREAM_DURATION);
        int sentTotal = sentBurst + sentSustained;

        await().atMost(AWAIT_TIMEOUT)
                .until(() -> messageProcessor.processedCount() >= sentTotal || hasDroppedVertxMdcOtelException());

        var droppedRegressionLogs = capturingHandler.records().stream()
                .filter(this::isVertxMdcOtelDroppedException)
                .toList();

        assertThat(droppedRegressionLogs)
                .withFailMessage(
                        "Detected dropped VertxMDC/OpenTelemetry exception in consumer flow: %s", droppedRegressionLogs)
                .isNotEmpty();
    }

    private boolean hasDroppedVertxMdcOtelException() {
        return capturingHandler.records().stream().anyMatch(this::isVertxMdcOtelDroppedException);
    }

    private void ensureTopicHasAtLeastPartitions(int targetPartitions) {
        try (var adminClient =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            var existingTopics = adminClient.listTopics().names().get(30, TimeUnit.SECONDS);
            if (!existingTopics.contains(TOPIC_NAME)) {
                adminClient
                        .createTopics(List.of(new NewTopic(TOPIC_NAME, targetPartitions, (short) 1)))
                        .all()
                        .get(30, TimeUnit.SECONDS);
                return;
            }

            int currentPartitions = adminClient
                    .describeTopics(List.of(TOPIC_NAME))
                    .allTopicNames()
                    .get(30, TimeUnit.SECONDS)
                    .get(TOPIC_NAME)
                    .partitions()
                    .size();
            if (currentPartitions < targetPartitions) {
                adminClient
                        .createPartitions(Map.of(TOPIC_NAME, NewPartitions.increaseTo(targetPartitions)))
                        .all()
                        .get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure topic partitions", e);
        }
    }

    private int sendBurst(int messageCount) {
        for (int i = 0; i < messageCount; i++) {
            sendSingleMessage();
        }
        return messageCount;
    }

    private int sendContinuouslyFor(Duration duration) {
        long deadlineNanos = System.nanoTime() + duration.toNanos();
        int sent = 0;
        while (System.nanoTime() < deadlineNanos) {
            sendSingleMessage();
            sent++;
        }
        return sent;
    }

    private void sendSingleMessage() {
        int current = messageCounter++;
        String entityId = "entity-" + (700_000_000 + current);
        byte[] payload = ("payload-" + entityId + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(entityId)
                .withTimestamp(Instant.now())
                .withHeaders(List.of(
                        new RecordHeader(MESSAGE_TYPE_HEADER, GENERIC_MESSAGE_TYPE.getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader(
                                "message-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))))
                .build();

        Message<byte[]> message = Message.of(payload).addMetadata(metadata);
        emitter.sendMessage(message).await().indefinitely();
    }

    private boolean isVertxMdcOtelDroppedException(LogRecord record) {
        if (record == null) {
            return false;
        }
        var message = record.getMessage();
        if (message == null || !message.contains(DROPPED_EXCEPTION_MESSAGE)) {
            return false;
        }
        return hasThrowableFrame(record.getThrown(), VERTX_MDC_CLASS)
                || hasThrowableFrame(record.getThrown(), OTEL_UTIL_CLASS);
    }

    private boolean hasThrowableFrame(Throwable throwable, String className) {
        if (throwable == null) {
            return false;
        }
        for (StackTraceElement element : throwable.getStackTrace()) {
            if (className.equals(element.getClassName())) {
                return true;
            }
        }
        return hasThrowableFrame(throwable.getCause(), className);
    }

    private static final class CapturingHandler extends Handler {
        private final CopyOnWriteArrayList<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<LogRecord> records() {
            return List.copyOf(records);
        }
    }
}
