package dev.mdcotel.reproducer.cache;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class EntityStateStore {

    private final RedisStoreClient cache;

    public EntityStateStore(RedisStoreClient cache) {
        this.cache = cache;
    }

    @WithSpan(value = "EntityStateStore.find")
    public Optional<String> find(String entityId) {
        return Optional.ofNullable(cache.get(entityId));
    }

    @WithSpan(value = "EntityStateStore.save")
    public void save(String entityId, String state) {
        cache.save(entityId, state);
    }
}
