// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/** Dagger wiring for shared, typed, lazy Redis connection profiles. */
@Module
public final class RedisConnectionModule {
    @Provides
    @Singleton
    static RedisConnectionsConfig redisConnectionsConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(config, "redis.connections");
        return new RedisConnectionsConfig(parser.parseKeyedObject(section, "name", RedisConnectionConfig.class));
    }

    @Provides
    @Singleton
    static RedisClientRegistry redisClientRegistry(Vertx vertx, RedisConnectionsConfig config) {
        return new RedisClientRegistry(vertx, config);
    }

    /**
     * Contributes the provider-owned Redis client shutdown step to application teardown.
     *
     * @param step the shared Redis client shutdown step
     * @return the step as an application shutdown contribution
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationShutdownStep redisClientShutdownStep(RedisClientShutdownStep step) {
        return step;
    }
}
