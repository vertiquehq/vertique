// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.Invocation;
import dev.vertique.cache.CacheAdapterSupport;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheEvictionTest {

    @Test
    void failedTargetDoesNotEvict() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        Invocation invocation = new Invocation() {
            @Override
            public MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return new Object[] {"alice"};
            }

            @Override
            public Object instance() {
                return new Target();
            }

            @Override
            public Future<Object> proceed() {
                return Future.failedFuture("business failure");
            }
        };

        Future<Object> result = new CacheEvictAspect(store, CacheConfig.defaults(), Set.of())
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(invocation);

        assertTrue(result.failed());
        assertEquals(0, store.evictCalls);
        assertEquals(0, store.clearCalls);
    }

    @Test
    void evictionFailurePreservesSuccessfulMutation() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        store.failEvictions = true;
        var support = CacheAdapterSupport.forStore(store, CacheConfig.defaults(), Set.of(), Set.of());
        registerSharedProfile(support);
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = new CacheEvictAspect(new CacheAnnotationAdapter(support))
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("result-1", result);
        assertEquals(1, store.evictCalls);
    }

    @Test
    void standaloneEvictionAddressesTheRegisteredTargetIdentityBucket() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        CacheIdentityResolver resolver = () -> Optional.of(identity("alice"));
        var support = CacheAdapterSupport.forStore(store, CacheConfig.defaults(), Set.of(), Set.of(resolver));
        var adapter = new CacheAnnotationAdapter(support);
        var cacheableMethod = CacheableTarget.class.getDeclaredMethod("value", String.class);
        var cacheableMetadata = CacheTestFixtures.metadata(cacheableMethod, "user");
        var evictMethod = Target.class.getDeclaredMethod("mutate", String.class);
        var evictMetadata = CacheTestFixtures.metadata(evictMethod, "user");

        new CacheableAspect(adapter)
                .interceptor(cacheableMetadata, cacheableMethod.getAnnotation(Cacheable.class))
                .intercept(CacheTestFixtures.invocation(cacheableMetadata, new Object[] {"alice"}, new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        String cachedIdentity = store.lastKey.identityComponent();
        assertTrue(cachedIdentity.startsWith("i2:P"), cachedIdentity);

        new CacheEvictAspect(adapter)
                .interceptor(evictMetadata, evictMethod.getAnnotation(CacheEvict.class))
                .intercept(CacheTestFixtures.invocation(evictMetadata, new Object[] {"alice"}, new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, store.evictCalls);
        assertEquals(
                cachedIdentity,
                store.lastKey.identityComponent(),
                "a standalone eviction must address the registered target's identity bucket");
    }

    @Test
    void unresolvedEvictionSkipsTheProviderAndObservesTypedOutcome() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        List<CacheEvent> events = new ArrayList<>();
        CacheObserver observer = events::add;
        var support = CacheAdapterSupport.forStore(store, CacheConfig.defaults(), Set.of(observer), Set.of());
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");

        Object result = new CacheEvictAspect(new CacheAnnotationAdapter(support))
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("result-1", result);
        assertEquals(0, store.evictCalls);
        assertTrue(events.stream()
                .anyMatch(event -> event instanceof CacheOperationCompleted completed
                        && completed.operation() == CacheOperation.EVICT
                        && completed.outcome() == CacheOutcome.UNRESOLVED_TARGET));
    }

    @Test
    void programmaticOnlyDefinitionsDoNotSatisfyAnnotationEvictions() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        List<CacheEvent> events = new ArrayList<>();
        CacheObserver observer = events::add;
        var support = CacheAdapterSupport.forStore(store, CacheConfig.defaults(), Set.of(observer), Set.of());
        // A programmatic build registers the name without annotation selector paths.
        support.registered(new CacheAdapterSupport.AdapterDefinition(
                "profile", String.class, null, -1, CacheIdentity.NONE, null, null, false, null));
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");

        new CacheEvictAspect(new CacheAnnotationAdapter(support))
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(0, store.evictCalls, "a programmatic-only definition never satisfies an annotation eviction");
        assertTrue(events.stream()
                .anyMatch(event -> event instanceof CacheOperationCompleted completed
                        && completed.outcome() == CacheOutcome.UNRESOLVED_TARGET));
    }

    private static void registerSharedProfile(CacheAdapterSupport support) {
        support.registered(new CacheAdapterSupport.AdapterDefinition(
                "profile", String.class, null, -1, CacheIdentity.NONE, null, null, false, List.of("0")));
    }

    private static SecurityIdentity identity(String userId) {
        return new SecurityIdentity(
                new PrincipalRef(PrincipalType.USER, userId, Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static final class Target {
        @CacheEvict(name = "profile", key = "0")
        String mutate(String user) {
            return "unused";
        }
    }

    static final class CacheableTarget {
        @Cacheable(name = "profile", key = "0", identity = CacheIdentity.EFFECTIVE_PRINCIPAL)
        String value(String user) {
            return "unused";
        }
    }
}
