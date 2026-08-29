// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheIdentityTest {

    @Test
    void contextIdentityIsReadByTheAspectAndIncludedInTheKey() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        var holder = new MutableContextHolder(securityContext("service-1", "user-1"));
        var store = new CacheTestFixtures.RecordingStore();
        CacheIdentityResolver resolver =
                () -> holder.current(SecurityContext.class).map(SecurityContext::identity);

        new CacheableAspect(store, CacheConfig.defaults(), Set.of(), Set.of(resolver))
                .interceptor(metadata, Target.annotation(CacheIdentity.ACTOR_AND_SUBJECT, AnonymousCachePolicy.BYPASS))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("i2:S7:SERVICE9:service-114:USER6:user-1", store.lastKey.identityComponent());
    }

    @Test
    void contextIdentitySeparatesCallersUsingTheSameSelector() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        var holder = new MutableContextHolder(securityContext("service-1", null));
        var store = new CacheTestFixtures.RecordingStore();
        var calls = new AtomicInteger();
        CacheIdentityResolver resolver =
                () -> holder.current(SecurityContext.class).map(SecurityContext::identity);
        var interceptor = new CacheableAspect(store, CacheConfig.defaults(), Set.of(), Set.of(resolver))
                .interceptor(metadata, Target.annotation(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS));

        interceptor
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], calls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        String firstKey = store.lastKey.identityComponent();

        holder.context = securityContext("service-2", null);
        interceptor
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], calls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertNotEquals(firstKey, store.lastKey.identityComponent());
        assertEquals(2, calls.get(), "a different caller must not receive the first caller's value");
    }

    @Test
    void authenticatedIdentityComponentsAreCanonicalAndDistinct() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        CacheIdentityResolver resolver =
                () -> Optional.of(securityContext("service", "alice").identity());

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
            assertEquals(
                    switch (identity) {
                        case ACTOR -> "i2:P7:SERVICE7:service";
                        case EFFECTIVE_PRINCIPAL -> "i2:P4:USER5:alice";
                        case ACTOR_AND_SUBJECT -> "i2:S7:SERVICE7:service14:USER5:alice";
                        case NONE -> "i2:N";
                    },
                    store.lastKey.identityComponent());
        }
    }

    @Test
    void anonymousBypassAndOptInUseSeparateBuckets() throws NoSuchMethodException {
        var method = Target.class.getDeclaredMethod("current");
        var metadata = CacheTestFixtures.metadata(method, "unused");
        CacheIdentityResolver anonymous = Optional::empty;
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

        assertEquals("i2:A", optInStore.lastKey.identityComponent());
    }

    static final class Target {
        static Cacheable annotation(CacheIdentity identity, AnonymousCachePolicy anonymous) {
            return new Cacheable() {
                @Override
                public String name() {
                    return "profile";
                }

                @Override
                public String[] key() {
                    return new String[0];
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

        @Cacheable(
                name = "profile",
                key = {})
        String current() {
            return "unused";
        }
    }

    private static SecurityContext securityContext(String actorId, String subjectId) {
        PrincipalRef actor = new PrincipalRef(PrincipalType.SERVICE, actorId, Map.of());
        Optional<PrincipalRef> subject = subjectId == null
                ? Optional.empty()
                : Optional.of(new PrincipalRef(PrincipalType.USER, subjectId, Map.of()));
        SecurityIdentity identity = new SecurityIdentity(actor, subject, Optional.empty(), Optional.empty());
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return new AuthenticationState(
                        DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
            }

            @Override
            public AuthorizationClaims authorization() {
                return AuthorizationClaims.empty();
            }

            @Override
            public Optional<dev.vertique.security.origin.RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    private static final class MutableContextHolder implements ContextHolder {
        private SecurityContext context;

        private MutableContextHolder(SecurityContext context) {
            this.context = context;
        }

        @Override
        public <T> Optional<T> current(Class<T> type) {
            return type.isInstance(context) ? Optional.of(type.cast(context)) : Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            throw new UnsupportedOperationException("test holder is read-only");
        }
    }
}
