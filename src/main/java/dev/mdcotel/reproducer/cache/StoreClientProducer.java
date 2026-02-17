package dev.mdcotel.reproducer.cache;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class StoreClientProducer {

    private final ReactiveRedisDataSource reactiveRedisDataSource;
    private final StorageConfiguration storageConfiguration;

    public StoreClientProducer(
            ReactiveRedisDataSource reactiveRedisDataSource, StorageConfiguration storageConfiguration) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.storageConfiguration = storageConfiguration;
    }

    @Produces
    @ApplicationScoped
    public RedisStoreClient redisStoreClient() {
        return new RedisStoreClient("reproducer", reactiveRedisDataSource, storageConfiguration);
    }
}
