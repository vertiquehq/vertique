// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/** Executes every repeated eviction declaration after the business method succeeds. */
@Singleton
final class CacheEvictListAspect implements AspectProvider<CacheEvict.List> {
    private final CacheBuilder builder;

    @Inject
    CacheEvictListAspect(CacheBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, CacheEvict.List annotations) {
        List<CacheBuilder.PreparedEviction> preparedEvictions;
        try {
            preparedEvictions = builder.evictions(target, annotations.value());
        } catch (RuntimeException invalidDefinition) {
            // A malformed declaration is rejected by the cache processor. Keep generated
            // proxies fail-open for compatibility with manually constructed metadata.
            preparedEvictions = List.of();
        }
        List<CacheBuilder.PreparedEviction> evictions = List.copyOf(preparedEvictions);
        return invocation -> invocation.proceed().compose(result -> {
            List<Future<Boolean>> operations = new ArrayList<>();
            for (CacheBuilder.PreparedEviction eviction : evictions) {
                if (eviction.clear()) {
                    operations.add(eviction.cache().invalidateAll());
                } else {
                    operations.add(eviction.cache().invalidate(invocation.arguments()));
                }
            }
            return Future.all(operations).map(ignored -> result);
        });
    }
}
