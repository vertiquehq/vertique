// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;

/** Annotation adapter that delegates invalidation to a prepared {@link Cache}. */
@Singleton
public final class CacheEvictAspect implements AspectProvider<CacheEvict> {
    private final CacheAnnotationAdapter adapter;

    @Inject
    CacheEvictAspect(CacheAnnotationAdapter adapter) {
        this.adapter = adapter;
    }

    /** Direct construction hook for isolated adapter tests. */
    public CacheEvictAspect(
            CacheStore store, dev.vertique.cache.config.CacheConfig config, Set<CacheObserver> observers) {
        this(new CacheAnnotationAdapter(store, config, observers));
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, CacheEvict annotation) {
        List<CacheAnnotationAdapter.PreparedEviction> evictions;
        try {
            evictions = List.of(new CacheAnnotationAdapter.PreparedEviction(
                    adapter.eviction(target, annotation), annotation.clear()));
        } catch (RuntimeException invalidDefinition) {
            return invocation -> invocation.proceed();
        }
        return invocation -> invocation.proceed().compose(result -> {
            List<Future<Boolean>> operations = evictions.stream()
                    .map(eviction -> eviction.clear()
                            ? eviction.cache().invalidateAll()
                            : eviction.cache().invalidate(invocation.arguments()))
                    .toList();
            return Future.all(operations).map(ignored -> result);
        });
    }
}
