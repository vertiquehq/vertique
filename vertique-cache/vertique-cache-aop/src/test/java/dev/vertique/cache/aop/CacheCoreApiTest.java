// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.MapKey;
import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves that cache contracts remain provider-neutral and Dagger-composable. */
class CacheCoreApiTest {

    private static final List<Class<?>> PUBLIC_CONTRACTS = List.of(
            Cacheable.class,
            CacheEvict.class,
            CacheMode.class,
            CacheIdentity.class,
            AnonymousCachePolicy.class,
            CacheRegion.class,
            CacheStore.class,
            CacheConfig.class,
            CacheEntryConfig.class,
            CacheObserver.class,
            CacheObservation.class,
            CacheIdentityResolver.class);

    @Test
    @DisplayName("public cache contracts are provider-neutral")
    void publicContractsAreProviderNeutral() {
        for (Class<?> contract : PUBLIC_CONTRACTS) {
            assertProviderNeutral(contract);
        }

        assertEquals(
                List.of(
                        "enabled",
                        "defaultMode",
                        "defaultTtlSeconds",
                        "maxTtlSeconds",
                        "jsonProfile",
                        "maxKeyBytes",
                        "maxValueBytes",
                        "maximumEntries",
                        "backendTimeoutMs",
                        "caches"),
                Arrays.stream(CacheConfig.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
        assertEquals(
                List.of("mode", "ttlSeconds", "jsonProfile"),
                Arrays.stream(CacheEntryConfig.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
        Class<?> modeKey = loadModeKey();
        assertTrue(modeKey.isAnnotationPresent(MapKey.class), "CacheModeKey must be a Dagger map key");
        assertEquals(CacheMode.class, modeKey.getDeclaredMethods()[0].getReturnType());
        assertTrue(hasObserverSetSeam(), "CacheCoreModule must preserve the provider-neutral observer seam");
        assertTrue(
                CacheIdentityResolver.class.isInterface(),
                "CacheIdentityResolver must remain a provider-neutral extension point");
    }

    private static void assertProviderNeutral(Class<?> contract) {
        assertFalse(hasForbiddenName(contract), () -> contract.getName() + " exposes a provider-specific type");
        for (Method method : contract.getDeclaredMethods()) {
            assertFalse(
                    hasForbiddenName(method.getGenericReturnType()),
                    () -> method + " exposes a provider-specific return type");
            for (Type parameter : method.getGenericParameterTypes()) {
                assertFalse(hasForbiddenName(parameter), () -> method + " exposes a provider-specific parameter type");
            }
        }
    }

    private static boolean hasForbiddenName(Type type) {
        String name = type.getTypeName().toLowerCase();
        return name.contains("redis")
                || name.contains("caffeine")
                || name.contains("jackson")
                || name.contains("buffer");
    }

    private static boolean hasForbiddenName(Class<?> type) {
        return hasForbiddenName((Type) type);
    }

    private static boolean hasObserverSetSeam() {
        return CacheObserver.class.isInterface()
                && Arrays.stream(CacheObserver.class.getDeclaredMethods())
                        .anyMatch(method -> method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == CacheObservation.class);
    }

    private static Class<?> loadModeKey() {
        try {
            return Class.forName("dev.vertique.cache.CacheModeKey");
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("CacheModeKey is missing", exception);
        }
    }
}
