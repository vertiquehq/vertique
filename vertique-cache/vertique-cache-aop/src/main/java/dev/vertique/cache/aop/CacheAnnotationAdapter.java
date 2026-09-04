// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import dev.vertique.aop.SelectorPaths;
import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.Cache;
import dev.vertique.cache.CacheAdapterSupport;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheKey;
import dev.vertique.cache.CacheMode;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Translates cache annotations and method metadata into the programmatic cache API. */
@Singleton
final class CacheAnnotationAdapter {
    private final CacheAdapterSupport support;

    @Inject
    CacheAnnotationAdapter(CacheAdapterSupport support) {
        this.support = support;
    }

    CacheAnnotationAdapter(CacheStore store, CacheConfig config, Set<CacheObserver> observers) {
        this(CacheAdapterSupport.forStore(store, config, observers, Set.of()));
    }

    CacheAnnotationAdapter(
            CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> identityResolvers) {
        this(CacheAdapterSupport.forStore(store, config, observers, identityResolvers));
    }

    Cache<Object, Object> cacheable(MethodMetadata target, Cacheable annotation) {
        return support.registered(new CacheAdapterSupport.AdapterDefinition(
                annotation.name(),
                valueType(target),
                annotation.mode(),
                annotation.ttlSeconds(),
                annotation.subject(),
                annotation.anonymous(),
                selector(annotation.key(), target),
                target.returnType() != Future.class,
                List.of(annotation.key())));
    }

    Cache<Object, Object> eviction(MethodMetadata target, CacheEvict annotation) {
        return support.unregistered(new CacheAdapterSupport.AdapterDefinition(
                annotation.name(),
                Object.class,
                CacheMode.DEFAULT,
                -1,
                CacheIdentity.NONE,
                AnonymousCachePolicy.BYPASS,
                selector(annotation.key(), target),
                false,
                annotation.key().length == 0 ? null : List.of(annotation.key())));
    }

    /**
     * Prepares one eviction. A clear resolves immediately; a standalone exact eviction
     * resolves its target policy lazily from the runtime catalog only when its ordered
     * selector paths match the registered target definition, so it can never silently
     * address a different identity bucket. Co-located cache annotations are invalid and
     * fail open when runtime metadata bypasses compile-time validation.
     */
    PreparedEviction prepared(MethodMetadata target, CacheEvict annotation) {
        if (target.findAnnotation(Cacheable.class).isPresent()) {
            throw new IllegalArgumentException("@Cacheable and @CacheEvict must be declared on different methods");
        }
        if (annotation.clear()) {
            return PreparedEviction.immediate(eviction(target, annotation), annotation.clear());
        }
        return PreparedEviction.lazy(
                support, annotation.name(), selector(annotation.key(), target), List.of(annotation.key()));
    }

    List<PreparedEviction> evictions(MethodMetadata target, CacheEvict[] declarations) {
        List<PreparedEviction> result = new ArrayList<>();
        for (CacheEvict declaration : declarations) {
            result.add(prepared(target, declaration));
        }
        return List.copyOf(result);
    }

    private static Function<Object, Object> selector(String[] paths, MethodMetadata target) {
        if (paths.length == 0) {
            // An explicitly empty declaration is a value-independent constant key.
            return null;
        }
        String[] declared = paths.clone();
        return input -> {
            Object[] values = SelectorPaths.resolve("cache key", declared, target, (Object[]) input);
            return values.length == 1
                    ? values[0]
                    : CacheKey.of(values[0], Arrays.copyOfRange(values, 1, values.length));
        };
    }

    private static Type valueType(MethodMetadata target) {
        Type returnType = target.genericReturnType();
        if (returnType instanceof ParameterizedType parameterized && target.returnType() == Future.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }

    /** One prepared eviction operation; construction-invalid declarations fail before this exists. */
    static final class PreparedEviction {
        private final boolean clear;
        private final CacheAdapterSupport support;
        private final String name;
        private final Function<Object, Object> selector;
        private final List<String> selectorPaths;
        private volatile Cache<Object, Object> resolved;

        private PreparedEviction(
                boolean clear,
                Cache<Object, Object> resolved,
                CacheAdapterSupport support,
                String name,
                Function<Object, Object> selector,
                List<String> selectorPaths) {
            this.clear = clear;
            this.resolved = resolved;
            this.support = support;
            this.name = name;
            this.selector = selector;
            this.selectorPaths = selectorPaths;
        }

        static PreparedEviction immediate(Cache<Object, Object> cache, boolean clear) {
            return new PreparedEviction(clear, cache, null, null, null, null);
        }

        static PreparedEviction lazy(
                CacheAdapterSupport support,
                String name,
                Function<Object, Object> selector,
                List<String> selectorPaths) {
            return new PreparedEviction(false, null, support, name, selector, List.copyOf(selectorPaths));
        }

        Future<Boolean> run(Object arguments) {
            Cache<Object, Object> cache = resolved;
            if (cache == null) {
                cache = support.evictionFor(name, selector, selectorPaths).orElse(null);
                if (cache == null) {
                    support.observeUnresolvedEviction(name);
                    return Future.succeededFuture(false);
                }
                resolved = cache;
            }
            return clear ? cache.invalidateAll() : cache.invalidate(arguments);
        }
    }
}
