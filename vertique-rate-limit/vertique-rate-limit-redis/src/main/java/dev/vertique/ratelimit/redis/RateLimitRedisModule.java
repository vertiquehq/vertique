// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitModeKey;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisConnectionModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import jakarta.inject.Singleton;

/**
 * Dagger contribution for the CLUSTERED Redis rate-limit backend
 * (contracts/rate-limit-runtime.md, "Redis integration contract"). Owns no client, pool,
 * credential, TLS, topology, or shutdown lifecycle of the shared Redis client itself beyond what
 * {@code vertique-redis-core}'s {@link RedisConnectionModule} already owns — this module reads
 * the shared client through {@link RedisClientRegistry} and never closes it itself (T009's own
 * scope note; {@code vertique-redis-core}'s {@code RedisClientShutdownStep} closes it exactly
 * once). It does, however, contribute an INFRA-phase ({@link
 * dev.vertique.core.lifecycle.LifecyclePhase#INFRA}) {@link RedisRateLimitFenceLifecycle} shutdown
 * step so the CLUSTERED rate-limit runtime closes — and fences any in-flight {@code
 * consume(...)} — <em>before</em> that shared client closes during reverse-order application
 * teardown (see {@link RedisRateLimitFenceLifecycle}'s own javadoc).
 *
 * <p>App composition installs this module alongside {@link RateLimitCoreModule} for any
 * application declaring a {@code CLUSTERED} policy (contracts/rate-limit-runtime.md, "Dagger
 * wiring").
 */
@Module(includes = {RateLimitCoreModule.class, RedisConnectionModule.class})
public abstract class RateLimitRedisModule {

    private static final String RATE_LIMIT = "rateLimit";
    private static final String REDIS = "redis";
    private static final String KEY_DERIVATION = "keyDerivation";
    private static final String SECRET = "secret";
    private static final String CONNECTION = "connection";
    private static final String NAMESPACE = "namespace";
    private static final String OPERATION_TIMEOUT_MS = "operationTimeoutMs";
    private static final String EXPIRATION_SLACK_MS = "expirationSlackMs";

    /** Prevents direct construction of the static binding module. */
    private RateLimitRedisModule() {}

    /**
     * Contributes the CLUSTERED {@link RateLimitBackend}, built from the shared client for {@code
     * rateLimit.redis.connection} (contracts/rate-limit-runtime.md, "Redis integration contract" —
     * the exact, frozen {@code Bucket4jVertx.casBasedBuilder(...)} construction chain, performed
     * only inside {@link Bucket4jRedisRateLimitBackend#redis}, never as a Dagger binding itself).
     * {@code rateLimit.redis.*}/{@code rateLimit.keyDerivation.secret} bounds are validated by
     * startup config validation upstream (T003/T004's ownership); this provider only translates an
     * already-valid configuration into the backend's construction arguments.
     *
     * @param config the raw application configuration
     * @param vertx application Vert.x instance, source of the operation-deadline timer
     * @param clients the shared, lazily-created Redis client registry
     * @return the CLUSTERED {@link RateLimitBackend}
     */
    @Provides
    @IntoMap
    @Singleton
    @RateLimitModeKey(RateLimitMode.CLUSTERED)
    static RateLimitBackend clusteredRateLimitBackend(
            @VertxConfig JsonObject config, Vertx vertx, RedisClientRegistry clients) {
        JsonObject rateLimit = JsonConfigPaths.navigateObject(config, RATE_LIMIT);
        JsonObject redis = JsonConfigPaths.navigateObject(rateLimit, REDIS);
        String secret =
                JsonConfigPaths.navigateObject(rateLimit, KEY_DERIVATION).getString(SECRET);
        RateLimitRedisConfig redisConfig = new RateLimitRedisConfig(
                redis.getString(CONNECTION),
                redis.getString(NAMESPACE),
                redis.getLong(OPERATION_TIMEOUT_MS, 0L),
                redis.getLong(EXPIRATION_SLACK_MS, 0L));
        Redis sharedRedis = clients.client(redisConfig.connection());
        return Bucket4jRedisRateLimitBackend.redis(
                sharedRedis,
                vertx,
                redisConfig.namespace(),
                secret,
                redisConfig.operationTimeoutMs(),
                redisConfig.expirationSlackMs());
    }

    /**
     * Contributes the {@link RedisRateLimitFenceLifecycle} shutdown step so the CLUSTERED
     * rate-limit runtime closes before the shared Redis client during reverse-order application
     * teardown (see that class's javadoc for the full ordering rationale).
     *
     * @param rateLimiters the application-scoped rate-limit runtime this step fences
     * @return the INFRA-phase shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep redisRateLimitFenceLifecycle(RateLimiters rateLimiters) {
        return new RedisRateLimitFenceLifecycle(rateLimiters);
    }
}
