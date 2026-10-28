// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.vertique.cache.config.ObjectCacheConfig;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Caffeine-backed {@link ObjectCache}. Entries are shared in-flight futures; the first caller for a missing
 * key runs the loader outside the map's atomic section and every caller receives its own bridged future.
 * The cache is bounded by {@link ObjectCacheConfig#maximumEntries()} and, when {@link ObjectCacheConfig#ttlSeconds()}
 * is positive, removes an entry that long after it was created, the meaning {@code ttlSeconds} has for the
 * {@code CacheStore} providers; only a size removal is reported as an eviction. It is independent of
 * {@code cache.enabled} and reads no {@code CacheConfig}. Misses, loads and size evictions are reported to the
 * observers as cache operation events under provider id {@code caffeine-object}; a time expiry, a hit and an
 * {@code invalidate} emit nothing. An entry expires from its creation, so a load that outlasts the expiry leaves no
 * entry for later callers to share.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class CaffeineObjectCache<K, V> implements ObjectCache<K, V> {
    static final String PROVIDER = "caffeine-object";

    private final String name;
    private final Predicate<Throwable> cacheFailure;
    private final Set<CacheObserver> observers;
    private final Cache<K, CompletableFuture<V>> entries;

    /**
     * Creates a by-reference object cache.
     *
     * @param name the cache name its events carry; not blank
     * @param config the entry bound and expiry
     * @param cacheFailure whether a failed load with the given error is cached as a failed future
     * @param observers the observers that receive cache events
     * @throws IllegalArgumentException if the name is blank
     * @throws NullPointerException if an argument is null
     */
    public CaffeineObjectCache(
            String name, ObjectCacheConfig config, Predicate<Throwable> cacheFailure, Set<CacheObserver> observers) {
        this(name, config, cacheFailure, observers, Ticker.systemTicker());
    }

    CaffeineObjectCache(
            String name,
            ObjectCacheConfig config,
            Predicate<Throwable> cacheFailure,
            Set<CacheObserver> observers,
            Ticker ticker) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(config, "config");
        this.cacheFailure = Objects.requireNonNull(cacheFailure, "cacheFailure");
        this.observers = Set.copyOf(Objects.requireNonNull(observers, "observers"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(config.maximumEntries());
        if (config.ttlSeconds() > 0) {
            builder.expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()));
        }
        this.entries = builder.ticker(ticker)
                .<K, CompletableFuture<V>>removalListener((key, value, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        emit(CacheOperation.EVICT, CacheOutcome.SIZE_EVICTED, Duration.ZERO);
                    }
                })
                .build();
    }

    @Override
    public Future<V> getOrLoad(K key, Supplier<Future<V>> loader) {
        Context caller = Vertx.currentContext();
        long start = System.nanoTime();
        CompletableFuture<V> fresh = new CompletableFuture<>();
        CompletableFuture<V> shared = entries.asMap().computeIfAbsent(key, ignored -> fresh);
        if (shared == fresh) {
            emit(CacheOperation.GET, CacheOutcome.MISS, elapsedSince(start));
            load(key, fresh, loader);
        }
        return caller == null ? Future.fromCompletionStage(shared) : Future.fromCompletionStage(shared, caller);
    }

    private void load(K key, CompletableFuture<V> target, Supplier<Future<V>> loader) {
        long start = System.nanoTime();
        Future<V> loading;
        try {
            loading = loader.get();
            if (loading == null) {
                loading = Future.failedFuture(new NullPointerException("loader returned null"));
            }
        } catch (Throwable failure) {
            loading = Future.failedFuture(failure);
        }
        loading.onComplete(result -> {
            Duration took = elapsedSince(start);
            if (result.succeeded()) {
                target.complete(result.result());
                emit(CacheOperation.LOAD, CacheOutcome.SUCCESS, took);
            } else {
                Throwable cause = result.cause();
                if (!accepts(cause)) {
                    entries.asMap().remove(key, target);
                }
                target.completeExceptionally(cause);
                emit(CacheOperation.LOAD, CacheOutcome.ERROR, took);
            }
        });
    }

    private boolean accepts(Throwable cause) {
        try {
            return cacheFailure.test(cause);
        } catch (RuntimeException predicateFailure) {
            return false;
        }
    }

    @Override
    public Optional<V> getIfPresent(K key) {
        CompletableFuture<V> entry = entries.getIfPresent(key);
        if (entry == null || !entry.isDone() || entry.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.getNow(null));
    }

    @Override
    public void invalidate(K key) {
        entries.invalidate(key);
    }

    @Override
    public long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    @Override
    public String name() {
        return name;
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
    }

    private void emit(CacheOperation operation, CacheOutcome outcome, Duration elapsed) {
        CacheOperationCompleted event = new CacheOperationCompleted(operation, PROVIDER, name, outcome, elapsed);
        for (CacheObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (RuntimeException ignored) {
                // observers never change cache behavior
            }
        }
    }
}
