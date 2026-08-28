// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheMode;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reflection-free generated declaration contributed by the cache annotation processor. */
public interface GeneratedCacheMetadata {
    OperationId operationId();

    boolean synchronous();

    Optional<CacheableDeclaration> cacheable();

    List<EvictionDeclaration> evictions();

    record OperationId(String declaringBinaryName, String methodName, List<String> erasedParameterTypeNames) {
        public OperationId {
            declaringBinaryName = boundedText(declaringBinaryName, "declaringBinaryName", 65_535);
            methodName = boundedText(methodName, "methodName", 65_535);
            erasedParameterTypeNames =
                    List.copyOf(Objects.requireNonNull(erasedParameterTypeNames, "erasedParameterTypeNames"));
            if (erasedParameterTypeNames.size() > 255) {
                throw new IllegalArgumentException("erasedParameterTypeNames exceeds 255 entries");
            }
            erasedParameterTypeNames = erasedParameterTypeNames.stream()
                    .map(value -> boundedText(value, "parameterTypeName", 65_535))
                    .toList();
        }
    }

    record CacheableDeclaration(
            String cacheName,
            Type valueType,
            CacheMode mode,
            long ttlSeconds,
            CacheIdentity identity,
            AnonymousCachePolicy anonymous,
            Selector selector) {
        public CacheableDeclaration {
            cacheName = Objects.requireNonNull(cacheName, "cacheName");
            valueType = Objects.requireNonNull(valueType, "valueType");
            mode = Objects.requireNonNull(mode, "mode");
            identity = Objects.requireNonNull(identity, "identity");
            anonymous = Objects.requireNonNull(anonymous, "anonymous");
            selector = Objects.requireNonNull(selector, "selector");
            if (ttlSeconds < -1) throw new IllegalArgumentException("ttlSeconds must be -1 or non-negative");
        }
    }

    record EvictionDeclaration(String cacheName, Optional<Selector> selector) {
        public EvictionDeclaration {
            cacheName = Objects.requireNonNull(cacheName, "cacheName");
            selector = Objects.requireNonNull(selector, "selector");
        }
    }

    record Selector(String normalizedLayout, List<SelectorComponent> components) {
        public Selector {
            normalizedLayout = Objects.requireNonNull(normalizedLayout, "normalizedLayout");
            components = List.copyOf(Objects.requireNonNull(components, "components"));
        }
    }

    record SelectorComponent(Class<?> declaredType, String accessorPath, ArgumentSelector accessor) {
        public SelectorComponent {
            declaredType = Objects.requireNonNull(declaredType, "declaredType");
            accessorPath = boundedText(accessorPath, "accessorPath", 256);
            accessor = Objects.requireNonNull(accessor, "accessor");
        }
    }

    @FunctionalInterface
    interface ArgumentSelector {
        Object select(Object[] invocationArguments);
    }

    private static String boundedText(String value, String field, int maxBytes) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " is blank or exceeds " + maxBytes + " UTF-8 bytes");
        }
        return value;
    }
}
