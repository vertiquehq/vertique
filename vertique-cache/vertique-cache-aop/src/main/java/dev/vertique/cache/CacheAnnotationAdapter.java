// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Translates cache annotations and method metadata into the programmatic cache API. */
@Singleton
final class CacheAnnotationAdapter {
    private final CacheBuilder builder;

    @Inject
    CacheAnnotationAdapter(CacheBuilder builder) {
        this.builder = builder;
    }

    CacheAnnotationAdapter(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this(CacheBuilder.forTesting(store, config, observers, Set.of()));
    }

    CacheAnnotationAdapter(
            CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> identityResolvers) {
        this(CacheBuilder.forTesting(store, config, observers, identityResolvers));
    }

    Cache<Object, Object> cacheable(MethodMetadata target, Cacheable annotation) {
        Type valueType = valueType(target);
        return builder.build(
                annotation.name(),
                valueType,
                annotation.mode(),
                annotation.ttlSeconds(),
                annotation.identity(),
                annotation.anonymous(),
                selector(annotation.key(), target),
                target.returnType() != Future.class,
                joinPaths(annotation.key()));
    }

    Cache<Object, Object> eviction(MethodMetadata target, CacheEvict annotation) {
        Cacheable cacheable = target.findAnnotation(Cacheable.class).orElse(null);
        Type valueType = cacheable == null ? Object.class : valueType(target);
        Function<Object, String> selector = selector(annotation.key(), target);
        return builder.buildUnregistered(
                annotation.name(),
                valueType,
                cacheable == null ? CacheMode.DEFAULT : cacheable.mode(),
                cacheable == null ? -1 : cacheable.ttlSeconds(),
                cacheable == null ? CacheIdentity.NONE : cacheable.identity(),
                cacheable == null ? AnonymousCachePolicy.BYPASS : cacheable.anonymous(),
                selector,
                false,
                annotation.key().length == 0 ? null : joinPaths(annotation.key()));
    }

    List<PreparedEviction> evictions(MethodMetadata target, CacheEvict[] declarations) {
        List<PreparedEviction> result = new ArrayList<>();
        for (CacheEvict declaration : declarations) {
            result.add(new PreparedEviction(eviction(target, declaration), declaration.clear()));
        }
        return List.copyOf(result);
    }

    private static Function<Object, String> selector(String[] paths, MethodMetadata target) {
        if (paths.length == 0) {
            // An explicitly empty declaration is a value-independent constant key.
            return ignored -> CacheBuilder.CONSTANT_SELECTOR;
        }
        String[] declared = paths.clone();
        return input -> {
            Object[] values = MethodMetadataKeyResolver.resolve(declared, target, (Object[]) input);
            return CacheBuilder.render(CacheKey.of(values[0], java.util.Arrays.copyOfRange(values, 1, values.length)));
        };
    }

    private static String joinPaths(String[] paths) {
        return String.join(",", paths);
    }

    private static Type valueType(MethodMetadata target) {
        Type returnType = target.genericReturnType();
        if (returnType instanceof ParameterizedType parameterized && target.returnType() == Future.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }

    record PreparedEviction(Cache<Object, Object> cache, boolean clear) {}
}
