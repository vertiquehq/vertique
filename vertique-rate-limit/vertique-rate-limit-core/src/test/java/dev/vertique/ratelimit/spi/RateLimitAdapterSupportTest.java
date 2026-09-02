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
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

    // --- Shared fixtures ---

    private static RateLimitAdapterSupport adapterSupport(Optional<SecurityIdentity> identity) {
        return new RateLimitAdapterSupport(rateLimiters, () -> identity);
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
