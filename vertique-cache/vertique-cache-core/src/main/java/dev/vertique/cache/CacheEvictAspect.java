// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
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
    private final CacheStoreResolver stores;
    private final CacheConfig config;
    private final Set<CacheObserver> observers;

    public CacheEvictAspect(CacheStore store, CacheConfig config) {
        this(CacheStoreResolver.fixed(store), config, Set.of());
    }

    public CacheEvictAspect(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this(CacheStoreResolver.fixed(store), config, observers);
    }

    @Inject
    public CacheEvictAspect(CacheStoreResolver stores, CacheConfig config, Set<CacheObserver> observers) {
        this.stores = stores;
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
            CacheStoreSelection selection;
            try {
                selection = stores.resolve(effectiveMode(annotation));
            } catch (RuntimeException unavailable) {
                return invocation.proceed();
            }
            return invocation.proceed().compose(result -> evict(invocation, target, region, annotation, selection)
                    .map(result));
        };
    }

    private Future<Void> evict(
            Invocation invocation,
            MethodMetadata target,
            CacheRegion region,
            CacheEvict annotation,
            CacheStoreSelection selection) {
        try {
            Future<Void> operation;
            if (annotation.clear()) {
                operation = selection.store().clear(region);
            } else if (!annotation.key().isBlank()) {
                String selector = CacheKeyRenderer.render(annotation.key(), target, invocation.arguments());
                operation = selection.store().evict(new CacheKey(region, "NONE", selector));
            } else {
                return Future.succeededFuture();
            }
            long startedAt = System.nanoTime();
            return operation
                    .onSuccess(ignored -> CacheObservationSupport.observe(
                            observers,
                            selection.providerId(),
                            annotation.clear() ? "clear" : "evict",
                            region,
                            "success",
                            startedAt))
                    .onFailure(ignored -> CacheObservationSupport.observe(
                            observers,
                            selection.providerId(),
                            annotation.clear() ? "clear" : "evict",
                            region,
                            "failure",
                            startedAt))
                    .recover(ignored -> Future.succeededFuture());
        } catch (Throwable failure) {
            CacheObservationSupport.observe(
                    observers,
                    selection.providerId(),
                    annotation.clear() ? "clear" : "evict",
                    region,
                    "failure",
                    System.nanoTime());
            return Future.succeededFuture();
        }
    }

    private CacheMode effectiveMode(CacheEvict annotation) {
        CacheEntryConfig entry = config.caches().get(annotation.name());
        return entry == null || entry.mode() == CacheMode.DEFAULT ? config.defaultMode() : entry.mode();
    }
}
