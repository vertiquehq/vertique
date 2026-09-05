// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.RateLimitCoreTestFixtures;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.exception.RateLimitRequestException;
import dev.vertique.ratelimit.exception.RateLimitRequestFailure;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001: {@link RateLimitAdapterSupport#subjectKey} distinguishes an anonymous caller (governed
 * by {@link AnonymousRateLimitPolicy}) from an authenticated caller whose requested facet is
 * absent — a hard {@code SUBJECT_UNRESOLVABLE} failure, never a silent fall-through into the
 * shared anonymous bucket (contracts/rate-limit-runtime.md, "Framework adapter seam"; {@code
 * spec.md} §5.2).
 */
class RateLimitAdapterSupportTest {

    private static final String POLICY_NAME = "probe";
    private static final List<Object> EXTRA_COMPONENTS = List.of("tenant-42");

    private static Vertx vertx;
    private static RateLimiters rateLimiters;

    @BeforeAll
    static void setUpRateLimiters() {
        vertx = Vertx.vertx();
        rateLimiters = RateLimitCoreTestFixtures.singlePolicy(vertx, probePolicy(), Set.of());
    }

    @AfterAll
    static void closeVertx() {
        vertx.close();
    }

    static Stream<MatrixRow> t006ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldFrameASharedAnonymousComponentWhenNoIdentityAndPolicyIsSharedBucket",
                        RateLimitAdapterSupportTest
                                ::shouldFrameASharedAnonymousComponentWhenNoIdentityAndPolicyIsSharedBucket),
                new MatrixRow(
                        "shouldReturnEmptyWhenNoIdentityAndPolicyIsBypass",
                        RateLimitAdapterSupportTest::shouldReturnEmptyWhenNoIdentityAndPolicyIsBypass),
                new MatrixRow(
                        "shouldFailWithSubjectUnresolvableWhenTheRequestedFacetIsAbsent",
                        RateLimitAdapterSupportTest::shouldFailWithSubjectUnresolvableWhenTheRequestedFacetIsAbsent),
                new MatrixRow(
                        "shouldFrameTheResolvedFacetWhenIdentityAndTheRequestedFacetArePresent",
                        RateLimitAdapterSupportTest
                                ::shouldFrameTheResolvedFacetWhenIdentityAndTheRequestedFacetArePresent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t006ContractMatrix")
    @DisplayName("enforces the T006 subject-resolution contract matrix")
    void shouldEnforceT006ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: no identity, SHARED_BUCKET ---

    private static void shouldFrameASharedAnonymousComponentWhenNoIdentityAndPolicyIsSharedBucket() {
        RateLimitAdapterSupport firstAnonymousCaller = adapterSupport(Optional.empty());
        RateLimitAdapterSupport secondAnonymousCaller = adapterSupport(Optional.empty());

        Optional<RateLimitKey> firstKey = firstAnonymousCaller.subjectKey(
                RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, EXTRA_COMPONENTS);
        Optional<RateLimitKey> secondKey = secondAnonymousCaller.subjectKey(
                RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, EXTRA_COMPONENTS);

        assertThat(firstKey).as("SHARED_BUCKET key for an anonymous caller").isPresent();
        assertThat(firstKey)
                .as("SHARED_BUCKET key must be stable across distinct anonymous callers")
                .isEqualTo(secondKey);
    }

    // --- Row 2: no identity, BYPASS ---

    private static void shouldReturnEmptyWhenNoIdentityAndPolicyIsBypass() {
        RateLimitAdapterSupport support = adapterSupport(Optional.empty());

        Optional<RateLimitKey> key =
                support.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.BYPASS, EXTRA_COMPONENTS);

        assertThat(key).as("BYPASS key for an anonymous caller").isEmpty();
    }

    // --- Row 3: identity present, requested facet (CLIENT) absent ---

    private static void shouldFailWithSubjectUnresolvableWhenTheRequestedFacetIsAbsent() {
        SecurityIdentity noClient = RateLimitIdentityFixtures.actorOnly("service-1");
        RateLimitAdapterSupport support = adapterSupport(Optional.of(noClient));

        assertThatThrownBy(() -> support.subjectKey(
                        RateLimitSubject.CLIENT, AnonymousRateLimitPolicy.SHARED_BUCKET, EXTRA_COMPONENTS))
                .as("CLIENT requested but identity.client() is empty")
                .isInstanceOfSatisfying(RateLimitRequestException.class, thrown -> assertThat(thrown.reason())
                        .isEqualTo(RateLimitRequestFailure.SUBJECT_UNRESOLVABLE));
    }

    // --- Row 4: identity and the requested facet (EFFECTIVE_PRINCIPAL) both present ---

    private static void shouldFrameTheResolvedFacetWhenIdentityAndTheRequestedFacetArePresent() {
        RateLimitAdapterSupport anonymousSupport = adapterSupport(Optional.empty());
        SecurityIdentity withSubject = RateLimitIdentityFixtures.withSubject("service-1", "user-1");
        RateLimitAdapterSupport identitySupport = adapterSupport(Optional.of(withSubject));

        Optional<RateLimitKey> anonymousSharedKey = anonymousSupport.subjectKey(
                RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, EXTRA_COMPONENTS);
        Optional<RateLimitKey> facetKey = identitySupport.subjectKey(
                RateLimitSubject.EFFECTIVE_PRINCIPAL, AnonymousRateLimitPolicy.SHARED_BUCKET, EXTRA_COMPONENTS);

        assertThat(facetKey)
                .as("EFFECTIVE_PRINCIPAL key when identity.subject() is present")
                .isPresent();
        assertThat(facetKey)
                .as("a facet-present key must differ from the anonymous SHARED_BUCKET marker key — the facet"
                        + " value, not a placeholder, must be framed")
                .isNotEqualTo(anonymousSharedKey);
    }

    @Test
    @DisplayName("canonical anonymous identity and empty resolver result share anonymous semantics")
    void shouldTreatCanonicalAnonymousAndEmptyResolverAsSameState() {
        RateLimitAdapterSupport empty = adapterSupport(Optional.empty(), Optional.empty());
        RateLimitAdapterSupport canonical = adapterSupport(Optional.of(SecurityIdentity.anonymous()), Optional.empty());
        SecurityIdentity authenticated = RateLimitIdentityFixtures.actorOnly("authenticated-user");
        RateLimitAdapterSupport authenticatedSupport = adapterSupport(Optional.of(authenticated), Optional.empty());

        assertThat(canonical.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()))
                .isEqualTo(empty.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()))
                .isNotEqualTo(authenticatedSupport.subjectKey(
                        RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()));
        assertThat(canonical.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.BYPASS, List.of()))
                .isEmpty();
        assertThat(empty.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.BYPASS, List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("origin-aware subjects use trusted client IP and preserve authenticated facets")
    void shouldUseTrustedOriginForEveryOriginAwareMode() {
        RequestOrigin trustedOrigin = origin("203.0.113.77");
        RequestOrigin changedOrigin = origin("203.0.113.88");
        SecurityIdentity actorWithClient = RateLimitIdentityFixtures.withClient("actor-1", "client-1");
        SecurityIdentity actorWithoutClient = RateLimitIdentityFixtures.actorOnly("actor-1");

        RateLimitKey ipKey = adapterSupport(Optional.of(actorWithClient), Optional.of(trustedOrigin))
                .subjectKey(RateLimitSubject.IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        RateLimitKey changedIpKey = adapterSupport(Optional.empty(), Optional.of(changedOrigin))
                .subjectKey(RateLimitSubject.IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        assertThat(ipKey).isNotEqualTo(changedIpKey);

        RateLimitKey actorKey = adapterSupport(Optional.of(actorWithClient), Optional.of(trustedOrigin))
                .subjectKey(RateLimitSubject.ACTOR_OR_IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        RateLimitKey changedActorKey = adapterSupport(Optional.of(actorWithClient), Optional.of(changedOrigin))
                .subjectKey(RateLimitSubject.ACTOR_OR_IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        assertThat(actorKey).isEqualTo(changedActorKey);

        RateLimitKey clientKey = adapterSupport(Optional.of(actorWithClient), Optional.of(trustedOrigin))
                .subjectKey(RateLimitSubject.CLIENT_OR_IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        RateLimitKey changedClientKey = adapterSupport(Optional.of(actorWithClient), Optional.of(changedOrigin))
                .subjectKey(RateLimitSubject.CLIENT_OR_IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        assertThat(clientKey).isEqualTo(changedClientKey);

        RateLimitKey noClientFallback = adapterSupport(Optional.of(actorWithoutClient), Optional.of(trustedOrigin))
                .subjectKey(RateLimitSubject.CLIENT_OR_IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        RateLimitKey anonymousFallback = adapterSupport(
                        Optional.of(SecurityIdentity.anonymous()), Optional.of(trustedOrigin))
                .subjectKey(RateLimitSubject.CLIENT_OR_IP, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of())
                .orElseThrow();
        assertThat(noClientFallback).isEqualTo(anonymousFallback);

        RateLimitKey directPeerKey = adapterSupport(Optional.empty(), Optional.of(origin("198.51.100.10")))
                .subjectKey(RateLimitSubject.IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        RateLimitKey forwardedHeaderKey = adapterSupport(Optional.empty(), Optional.of(origin("198.51.100.11")))
                .subjectKey(RateLimitSubject.IP, AnonymousRateLimitPolicy.BYPASS, List.of())
                .orElseThrow();
        assertThat(ipKey).isNotEqualTo(directPeerKey).isNotEqualTo(forwardedHeaderKey);
    }

    @Test
    @DisplayName("origin-aware subjects fail closed when no captured origin exists")
    void shouldFailClosedWhenOriginIsMissing() {
        for (RateLimitSubject subject :
                List.of(RateLimitSubject.IP, RateLimitSubject.ACTOR_OR_IP, RateLimitSubject.CLIENT_OR_IP)) {
            assertThatThrownBy(() -> adapterSupport(Optional.empty(), Optional.empty())
                            .subjectKey(subject, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()))
                    .isInstanceOfSatisfying(RateLimitRequestException.class, thrown -> assertThat(thrown.reason())
                            .isEqualTo(RateLimitRequestFailure.SUBJECT_UNRESOLVABLE));
        }
    }

    @Test
    @DisplayName("strict existing subjects retain their identity and failure semantics")
    void shouldPreserveStrictExistingSubjects() {
        SecurityIdentity actor = RateLimitIdentityFixtures.actorOnly("actor-1");
        SecurityIdentity withSubject = RateLimitIdentityFixtures.withSubject("actor-1", "subject-1");
        RateLimitAdapterSupport actorSupport = adapterSupport(Optional.of(actor), Optional.empty());
        RateLimitAdapterSupport subjectSupport = adapterSupport(Optional.of(withSubject), Optional.empty());

        assertThat(actorSupport.subjectKey(RateLimitSubject.NONE, AnonymousRateLimitPolicy.BYPASS, List.of()))
                .contains(RateLimitKey.global());
        assertThat(actorSupport.subjectKey(RateLimitSubject.ACTOR, AnonymousRateLimitPolicy.BYPASS, List.of()))
                .isNotEqualTo(subjectSupport.subjectKey(
                        RateLimitSubject.EFFECTIVE_PRINCIPAL, AnonymousRateLimitPolicy.BYPASS, List.of()));
        assertThatThrownBy(() -> actorSupport.subjectKey(
                        RateLimitSubject.CLIENT, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of()))
                .isInstanceOfSatisfying(RateLimitRequestException.class, thrown -> assertThat(thrown.reason())
                        .isEqualTo(RateLimitRequestFailure.SUBJECT_UNRESOLVABLE));
    }

    // --- Shared fixtures ---

    private static RateLimitAdapterSupport adapterSupport(Optional<SecurityIdentity> identity) {
        return adapterSupport(identity, Optional.empty());
    }

    private static RateLimitAdapterSupport adapterSupport(
            Optional<SecurityIdentity> identity, Optional<RequestOrigin> origin) {
        return new RateLimitAdapterSupport(rateLimiters, new RateLimitSubjectResolver() {
            @Override
            public Optional<SecurityIdentity> current() {
                return identity;
            }

            @Override
            public Optional<RequestOrigin> currentOrigin() {
                return origin;
            }
        });
    }

    private static RequestOrigin origin(String clientIp) {
        return new RequestOrigin(
                "198.51.100.10",
                44321,
                List.of("203.0.113.77"),
                0,
                false,
                clientIp,
                "https",
                "api.example.test",
                Optional.empty());
    }

    private static RateLimitPolicy probePolicy() {
        return new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofSeconds(60))));
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }
}
