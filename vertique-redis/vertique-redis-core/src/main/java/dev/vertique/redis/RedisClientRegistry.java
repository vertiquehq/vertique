// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisClientType;
import io.vertx.redis.client.RedisCluster;
import io.vertx.redis.client.RedisClusterConnectOptions;
import io.vertx.redis.client.RedisOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Application-scoped lazy Redis client registry keyed by typed connection profile name. */
@Singleton
public final class RedisClientRegistry {
    private final Vertx vertx;
    private final RedisClusterClientFactory clusterClientFactory;
    private final Map<String, RedisConnectionConfig> profiles;
    private final Map<String, Redis> clients = new ConcurrentHashMap<>();
    private final Map<String, Redis> clusterClients = new ConcurrentHashMap<>();
    private final Map<String, RedisPrimaryOperations> primaryOperations = new ConcurrentHashMap<>();
    private final Map<String, RedisClusterClient> topologyClients = new ConcurrentHashMap<>();
    private final Map<String, RedisTopologyOperations> topologyOperations = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private boolean closeStarted;
    private Future<Void> closeFuture;

    /**
     * Constructs the application-scoped registry from startup-resolved profiles.
     *
     * @param vertx the host-owned Vert.x instance used to create Redis clients
     * @param config the validated Redis connection profiles
     */
    @Inject
    public RedisClientRegistry(Vertx vertx, RedisConnectionsConfig config) {
        this(vertx, config, Redis::createClusterClient);
    }

    /**
     * Constructs the registry with an internal cluster-client creation seam.
     *
     * <p>The seam is package-private so production wiring keeps the public constructor while
     * same-package tests can observe the exact options and connect-options supplier passed to
     * Vert.x without creating a real client implementation.
     *
     * @param vertx the host-owned Vert.x instance used to create Redis clients
     * @param config the validated Redis connection profiles
     * @param clusterClientFactory the factory used for cluster-capable clients
     */
    RedisClientRegistry(Vertx vertx, RedisConnectionsConfig config, RedisClusterClientFactory clusterClientFactory) {
        this.vertx = vertx;
        this.clusterClientFactory = clusterClientFactory;
        // The typed configuration has already resolved property sources by the time Dagger builds
        // this singleton. Keeping an immutable snapshot here makes credential rotation explicitly
        // restart-only and prevents a later configuration mutation from changing a live client.
        Map<String, RedisConnectionConfig> orderedProfiles = new LinkedHashMap<>();
        config.connections().forEach(profile -> orderedProfiles.put(profile.name(), profile));
        this.profiles = Collections.unmodifiableMap(orderedProfiles);
    }

    /**
     * Returns the lazily-created client for a named profile.
     *
     * @param profileName the validated profile name
     * @return the shared client for the profile
     * @throws IllegalArgumentException if the profile is unknown
     * @throws IllegalStateException if registry shutdown has started
     */
    public Redis client(String profileName) {
        synchronized (lifecycleLock) {
            if (closeStarted) {
                throw new IllegalStateException("Redis client registry is closed");
            }
            RedisConnectionConfig profile = profiles.get(profileName);
            if (profile == null) {
                throw new IllegalArgumentException("unknown Redis connection profile: " + profileName);
            }
            return clients.computeIfAbsent(profileName, ignored -> createClient(profile));
        }
    }

    /**
     * Returns primary-node operations backed by a registry-owned cluster client for a named profile.
     *
     * <p>This method creates one cluster-capable client for the profile and wraps it in one
     * cached seam. The registry remains responsible for the cluster client's lifecycle.
     *
     * @param profileName the validated profile name
     * @return primary-node operations backed by the shared cluster client
     * @throws IllegalArgumentException if the profile is unknown
     * @throws IllegalStateException if registry shutdown has started
     */
    public RedisPrimaryOperations primaryOperations(String profileName) {
        synchronized (lifecycleLock) {
            if (closeStarted) {
                throw new IllegalStateException("Redis client registry is closed");
            }
            RedisConnectionConfig profile = profiles.get(profileName);
            if (profile == null) {
                throw new IllegalArgumentException("unknown Redis connection profile: " + profileName);
            }
            return primaryOperations.computeIfAbsent(profileName, ignored -> {
                Redis clusterClient =
                        clusterClients.computeIfAbsent(profileName, ignoredProfile -> createClusterClient(profile));
                return new RedisPrimaryOperations(RedisCluster.create(clusterClient));
            });
        }
    }

    /**
     * Returns topology-aware maintenance operations backed by one registry-owned Lettuce client
     * for the named profile.
     *
     * <p>The Lettuce client and its cluster connection are both created lazily. Lettuce remains
     * confined to this maintenance adapter; request-path operations continue to use Vert.x Redis.
     *
     * @param profileName the validated profile name
     * @return topology-aware maintenance operations backed by the shared profile client
     * @throws IllegalArgumentException if the profile is unknown
     * @throws IllegalStateException if registry shutdown has started
     */
    public RedisTopologyOperations topologyOperations(String profileName) {
        synchronized (lifecycleLock) {
            if (closeStarted) {
                throw new IllegalStateException("Redis client registry is closed");
            }
            RedisConnectionConfig profile = profiles.get(profileName);
            if (profile == null) {
                throw new IllegalArgumentException("unknown Redis connection profile: " + profileName);
            }
            return topologyOperations.computeIfAbsent(profileName, ignored -> {
                RedisClusterClient client =
                        topologyClients.computeIfAbsent(profileName, ignoredProfile -> createTopologyClient(profile));
                return new LettuceRedisTopologyOperations(client);
            });
        }
    }

    /**
     * Closes created clients in validated profile order and settles once every close attempt has
     * completed.
     *
     * <p>The first invocation owns the asynchronous close sequence. Later invocations return the
     * same future, so a repeated application teardown cannot close a client twice. The host-owned
     * {@link Vertx} instance is deliberately not closed here.
     *
     * @return the shared ordered close future
     */
    public Future<Void> close() {
        synchronized (lifecycleLock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closeStarted = true;
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            Future<Void> sequence = Future.succeededFuture();
            for (String profileName : profiles.keySet()) {
                Redis client = clients.get(profileName);
                if (client != null) {
                    sequence = sequence.compose(ignored -> closeClient(client, firstFailure));
                }
                Redis clusterClient = clusterClients.get(profileName);
                if (clusterClient != null) {
                    sequence = sequence.compose(ignored -> closeClient(clusterClient, firstFailure));
                }
                RedisClusterClient topologyClient = topologyClients.get(profileName);
                if (topologyClient != null) {
                    sequence = sequence.compose(ignored -> closeTopologyClient(topologyClient, firstFailure));
                }
            }
            closeFuture = sequence.compose(ignored -> {
                Throwable failure = firstFailure.get();
                return failure == null ? Future.succeededFuture() : Future.failedFuture(failure);
            });
            return closeFuture;
        }
    }

    private Redis createClient(RedisConnectionConfig profile) {
        return Redis.createClient(vertx, createOptions(profile));
    }

    private Redis createClusterClient(RedisConnectionConfig profile) {
        RedisOptions options = createOptions(profile).setType(RedisClientType.CLUSTER);
        return clusterClientFactory.create(
                vertx, options, () -> Future.succeededFuture(copyClusterConnectOptions(options, profile.endpoints())));
    }

    private static RedisClusterClient createTopologyClient(RedisConnectionConfig profile) {
        List<RedisURI> endpoints = profile.endpoints().stream()
                .map(endpoint -> {
                    RedisURI uri = RedisURI.create(endpoint);
                    uri.setSsl(profile.tlsEnabled());
                    uri.setTimeout(Duration.ofMillis(profile.connectTimeoutMs()));
                    if (profile.username() != null) {
                        uri.setAuthentication(
                                profile.username(), profile.passwordSecret() == null ? "" : profile.passwordSecret());
                    } else if (profile.passwordSecret() != null) {
                        uri.setAuthentication(profile.passwordSecret());
                    }
                    return uri;
                })
                .toList();
        return RedisClusterClient.create(endpoints);
    }

    /**
     * Copies the connection settings used by the cluster-connect callback and applies the profile's
     * seed endpoints.
     *
     * @param options the cluster client options
     * @param endpoints the profile's cluster seed endpoints
     * @return the copied cluster-connect options
     */
    static RedisClusterConnectOptions copyClusterConnectOptions(RedisOptions options, List<String> endpoints) {
        return new RedisClusterConnectOptions(options).setEndpoints(endpoints);
    }

    private static RedisOptions createOptions(RedisConnectionConfig profile) {
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
        if (profile.passwordSecret() != null) {
            options.setPassword(profile.passwordSecret());
        }
        return options;
    }

    private static Future<Void> closeClient(Redis client, AtomicReference<Throwable> firstFailure) {
        try {
            return client.close().recover(cause -> {
                firstFailure.compareAndSet(null, cause);
                return Future.succeededFuture();
            });
        } catch (RuntimeException failure) {
            firstFailure.compareAndSet(null, failure);
            return Future.succeededFuture();
        }
    }

    private static Future<Void> closeTopologyClient(
            RedisClusterClient client, AtomicReference<Throwable> firstFailure) {
        try {
            Promise<Void> promise = Promise.promise();
            client.shutdownAsync(0, 0, TimeUnit.MILLISECONDS).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    firstFailure.compareAndSet(null, failure);
                }
                promise.complete();
            });
            return promise.future();
        } catch (RuntimeException failure) {
            firstFailure.compareAndSet(null, failure);
            return Future.succeededFuture();
        }
    }

    /**
     * Internal factory for the exact Vert.x cluster-client creation boundary.
     *
     * <p>Pool and network settings remain on the supplied {@link RedisOptions}; the supplier
     * provides the connect-level settings and endpoints used by cluster discovery.
     */
    @FunctionalInterface
    interface RedisClusterClientFactory {

        /**
         * Creates a cluster-capable Redis client.
         *
         * @param vertx the host-owned Vert.x instance
         * @param options the Redis client options
         * @param connectOptionsSupplier the lazy cluster connect-options supplier
         * @return the created Redis client
         */
        Redis create(
                Vertx vertx, RedisOptions options, Supplier<Future<RedisClusterConnectOptions>> connectOptionsSupplier);
    }
}
