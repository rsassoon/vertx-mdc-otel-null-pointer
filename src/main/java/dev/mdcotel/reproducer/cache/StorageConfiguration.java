package dev.mdcotel.reproducer.cache;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

@ConfigMapping(prefix = "reproducer.redis")
public interface StorageConfiguration {
    Duration timeout();

    Duration expiration();

    Retry retry();

    interface Retry {
        Duration backoffInitial();

        Duration backoffMax();

        int maxRetries();
    }
}
