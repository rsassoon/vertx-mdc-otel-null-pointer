package dev.mdcotel.reproducer.cache;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class EntityResultStore {

    private final RedisStoreClient cache;

    public EntityResultStore(RedisStoreClient cache) {
        this.cache = cache;
    }

    @WithSpan(value = "EntityResultStore.find")
    public Optional<String> find(String entityId) {
        return Optional.ofNullable(cache.get(entityId));
    }

    @WithSpan(value = "EntityResultStore.save")
    public void save(String entityId, String result) {
        cache.save(entityId, result);
    }
}
