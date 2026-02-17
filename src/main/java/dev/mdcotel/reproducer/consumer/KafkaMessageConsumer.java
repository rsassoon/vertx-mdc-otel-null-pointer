package dev.mdcotel.reproducer.consumer;

import dev.mdcotel.reproducer.processing.MessageProcessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.IncomingKafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.slf4j.MDC;

@ApplicationScoped
public class KafkaMessageConsumer {

    private static final String MESSAGE_TYPE_HEADER = "messageType";
    private static final String MDC_ENTITY_ID = "entityId";
    private static final String MDC_MESSAGE_TYPE = "messageType";
    private static final String SPAN_ATTR_ENTITY_ID = "kafka.entityId";
    private static final String SPAN_ATTR_MESSAGE_TYPE = "kafka.messageType";
    private static final int MDC_CHURN_ENTRIES = 24;

    private final MessageProcessor messageProcessor;

    public KafkaMessageConsumer(MessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
    }

    @Incoming("input-events")
    @Blocking
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @WithSpan(kind = SpanKind.CONSUMER, value = "KafkaMessageConsumer.onMessage")
    public CompletionStage<Void> onMessage(IncomingKafkaRecord<String, byte[]> kafkaRecord) {
        try {
            var entityId = kafkaRecord.getKey();
            var messageType = messageTypeFromHeader(kafkaRecord);
            Instant kafkaTimestamp = kafkaRecord.getTimestamp();

            Span.current().setAttribute(SPAN_ATTR_ENTITY_ID, entityId);
            Span.current().setAttribute(SPAN_ATTR_MESSAGE_TYPE, messageType);
            MDC.put(MDC_ENTITY_ID, entityId);
            MDC.put(MDC_MESSAGE_TYPE, messageType);
            for (int i = 0; i < MDC_CHURN_ENTRIES; i++) {
                var churnKey = "mdc.churn." + i;
                MDC.put(churnKey, entityId + ":" + i);
                if (i % 3 == 0) {
                    MDC.remove(churnKey);
                }
            }

            //            Log.infov("Consumed message type={0}, entity={1}", messageType, entityId);
            messageProcessor.process(entityId, kafkaRecord.getPayload(), kafkaTimestamp);
        } catch (RuntimeException e) {
            Log.errorv(e, "Error while consuming message, nacking");
            return kafkaRecord.nack(e);
        }

        return kafkaRecord.ack();
    }

    private static String messageTypeFromHeader(IncomingKafkaRecord<String, byte[]> kafkaRecord) {
        Header header = kafkaRecord.getHeaders().lastHeader(MESSAGE_TYPE_HEADER);
        if (header == null) {
            return "UNKNOWN";
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
