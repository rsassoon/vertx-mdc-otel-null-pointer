package dev.mdcotel.reproducer.cache;

import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;

public class RedisStoreClient {

    private final String keyPrefix;
    private final ReactiveValueCommands<String, String> values;
    private final StorageConfiguration config;

    public RedisStoreClient(String keyPrefix, ReactiveRedisDataSource reactive, StorageConfiguration config) {
        this.keyPrefix = keyPrefix;
        this.values = reactive.value(String.class);
        this.config = config;
    }

    public void save(String entityId, String value) {
        var key = buildKey(entityId);
        Uni<Void> operation = Uni.createFrom()
                .deferred(() -> values.setex(key, config.expiration().toSeconds(), value));
        executeWithRetryAndTimeout(operation, "save");
    }

    public String get(String entityId) {
        var key = buildKey(entityId);
        Uni<String> operation = Uni.createFrom().deferred(() -> values.get(key));
        return executeWithRetryAndTimeout(operation, "get");
    }

    private <U> U executeWithRetryAndTimeout(Uni<U> operation, String operationName) {
        try {
            return operation
                    .onFailure()
                    .retry()
                    .withBackOff(config.retry().backoffInitial(), config.retry().backoffMax())
                    .atMost(config.retry().maxRetries())
                    .ifNoItem()
                    .after(config.timeout())
                    .fail()
                    .await()
                    .indefinitely();
        } catch (RuntimeException e) {
            Log.errorv(e, "Redis {0} failed", operationName);
            throw e;
        }
    }

    private String buildKey(String entityId) {
        return keyPrefix + ":" + entityId;
    }
}
