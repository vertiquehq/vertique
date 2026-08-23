// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the provider-neutral cache API defaults and value boundaries. */
class CacheContractsTest {

    @Test
    @DisplayName("cache annotations expose the frozen defaults")
    void cacheAnnotationsExposeFrozenDefaults() throws NoSuchMethodException {
        var cacheable = Sample.class.getDeclaredMethod("cached").getAnnotation(Cacheable.class);

        assertEquals(CacheMode.DEFAULT, cacheable.mode());
        assertEquals(-1, cacheable.ttlSeconds());
        assertEquals(CacheIdentity.NONE, cacheable.identity());
        assertEquals(AnonymousCachePolicy.BYPASS, cacheable.anonymous());
    }

    @Test
    @DisplayName("cache keys use the canonical region and selector shape")
    void cacheKeysUseCanonicalShape() {
        var key = new CacheKey(new CacheRegion("cache", "profile", 1), "actor:USER:alice", "user-42");

        assertEquals("cache:v1:profile:actor:USER:alice:user-42", key.canonical());
    }

    @Test
    @DisplayName("cache regions reject invalid identity components")
    void cacheRegionsRejectInvalidIdentityComponents() {
        assertThrows(IllegalArgumentException.class, () -> new CacheRegion("cache", "", 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheRegion("cache:name", "profile", 1));
    }

    @Test
    @DisplayName("cache defaults use the approved operational limits")
    void cacheDefaultsUseApprovedLimits() {
        var config = CacheConfig.defaults();

        assertEquals(CacheMode.LOCAL, config.defaultMode());
        assertEquals(60, config.defaultTtlSeconds());
        assertEquals(1_024, config.maxKeyBytes());
        assertEquals(100, config.backendTimeoutMs());
    }

    @Test
    @DisplayName("cache configuration rejects an entry TTL above the configured maximum")
    void cacheConfigurationRejectsEntryTtlAboveMaximum() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheConfig(
                        true,
                        CacheMode.LOCAL,
                        60,
                        120,
                        1_024,
                        1_048_576,
                        10_000,
                        100,
                        Map.of("profile", new CacheEntryConfig(CacheMode.LOCAL, 121))));
    }

    @Test
    @DisplayName("key templates percent-encode scalars and resolve named record properties")
    void keyTemplatesRenderBoundedScalarSelectors() {
        MethodMetadata metadata = metadata("user");

        assertEquals(
                "user/%C3%85sa/true",
                CacheKeyRenderer.render("user/{user.name}/{0.active}", metadata, new Object[] {new User("Åsa", true)}));
    }

    @Test
    @DisplayName("key templates reject unmatched braces and object terminals")
    void keyTemplatesRejectUnsupportedSelectors() {
        MethodMetadata metadata = metadata("user");

        assertThrows(
                IllegalArgumentException.class,
                () -> CacheKeyRenderer.render("{user.missing}", metadata, new Object[] {new User("Åsa", true)}));
        assertThrows(
                IllegalArgumentException.class,
                () -> CacheKeyRenderer.render("{user", metadata, new Object[] {new User("Åsa", true)}));
    }

    private static MethodMetadata metadata(String parameterName) {
        ParameterMetadata parameter = new ParameterMetadata() {
            @Override
            public int index() {
                return 0;
            }

            @Override
            public String name() {
                return parameterName;
            }

            @Override
            public Class<?> type() {
                return User.class;
            }

            @Override
            public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> type) {
                return Optional.empty();
            }

            @Override
            public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> type) {
                return false;
            }

            @Override
            public Type genericType() {
                return User.class;
            }
        };
        return new MethodMetadata() {
            @Override
            public String name() {
                return "cached";
            }

            @Override
            public Class<?> declaringType() {
                return Sample.class;
            }

            @Override
            public Class<?> returnType() {
                return Object.class;
            }

            @Override
            public Class<?>[] parameterTypes() {
                return new Class<?>[] {User.class};
            }

            @Override
            public List<ParameterMetadata> parameters() {
                return List.of(parameter);
            }

            @Override
            public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> type) {
                return Optional.empty();
            }

            @Override
            public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> type) {
                return false;
            }

            @Override
            public Type genericReturnType() {
                return Object.class;
            }

            @Override
            public Method asMethod() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record User(String name, boolean active) {}

    static final class Sample {
        @Cacheable(name = "profile", key = "{0}")
        Object cached() {
            return null;
        }
    }
}
