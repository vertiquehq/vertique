// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheIdentityTest {

    @Test
    void authenticatedIdentityComponentsAreCanonicalAndDistinct() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        CacheIdentityResolver resolver = identity -> switch (identity) {
            case ACTOR -> Optional.of("actor:USER:alice");
            case EFFECTIVE_PRINCIPAL -> Optional.of("principal:USER:alice");
            case ACTOR_AND_SUBJECT -> Optional.of("actor:USER:service~subject:USER:alice");
            case NONE -> Optional.of("NONE");
        };

        for (CacheIdentity identity :
                Set.of(CacheIdentity.ACTOR, CacheIdentity.EFFECTIVE_PRINCIPAL, CacheIdentity.ACTOR_AND_SUBJECT)) {
            var store = new CacheTestFixtures.RecordingStore();
            var annotation = Target.annotation(identity, AnonymousCachePolicy.BYPASS);
            AtomicInteger targetCalls = new AtomicInteger();
            new CacheableAspect(store, CacheConfig.defaults(), Set.of(), Set.of(resolver))
                    .interceptor(metadata, annotation)
                    .intercept(CacheTestFixtures.invocation(metadata, new Object[0], targetCalls))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            assertEquals(1, store.getCalls, () -> "cache lookup was bypassed for " + identity);
            assertEquals(resolver.resolve(identity).orElseThrow(), store.lastKey.identityComponent());
        }
    }

    @Test
    void anonymousBypassAndOptInUseSeparateBuckets() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        CacheIdentityResolver anonymous = identity -> Optional.empty();
        var bypassStore = new CacheTestFixtures.RecordingStore();
        var bypassCalls = new AtomicInteger();

        new CacheableAspect(bypassStore, CacheConfig.defaults(), Set.of(), Set.of(anonymous))
                .interceptor(metadata, Target.annotation(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], bypassCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(0, bypassStore.getCalls);

        var optInStore = new CacheTestFixtures.RecordingStore();
        new CacheableAspect(optInStore, CacheConfig.defaults(), Set.of(), Set.of(anonymous))
                .interceptor(metadata, Target.annotation(CacheIdentity.ACTOR, AnonymousCachePolicy.CACHE_AS_ANONYMOUS))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("ANONYMOUS", optInStore.lastKey.identityComponent());
    }

    static final class Target {
        static Cacheable annotation(CacheIdentity identity, AnonymousCachePolicy anonymous) {
            return new Cacheable() {
                @Override
                public String name() {
                    return "profile";
                }

                @Override
                public String key() {
                    return "me";
                }

                @Override
                public CacheMode mode() {
                    return CacheMode.LOCAL;
                }

                @Override
                public long ttlSeconds() {
                    return -1;
                }

                @Override
                public CacheIdentity identity() {
                    return identity;
                }

                @Override
                public AnonymousCachePolicy anonymous() {
                    return anonymous;
                }

                @Override
                public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return Cacheable.class;
                }
            };
        }

        @Cacheable(name = "profile", key = "me")
        String current() {
            return "unused";
        }
    }
}
