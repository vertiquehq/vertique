// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/** Provider-neutral interceptor that performs fail-open invalidation after a successful result. */
@Singleton
public final class CacheEvictAspect implements AspectProvider<CacheEvict> {
    private final CacheStore store;
    private final CacheConfig config;
    private final Set<CacheObserver> observers;

    public CacheEvictAspect(CacheStore store, CacheConfig config) {
        this(store, config, Set.of());
    }

    @Inject
    public CacheEvictAspect(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this.store = store;
        this.config = config;
        this.observers = Set.copyOf(observers);
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, CacheEvict annotation) {
        CacheRegion region = new CacheRegion("cache", annotation.name(), 1);
        return invocation -> {
            if (!config.enabled()) {
                return invocation.proceed();
            }
            return invocation.proceed().compose(result -> evict(invocation, target, region, annotation)
                    .map(result));
        };
    }

    private Future<Void> evict(
            Invocation invocation, MethodMetadata target, CacheRegion region, CacheEvict annotation) {
        try {
            Future<Void> operation;
            if (annotation.clear()) {
                operation = store.clear(region);
            } else if (!annotation.key().isBlank()) {
                String selector = CacheKeyRenderer.render(annotation.key(), target, invocation.arguments());
                operation = store.evict(new CacheKey(region, "NONE", selector));
            } else {
                return Future.succeededFuture();
            }
            long startedAt = System.nanoTime();
            return operation
                    .onSuccess(ignored -> CacheObservationSupport.observe(
                            observers, annotation.clear() ? "clear" : "evict", region, "success", startedAt))
                    .onFailure(ignored -> CacheObservationSupport.observe(
                            observers, annotation.clear() ? "clear" : "evict", region, "failure", startedAt))
                    .recover(ignored -> Future.succeededFuture());
        } catch (Throwable failure) {
            CacheObservationSupport.observe(
                    observers, annotation.clear() ? "clear" : "evict", region, "failure", System.nanoTime());
            return Future.succeededFuture();
        }
    }
}
