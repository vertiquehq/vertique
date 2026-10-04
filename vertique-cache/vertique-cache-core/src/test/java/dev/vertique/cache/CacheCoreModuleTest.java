// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring test for {@link CacheCoreModule}: configuration binding, the provider map keys, the
 * observer multibinding, and the optional identity-resolver override, through real Dagger graphs.
 */
class CacheCoreModuleTest {

    private static final PrincipalRef ALICE = new PrincipalRef(PrincipalType.USER, "alice", Map.of());

    // --- configuration binding ---

    @Test
    @DisplayName("an absent cache section yields CacheConfig.defaults()")
    void absentSectionYieldsDefaults() {
        CacheConfig config = component(new JsonObject()).cacheConfig();

        assertEquals(CacheConfig.defaults(), config);
        assertEquals("system", config.jsonProfile());
    }

    @Test
    @DisplayName("an empty cache section yields CacheConfig.defaults()")
    void emptySectionYieldsDefaults() {
        CacheConfig config =
                component(new JsonObject().put("cache", new JsonObject())).cacheConfig();

        assertEquals(CacheConfig.defaults(), config);
    }

    @Test
    @DisplayName("a complete cache section binds global and per-cache keys, with independent jsonProfile")
    void completeSectionBindsAllKeys() {
        JsonObject root = new JsonObject()
                .put("json", new JsonObject().put("jsonProfile", "edge"))
                .put(
                        "cache",
                        fullSection()
                                .put(
                                        "caches",
                                        new JsonObject()
                                                .put(
                                                        "orders",
                                                        new JsonObject()
                                                                .put("mode", "CLUSTERED")
                                                                .put("ttlSeconds", 120)
                                                                .put("jsonProfile", "orders-profile"))
                                                .put(
                                                        "inherits",
                                                        new JsonObject()
                                                                .put("mode", "DEFAULT")
                                                                .put("ttlSeconds", -1))));

        CacheConfig config = component(root).cacheConfig();

        assertEquals(false, config.enabled());
        assertEquals(CacheMode.CLUSTERED, config.defaultMode());
        assertEquals(30, config.defaultTtlSeconds());
        assertEquals(600, config.maxTtlSeconds());
        assertEquals("system", config.jsonProfile(), "cache.jsonProfile does not follow json.jsonProfile");
        assertEquals(256, config.maxKeyBytes());
        assertEquals(2_048, config.maxValueBytes());
        assertEquals(50, config.maximumEntries());
        assertEquals(250, config.backendTimeoutMs());
        assertEquals(CacheMode.CLUSTERED, config.caches().get("orders").mode());
        assertEquals(120, config.caches().get("orders").ttlSeconds());
        assertEquals("orders-profile", config.caches().get("orders").jsonProfile());
        assertEquals(CacheMode.DEFAULT, config.caches().get("inherits").mode());
        assertEquals(-1, config.caches().get("inherits").ttlSeconds());
        assertNull(config.caches().get("inherits").jsonProfile());
    }

    @Test
    @DisplayName("an omitted per-cache ttlSeconds binds as 0 (no time expiry), not as inherit")
    void omittedEntryTtlBindsAsZero() {
        JsonObject root = new JsonObject()
                .put(
                        "cache",
                        fullSection()
                                .put("caches", new JsonObject().put("orders", new JsonObject().put("mode", "LOCAL"))));

        assertEquals(0, component(root).cacheConfig().caches().get("orders").ttlSeconds());
    }

    @Test
    @DisplayName("a non-empty section is bound as a whole record; omitted global keys are not defaulted")
    void partialSectionIsNotDefaulted() {
        JsonObject root = new JsonObject().put("cache", new JsonObject().put("enabled", false));

        assertThrows(ConfigurationException.class, () -> component(root).cacheConfig());
    }

    @Test
    @DisplayName("a non-empty section must also carry the caches object, even when empty")
    void cachesObjectIsRequiredInANonEmptySection() {
        JsonObject withoutCaches = fullSection();
        withoutCaches.remove("caches");
        JsonObject root = new JsonObject().put("cache", withoutCaches);

        assertThrows(ConfigurationException.class, () -> component(root).cacheConfig());
    }

    @Test
    @DisplayName("an out-of-range value in a complete section fails binding")
    void invalidValuesFailBinding() {
        JsonObject root = new JsonObject().put("cache", fullSection().put("backendTimeoutMs", 10_001));

        assertThrows(ConfigurationException.class, () -> component(root).cacheConfig());
    }

    @Test
    @DisplayName("a per-cache TTL above maxTtlSeconds fails binding")
    void perCacheTtlAboveMaxFailsBinding() {
        JsonObject root = new JsonObject()
                .put(
                        "cache",
                        fullSection()
                                .put(
                                        "caches",
                                        new JsonObject()
                                                .put(
                                                        "orders",
                                                        new JsonObject()
                                                                .put("mode", "LOCAL")
                                                                .put("ttlSeconds", 601))));

        assertThrows(ConfigurationException.class, () -> component(root).cacheConfig());
    }

    // --- provider and observer multibindings ---

    @Test
    @DisplayName("provider map keys wire a store into the built CacheBuilder")
    void providerBindingsReachTheBuilder() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        TestComponent component = component(new JsonObject(), store, new CopyOnWriteArrayList<>());
        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.NONE)
                .ttl(Duration.ofSeconds(30))
                .build();

        assertEquals("loaded", await(cache.get("k", ignored -> Future.succeededFuture("loaded"))));
        assertEquals("loaded", await(cache.get("k", ignored -> Future.succeededFuture("unexpected"))));

        assertEquals(1, store.putCalls);
        assertEquals(Duration.ofSeconds(30), store.lastTtl);
    }

    @Test
    @DisplayName("CacheBuilder is a singleton and CacheAdapterSupport is built over it")
    void builderIsSingleton() {
        TestComponent component = component(new JsonObject());

        assertSame(component.cacheBuilder(), component.cacheBuilder());
        assertNotNull(component.adapterSupport());
    }

    @Test
    @DisplayName("contributed observers receive the sealed event vocabulary")
    void observersReceiveEvents() {
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        TestComponent component =
                component(new JsonObject(), new ProgrammaticCacheTestFixtures.RecordingStore(), events);
        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.NONE)
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("loaded")));

        assertEquals(1, component.observers().size());
        assertTrue(events.stream()
                .filter(CacheOperationCompleted.class::isInstance)
                .map(CacheOperationCompleted.class::cast)
                .anyMatch(event -> event.outcome() == CacheOutcome.MISS));
    }

    @Test
    @DisplayName("without a provider the multibinds are empty and building a cache names the missing mode")
    void missingProviderFailsAtBuild() {
        NoProviderComponent component = DaggerCacheCoreModuleTest_NoProviderComponent.builder()
                .configModule(new ConfigModule(new JsonObject()))
                .build();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.NONE)
                .build());

        assertTrue(failure.getMessage().contains("LOCAL"));
    }

    @Test
    @DisplayName("a disabled cache builds without any provider installed")
    void disabledCacheNeedsNoProvider() {
        NoProviderComponent component = DaggerCacheCoreModuleTest_NoProviderComponent.builder()
                .configModule(new ConfigModule(new JsonObject().put("cache", fullSection())))
                .build();

        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.NONE)
                .build();

        assertEquals("loaded", await(cache.get("k", ignored -> Future.succeededFuture("loaded"))));
    }

    // --- identity resolver ---

    @Test
    @DisplayName("default wiring resolves identity from the framework context and fails closed without one")
    void defaultResolverFailsClosedOutsideAContext() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        TestComponent component = component(new JsonObject(), store, new CopyOnWriteArrayList<>());
        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.ACTOR)
                .build();
        int[] loads = {0};

        await(cache.get("k", ignored -> {
            loads[0]++;
            return Future.succeededFuture("loaded");
        }));
        await(cache.get("k", ignored -> {
            loads[0]++;
            return Future.succeededFuture("loaded");
        }));

        assertEquals(2, loads[0], "no SecurityContext is bound, so identity-scoped caching must not cache");
        assertEquals(0, store.putCalls);
        assertEquals(0, store.getCalls);
    }

    @Test
    @DisplayName("one supplied CacheIdentityResolver replaces the default")
    void customResolverReplacesDefault() {
        ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        CustomResolverComponent component = DaggerCacheCoreModuleTest_CustomResolverComponent.builder()
                .configModule(new ConfigModule(new JsonObject()))
                .customResolverModule(new CustomResolverModule(store))
                .build();
        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("profiles", String.class)
                .identity(CacheIdentity.ACTOR)
                .build();

        await(cache.get("k", ignored -> Future.succeededFuture("loaded")));
        String second = await(cache.get("k", ignored -> Future.succeededFuture("unexpected")));

        assertEquals("loaded", second);
        assertEquals("i2:P4:USER5:alice", store.lastKey.identityComponent());
    }

    // --- fixtures ---

    private static TestComponent component(JsonObject root) {
        return component(root, new ProgrammaticCacheTestFixtures.RecordingStore(), new CopyOnWriteArrayList<>());
    }

    private static TestComponent component(
            JsonObject root, ProgrammaticCacheTestFixtures.RecordingStore store, List<CacheEvent> events) {
        return DaggerCacheCoreModuleTest_TestComponent.builder()
                .configModule(new ConfigModule(root))
                .providerModule(new ProviderModule(store))
                .observerModule(new ObserverModule(events))
                .build();
    }

    /** A complete {@code cache} section with non-default values, so every key is observably bound. */
    private static JsonObject fullSection() {
        return new JsonObject()
                .put("enabled", false)
                .put("defaultMode", "CLUSTERED")
                .put("defaultTtlSeconds", 30)
                .put("maxTtlSeconds", 600)
                .put("jsonProfile", "system")
                .put("maxKeyBytes", 256)
                .put("maxValueBytes", 2_048)
                .put("maximumEntries", 50)
                .put("backendTimeoutMs", 250)
                .put("caches", new JsonObject());
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    /** Supplies the {@code @VertxConfig} root and the real config parser the application graph provides. */
    @Module
    static final class ConfigModule {
        private final JsonObject root;

        ConfigModule(JsonObject root) {
            this.root = root;
        }

        @Provides
        @VertxConfig
        JsonObject vertxConfig() {
            return root;
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    /** Contributes one in-memory store for both modes via the public provider map keys. */
    @Module
    static final class ProviderModule {
        private final CacheStore store;

        ProviderModule(CacheStore store) {
            this.store = store;
        }

        @Provides
        @IntoMap
        @CacheModeKey(CacheMode.LOCAL)
        CacheStore localStore() {
            return store;
        }

        @Provides
        @IntoMap
        @CacheModeKey(CacheMode.CLUSTERED)
        CacheStore clusteredStore() {
            return store;
        }

        @Provides
        @IntoMap
        @CacheProviderIdKey(CacheMode.LOCAL)
        static String localId() {
            return "fake-local";
        }

        @Provides
        @IntoMap
        @CacheProviderIdKey(CacheMode.CLUSTERED)
        static String clusteredId() {
            return "fake-clustered";
        }
    }

    /** Contributes one observer that records every event it receives. */
    @Module
    static final class ObserverModule {
        private final List<CacheEvent> events;

        ObserverModule(List<CacheEvent> events) {
            this.events = events;
        }

        @Provides
        @IntoSet
        CacheObserver observer() {
            return events::add;
        }
    }

    /** Contributes a LOCAL store and a custom identity resolver for a fixed identity. */
    @Module
    static final class CustomResolverModule {
        private final CacheStore store;

        CustomResolverModule(CacheStore store) {
            this.store = store;
        }

        @Provides
        @IntoMap
        @CacheModeKey(CacheMode.LOCAL)
        CacheStore localStore() {
            return store;
        }

        @Provides
        @IntoMap
        @CacheProviderIdKey(CacheMode.LOCAL)
        static String localId() {
            return "fake-local";
        }

        @Provides
        static CacheIdentityResolver resolver() {
            return () -> Optional.of(SecurityIdentity.user(ALICE));
        }
    }

    @Singleton
    @Component(modules = {CacheCoreModule.class, ConfigModule.class, ProviderModule.class, ObserverModule.class})
    interface TestComponent {
        CacheBuilder cacheBuilder();

        CacheAdapterSupport adapterSupport();

        CacheConfig cacheConfig();

        Set<CacheObserver> observers();
    }

    @Singleton
    @Component(modules = {CacheCoreModule.class, ConfigModule.class})
    interface NoProviderComponent {
        CacheBuilder cacheBuilder();
    }

    @Singleton
    @Component(modules = {CacheCoreModule.class, ConfigModule.class, CustomResolverModule.class})
    interface CustomResolverComponent {
        CacheBuilder cacheBuilder();
    }
}
