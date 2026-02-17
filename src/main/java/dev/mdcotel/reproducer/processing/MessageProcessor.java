package dev.mdcotel.reproducer.processing;

import dev.mdcotel.reproducer.cache.EntityResultStore;
import dev.mdcotel.reproducer.cache.EntityStateStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class MessageProcessor {

    private final EntityStateStore stateStore;
    private final EntityResultStore resultStore;
    private final AtomicInteger processedCount = new AtomicInteger(0);

    public MessageProcessor(EntityStateStore stateStore, EntityResultStore resultStore) {
        this.stateStore = stateStore;
        this.resultStore = resultStore;
    }

    public void process(String entityId, byte[] payload, Instant timestamp) {
        for (int i = 0; i < 3; i++) {
            var scopedEntityId = entityId + ":" + i;
            var existingState = stateStore.find(scopedEntityId).orElse("initial");
            var newState = existingState + ":" + payload.length + ":" + i;
            stateStore.save(scopedEntityId, newState);

            var existingResult = resultStore.find(scopedEntityId).orElse("none");
            var newResult = computeResult(scopedEntityId, newState, existingResult);
            resultStore.save(scopedEntityId, newResult);
        }

        //        var currentCount = processedCount.incrementAndGet();
        //        var latency = Duration.between(timestamp, Instant.now());
        //        Log.infov(
        //                "Processed message #{0} entity={1} payloadBytes={2} latencyMs={3}",
        //                currentCount, entityId, payload.length, latency.toMillis());
    }

    public int processedCount() {
        return processedCount.get();
    }

    public void resetCount() {
        processedCount.set(0);
    }

    private String computeResult(String entityId, String state, String previousResult) {
        return entityId + ":" + state.hashCode() + ":" + previousResult.hashCode();
    }
}
