// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Caffeine-backed {@link ObjectCache}. Entries are shared in-flight futures; the first caller for a missing
 * key runs the loader outside the map's atomic section and every caller receives its own bridged future.
 */
final class CaffeineObjectCache<K, V> implements ObjectCache<K, V> {
    static final String PROVIDER = "caffeine-object";

    private final String name;
    private final Predicate<Throwable> cacheFailure;
    private final Set<CacheObserver> observers;
    private final Cache<K, CompletableFuture<V>> entries;

    CaffeineObjectCache(String name, long maxEntries, Predicate<Throwable> cacheFailure, Set<CacheObserver> observers) {
        this.name = name;
        this.cacheFailure = cacheFailure;
        this.observers = observers;
        this.entries = Caffeine.newBuilder()
                .maximumSize(maxEntries)
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
