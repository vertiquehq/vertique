// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof of identity-dimension key framing and the fail-closed rules for identity-scoped caching. */
class CacheIdentityModesTest {

    private static final PrincipalRef ALICE = new PrincipalRef(PrincipalType.USER, "alice", Map.of());
    private static final PrincipalRef BOB = new PrincipalRef(PrincipalType.USER, "bob", Map.of());

    // --- identity dimensions ---

    @Test
    @DisplayName("NONE never consults the resolver and uses the shared bucket")
    void noneUsesSharedBucket() {
        Fixture fixture = new Fixture(() -> {
            throw new AssertionError("resolver must not be consulted for NONE");
        });
        Cache<String, String> cache = fixture.cache(CacheIdentity.NONE, AnonymousCachePolicy.BYPASS);

        assertEquals("v", await(cache.get("k", ignored -> Future.succeededFuture("v"))));

        assertEquals("i2:N", fixture.store.lastKey.identityComponent());
        assertEquals(1, fixture.store.putCalls);
    }

    @Test
    @DisplayName("ACTOR keys on the actor and ignores the subject")
    void actorUsesActor() {
        Fixture fixture = new Fixture(() -> Optional.of(delegated(ALICE, BOB)));
        Cache<String, String> cache = fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS);

        await(cache.get("k", ignored -> Future.succeededFuture("v")));

        assertEquals("i2:P4:USER5:alice", fixture.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("EFFECTIVE_PRINCIPAL keys on the subject when present, otherwise on the actor")
    void effectivePrincipalPrefersSubject() {
        Fixture withSubject = new Fixture(() -> Optional.of(delegated(ALICE, BOB)));
        await(withSubject
                .cache(CacheIdentity.EFFECTIVE_PRINCIPAL, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals("i2:P4:USER3:bob", withSubject.store.lastKey.identityComponent());

        Fixture actorOnly = new Fixture(() -> Optional.of(direct(ALICE)));
        await(actorOnly
                .cache(CacheIdentity.EFFECTIVE_PRINCIPAL, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals("i2:P4:USER5:alice", actorOnly.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("ACTOR_AND_SUBJECT preserves both dimensions and distinguishes an absent subject")
    void actorAndSubjectPreservesBoth() {
        Fixture withSubject = new Fixture(() -> Optional.of(delegated(ALICE, BOB)));
        await(withSubject
                .cache(CacheIdentity.ACTOR_AND_SUBJECT, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals("i2:S4:USER5:alice14:USER3:bob", withSubject.store.lastKey.identityComponent());

        Fixture actorOnly = new Fixture(() -> Optional.of(direct(ALICE)));
        await(actorOnly
                .cache(CacheIdentity.ACTOR_AND_SUBJECT, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals("i2:S4:USER5:alice0", actorOnly.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("principal ids are percent-encoded so they cannot forge identity framing")
    void principalIdsArePercentEncoded() {
        PrincipalRef hostile = new PrincipalRef(PrincipalType.USER, "a:b c", Map.of());
        Fixture fixture = new Fixture(() -> Optional.of(direct(hostile)));

        await(fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));

        assertEquals("i2:P4:USER9:a%3Ab%20c", fixture.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("distinct callers never share an identity-scoped entry")
    void distinctCallersAreIsolated() {
        List<SecurityIdentity> current = new ArrayList<>(List.of(direct(ALICE)));
        Fixture fixture = new Fixture(() -> Optional.of(current.get(0)));
        Cache<String, String> cache = fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("alice-data", await(cache.get("k", ignored -> load(loads, "alice-data"))));
        current.set(0, direct(BOB));
        assertEquals("bob-data", await(cache.get("k", ignored -> load(loads, "bob-data"))));
        current.set(0, direct(ALICE));
        assertEquals("alice-data", await(cache.get("k", ignored -> load(loads, "unexpected"))));

        assertEquals(2, loads.get());
    }

    // --- fail closed ---

    @Test
    @DisplayName("no resolver bound: identity-scoped caching bypasses the store and runs the loader")
    void missingResolverFailsClosed() {
        for (CacheIdentity identity :
                List.of(CacheIdentity.ACTOR, CacheIdentity.EFFECTIVE_PRINCIPAL, CacheIdentity.ACTOR_AND_SUBJECT)) {
            Fixture fixture = new Fixture(null);
            AtomicInteger loads = new AtomicInteger();

            Cache<String, String> cache = fixture.cache(identity, AnonymousCachePolicy.BYPASS);
            assertEquals("v", await(cache.get("k", ignored -> load(loads, "v"))));
            assertEquals("v", await(cache.get("k", ignored -> load(loads, "v"))));

            assertEquals(2, loads.get(), identity + " must not cache without an identity");
            assertEquals(0, fixture.store.getCalls, identity + " must not touch the store");
            assertEquals(0, fixture.store.putCalls, identity + " must not write to the store");
            assertEquals(CacheOutcome.BYPASS_IDENTITY, fixture.lastOutcome(CacheOperation.GET));
        }
    }

    @Test
    @DisplayName("resolver without a current identity fails closed")
    void emptyIdentityFailsClosed() {
        Fixture fixture = new Fixture(Optional::empty);
        AtomicInteger loads = new AtomicInteger();
        Cache<String, String> cache = fixture.cache(CacheIdentity.EFFECTIVE_PRINCIPAL, AnonymousCachePolicy.BYPASS);

        await(cache.get("k", ignored -> load(loads, "v")));
        await(cache.get("k", ignored -> load(loads, "v")));

        assertEquals(2, loads.get());
        assertEquals(0, fixture.store.getCalls);
        assertEquals(0, fixture.store.putCalls);
    }

    @Test
    @DisplayName("a throwing resolver fails closed instead of failing the business call")
    void throwingResolverFailsClosed() {
        Fixture fixture = new Fixture(() -> {
            throw new IllegalStateException("context unavailable");
        });
        Cache<String, String> cache = fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS);

        assertEquals("v", await(cache.get("k", ignored -> Future.succeededFuture("v"))));

        assertEquals(0, fixture.store.getCalls);
        assertEquals(0, fixture.store.putCalls);
        assertEquals(CacheOutcome.BYPASS_IDENTITY, fixture.lastOutcome(CacheOperation.GET));
    }

    @Test
    @DisplayName("anonymous principal bypasses unless CACHE_AS_ANONYMOUS is selected")
    void anonymousPrincipalPolicy() {
        Fixture bypass = new Fixture(() -> Optional.of(SecurityIdentity.anonymous()));
        await(bypass.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals(0, bypass.store.getCalls);
        assertEquals(CacheOutcome.BYPASS_IDENTITY, bypass.lastOutcome(CacheOperation.GET));

        Fixture anonymous = new Fixture(() -> Optional.of(SecurityIdentity.anonymous()));
        await(anonymous
                .cache(CacheIdentity.ACTOR, AnonymousCachePolicy.CACHE_AS_ANONYMOUS)
                .get("k", ignored -> Future.succeededFuture("v")));
        assertEquals("i2:A", anonymous.store.lastKey.identityComponent());
        assertEquals(1, anonymous.store.putCalls);
    }

    @Test
    @DisplayName("CACHE_AS_ANONYMOUS also covers an absent identity, using the anonymous bucket")
    void absentIdentityWithAnonymousPolicy() {
        Fixture fixture = new Fixture(Optional::empty);

        await(fixture.cache(CacheIdentity.EFFECTIVE_PRINCIPAL, AnonymousCachePolicy.CACHE_AS_ANONYMOUS)
                .get("k", ignored -> Future.succeededFuture("v")));

        assertEquals("i2:A", fixture.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("NONE ignores the anonymous policy and stays in the shared bucket")
    void noneIgnoresAnonymousPolicy() {
        Fixture fixture = new Fixture(Optional::empty);

        await(fixture.cache(CacheIdentity.NONE, AnonymousCachePolicy.CACHE_AS_ANONYMOUS)
                .get("k", ignored -> Future.succeededFuture("v")));

        assertEquals("i2:N", fixture.store.lastKey.identityComponent());
    }

    @Test
    @DisplayName("invalidate() without an identity fails closed: no eviction is issued")
    void invalidateFailsClosed() {
        Fixture fixture = new Fixture(Optional::empty);
        Cache<String, String> cache = fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS);

        assertFalse(await(cache.invalidate("k")));

        assertEquals(CacheOutcome.BYPASS_IDENTITY, fixture.lastOutcome(CacheOperation.EVICT));
    }

    @Test
    @DisplayName("an ill-formed principal id fails closed instead of producing a key")
    void illFormedPrincipalIdFailsClosed() {
        PrincipalRef loneSurrogate = new PrincipalRef(PrincipalType.USER, "bad\uD800", Map.of());
        Fixture fixture = new Fixture(() -> Optional.of(direct(loneSurrogate)));
        AtomicInteger loads = new AtomicInteger();

        assertEquals(
                "v",
                await(fixture.cache(CacheIdentity.ACTOR, AnonymousCachePolicy.BYPASS)
                        .get("k", ignored -> load(loads, "v"))));

        assertEquals(1, loads.get());
        assertEquals(0, fixture.store.putCalls);
        assertEquals(CacheOutcome.BYPASS_SELECTOR, fixture.lastOutcome(CacheOperation.GET));
    }

    // --- fixtures ---

    private static SecurityIdentity direct(PrincipalRef actor) {
        return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static SecurityIdentity delegated(PrincipalRef actor, PrincipalRef subject) {
        return new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty());
    }

    private static Future<String> load(AtomicInteger counter, String value) {
        counter.incrementAndGet();
        return Future.succeededFuture(value);
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    /** One builder over a recording store, with an optional resolver and a recording observer. */
    private static final class Fixture {
        final ProgrammaticCacheTestFixtures.RecordingStore store = new ProgrammaticCacheTestFixtures.RecordingStore();
        final List<CacheOperationCompleted> events = new ArrayList<>();
        final CacheBuilder builder;

        Fixture(Supplier<Optional<SecurityIdentity>> resolver) {
            CacheObserver observer = (CacheEvent event) -> {
                if (event instanceof CacheOperationCompleted completed) {
                    events.add(completed);
                }
            };
            Set<CacheIdentityResolver> resolvers = resolver == null ? Set.of() : Set.of(resolver::get);
            builder = CacheBuilder.forTesting(store, CacheConfig.defaults(), Set.of(observer), resolvers);
        }

        Cache<String, String> cache(CacheIdentity identity, AnonymousCachePolicy policy) {
            return builder.cache("profiles", String.class)
                    .identity(identity)
                    .anonymous(policy)
                    .ttl(Duration.ofSeconds(30))
                    .build();
        }

        CacheOutcome lastOutcome(CacheOperation operation) {
            return events.stream()
                    .filter(event -> event.operation() == operation)
                    .reduce((first, second) -> second)
                    .orElseThrow()
                    .outcome();
        }
    }
}
