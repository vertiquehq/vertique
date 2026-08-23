// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Application-scoped lazy Redis client registry keyed by typed connection profile name. */
@Singleton
public final class RedisClientRegistry {
    private final Vertx vertx;
    private final Map<String, RedisConnectionConfig> profiles;
    private final Map<String, Redis> clients = new ConcurrentHashMap<>();

    @Inject
    public RedisClientRegistry(Vertx vertx, RedisConnectionsConfig config) {
        this.vertx = vertx;
        this.profiles = config.connections().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(RedisConnectionConfig::name, value -> value));
    }

    /** Returns the lazily-created client for a named profile. */
    public Redis client(String profileName) {
        RedisConnectionConfig profile = profiles.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException("unknown Redis connection profile: " + profileName);
        }
        return clients.computeIfAbsent(profileName, ignored -> {
            RedisOptions options = new RedisOptions()
                    .setEndpoints(profile.endpoints())
                    .setNetClientOptions(new NetClientOptions()
                            .setSsl(profile.tlsEnabled())
                            .setConnectTimeout(Math.toIntExact(profile.connectTimeoutMs())))
                    .setMaxPoolSize(profile.maxPoolSize())
                    .setMaxPoolWaiting(profile.maxPoolWaiting());
            if (profile.username() != null) {
                options.setUser(profile.username());
            }
            return Redis.createClient(vertx, options);
        });
    }

    /** Closes all created clients and settles once every close attempt has completed. */
    public Future<Void> close() {
        return Future.all(clients.values().stream().map(Redis::close).toList()).mapEmpty();
    }
}
