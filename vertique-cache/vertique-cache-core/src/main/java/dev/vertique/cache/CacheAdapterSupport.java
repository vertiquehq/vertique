// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Runtime-owned construction seam for framework adapters — the annotation aspects and
 * generated-code integrations that build {@link Cache} handles from metadata rather
 * than through the fluent application {@link CacheBuilder} DSL. This is the only
 * supported cross-package entry into definition resolution; adapters supply logical
 * selector values and never encode, frame, or compose canonical keys.
 *
 * <p>This is framework-integration surface, not application API. Application code uses
 * {@link CacheBuilder}.
 */
@Singleton
public final class CacheAdapterSupport {
    private final CacheBuilder builder;

    @Inject
    public CacheAdapterSupport(CacheBuilder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /**
     * Creates a support instance over one fixed store for adapter tests and
     * provider-less composition.
     */
    public static CacheAdapterSupport forStore(
            CacheStore store,
            CacheConfig config,
            Set<CacheObserver> observers,
            Set<CacheIdentityResolver> identityResolvers) {
        return new CacheAdapterSupport(CacheBuilder.forTesting(store, config, observers, identityResolvers));
    }

    /** Builds and catalog-registers an adapter-declared cache. */
    public Cache<Object, Object> registered(AdapterDefinition definition) {
        return builder.build(
                definition.name(),
                definition.valueType(),
                definition.mode(),
                definition.ttlSeconds(),
                definition.identity(),
                definition.anonymous(),
                compose(definition.selector()),
                definition.asynchronousOnly(),
                joinedPaths(definition.selectorPaths()));
    }

    /** Builds an unregistered handle for an adapter operation that never stores values. */
    public Cache<Object, Object> unregistered(AdapterDefinition definition) {
        return builder.buildUnregistered(
                definition.name(),
                definition.valueType(),
                definition.mode(),
                definition.ttlSeconds(),
                definition.identity(),
                definition.anonymous(),
                compose(definition.selector()),
                definition.asynchronousOnly(),
                joinedPaths(definition.selectorPaths()));
    }

    /**
     * Adapter-declared cache definition. The selector maps invocation input to one
     * supported scalar or a {@link CacheKey}; a null selector declares a
     * value-independent constant operation key. {@code selectorPaths} carries the
     * declared ordered annotation paths for catalog comparison, or null when the
     * declaration carries none.
     */
    public record AdapterDefinition(
            String name,
            Type valueType,
            CacheMode mode,
            long ttlSeconds,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            Function<Object, Object> selector,
            boolean asynchronousOnly,
            List<String> selectorPaths) {
        public AdapterDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(valueType, "valueType");
            selectorPaths = selectorPaths == null ? null : List.copyOf(selectorPaths);
        }
    }

    private static Function<Object, String> compose(Function<Object, Object> selector) {
        if (selector == null) {
            return ignored -> CacheKey.CONSTANT_SELECTOR;
        }
        return input -> CacheKey.render(selector.apply(input));
    }

    private static String joinedPaths(List<String> selectorPaths) {
        return selectorPaths == null ? null : String.join(",", selectorPaths);
    }
}
