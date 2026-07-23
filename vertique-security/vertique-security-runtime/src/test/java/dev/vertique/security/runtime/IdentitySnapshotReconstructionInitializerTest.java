// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.CarriageRequirement;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link IdentitySnapshotReconstructionInitializer} (PRD-ID-002 §14.3 "Frozen
 * binding-precedence rule", §14.3 amendment A6 "deferred-execution provenance gate", §14.6 amendment
 * A9 "origin-or-verified mint evidence", §14.6 slice P1.S5 / P2.S0).
 *
 * <p>Verifies the five receive-side cases: a live {@link SecurityContext} already bound wins
 * (idempotent no-op); a verified {@link dev.vertique.security.runtime.IdentitySnapshotContext}
 * reconstructs into a real context regardless of origin; an unverifiable (decoder-detected or
 * reconstruction-re-verify-failed) snapshot fails closed, minting a service-only context plus a
 * {@link SnapshotDegradationMarker} <em>only when</em> a {@link DeferredExecutionOrigin} proves
 * the dispatch is deferred execution — otherwise only the marker is bound, no context, per the
 * origin-or-verified mint evidence rule (A9); a snapshot-less dispatch carrying a
 * {@link DeferredExecutionOrigin} mints a bounded service context (SYSTEM via {@code system(...)} or
 * SERVICE via {@code unauthenticated(...)}); and a snapshot-less dispatch with <em>no</em> origin
 * fails closed and binds nothing. The bounded-mint / fail-closed split is the provenance gate: only
 * proven deferred execution (or a verified snapshot) mints a context — an ordinary context-empty
 * dispatch, or an unverifiable snapshot with no origin evidence, is left
 * {@code AUTHENTICATION_REQUIRED}. Also covers the overridable resolver seam (built-in default vs.
 * an application-supplied resolver).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IdentitySnapshotReconstructionInitializerTest {

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));
    private static final DelegationSummary DELEGATION = new DelegationSummary("on-behalf-of", Optional.of("grant-7"));
    private static final ClientRef CLIENT = new ClientRef("client-abc", "jwt-azp", Map.of("app", "mobile"));
    private static final List<AuthorityClaim> CLAIMS = List.of();

    private static IdentitySnapshotCodec newCodec() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        return new IdentitySnapshotCodec(hmac);
    }

    private static final String ACTIVE_SECRET = "super-secret-signing-key-material";

    /**
     * Builds a minimal valid {@link IdentitySnapshotConfig} with no configured
     * {@link CarriageRequirement} entries — every target-kind resolves to
     * {@link CarriageRequirement#OPTIONAL}, matching the pre-P2.S0-commit-5b behavior.
     *
     * @return a config with an empty {@code carriageRequirements} map
     */
    private static IdentitySnapshotConfig optionalConfig() {
        return new IdentitySnapshotConfig(
                true,
                IdentitySnapshotDegradationPolicy.FAIL,
                new SnapshotHmacConfig(new SnapshotHmacKeyConfig("active", ACTIVE_SECRET), List.of()),
                null,
                null,
                30_000L,
                Map.of());
    }

    /**
     * Builds an {@link IdentitySnapshotConfig} declaring a single target-kind's
     * {@link CarriageRequirement}, with both freshness budgets set so a {@code REQUIRED} entry
     * satisfies the config's own startup validation.
     *
     * @param targetKind  the target-kind key
     * @param requirement the requirement to map it to
     * @return the built config
     */
    private static IdentitySnapshotConfig configWith(String targetKind, CarriageRequirement requirement) {
        return new IdentitySnapshotConfig(
                true,
                IdentitySnapshotDegradationPolicy.FAIL,
                new SnapshotHmacConfig(new SnapshotHmacKeyConfig("active", ACTIVE_SECRET), List.of()),
                60_000L,
                3_600_000L,
                30_000L,
                Map.of(targetKind, requirement));
    }

    private static final SnapshotCarrierBinding CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));

    private static IdentitySnapshot signedSnapshot(IdentitySnapshotCodec codec) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                ACTOR,
                Optional.of(SUBJECT),
                Optional.of(DELEGATION),
                Optional.of(CLIENT),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                CLAIMS,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        Instant issuedAt = Instant.now();
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                CARRIER,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));
        return codec.decode(codec.encode(unsigned));
    }

    private static IdentitySnapshotReconstructionInitializer newInitializer(
            ContextHolder holder, IdentityReconstruction reconstruction) {
        ServiceIdentityResolver resolver = origin -> SystemIdentities.scheduledJob(origin.reference());
        return new IdentitySnapshotReconstructionInitializer(
                holder, reconstruction, Optional.of(resolver), optionalConfig());
    }

    private static IdentitySnapshotReconstructionInitializer newInitializer(
            ContextHolder holder, IdentityReconstruction reconstruction, Optional<ServiceIdentityResolver> resolver) {
        return new IdentitySnapshotReconstructionInitializer(holder, reconstruction, resolver, optionalConfig());
    }

    private static IdentitySnapshotReconstructionInitializer newInitializer(
            ContextHolder holder,
            IdentityReconstruction reconstruction,
            Optional<ServiceIdentityResolver> resolver,
            IdentitySnapshotConfig config) {
        return new IdentitySnapshotReconstructionInitializer(holder, reconstruction, resolver, config);
    }

    private static IdentitySnapshotReconstructionInitializer newInitializer(
            ContextHolder holder, IdentityReconstruction reconstruction, IdentitySnapshotConfig config) {
        ServiceIdentityResolver resolver = origin -> SystemIdentities.scheduledJob(origin.reference());
        return new IdentitySnapshotReconstructionInitializer(holder, reconstruction, Optional.of(resolver), config);
    }

    @Test
    @DisplayName("no-op and idempotent when a SecurityContext is already bound")
    void idempotentWhenContextBound(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            SecurityContext live = SecurityContexts.assemble(
                    SecurityIdentity.user(SUBJECT),
                    new AuthenticationState(
                            DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                    AuthorizationClaims.empty(),
                    Optional.empty());
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());

            try (ContextHolder.Scope outer = holder.bind(SecurityContext.class, live)) {
                IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);
                ContextHolder.Scope scope = initializer.initialize(new InboundContextInitializationContext("cron"));

                SecurityContext current = holder.current(SecurityContext.class).orElseThrow();
                assertSame(live, current, "already-bound live context must not be overwritten");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no marker should be bound when the context was already present");

                scope.close(); // idempotent no-op
                assertSame(
                        live,
                        holder.current(SecurityContext.class).orElseThrow(),
                        "closing the no-op scope must not remove the pre-existing binding");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("verified IdentitySnapshotContext reconstructs into the deferred-execution SecurityContext")
    void verifiedSnapshotReconstructs(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentitySnapshotCodec codec = newCodec();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
            IdentitySnapshot snapshot = signedSnapshot(codec);

            try (ContextHolder.Scope snapshotScope =
                    holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.verified(snapshot))) {
                IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                    SecurityContext bound =
                            holder.current(SecurityContext.class).orElseThrow();

                    assertEquals("system:scheduledJob", bound.identity().actor().id());
                    assertTrue(bound.identity().subject().isPresent());
                    assertEquals(SUBJECT, bound.identity().subject().get());
                    assertTrue(
                            holder.current(SnapshotDegradationMarker.class).isEmpty(),
                            "no degradation marker on a successful reconstruction");
                }
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "scope close must remove the bound reconstructed context");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("reconstruction re-verification failure (tampered snapshot) with a DeferredExecutionOrigin still "
            + "binds a service-only context plus a degradation marker")
    void reconstructionReverifyFailureWithOriginStillMints(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentitySnapshotCodec codec = newCodec();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
            IdentitySnapshot valid = signedSnapshot(codec);
            IdentitySnapshot tampered = new IdentitySnapshot(
                    valid.schemaVersion(),
                    valid.content(),
                    valid.carrier(),
                    valid.issuedAt(),
                    valid.expiresAt(),
                    new SnapshotIntegrity(
                            valid.integrity().algorithm(),
                            valid.integrity().keyId(),
                            valid.integrity().tag() + "x"));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-42"))) {
                try (ContextHolder.Scope snapshotScope =
                        holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.verified(tampered))) {
                    IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);
                    try (ContextHolder.Scope scope =
                            initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                        SecurityContext bound =
                                holder.current(SecurityContext.class).orElseThrow();
                        assertEquals(
                                "system:scheduledJob", bound.identity().actor().id());
                        assertTrue(
                                bound.identity().subject().isEmpty(),
                                "an unverifiable snapshot must not carry over any subject");

                        SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                                .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                        assertFalse(marker.reasonCode().isBlank());
                        assertEquals(
                                SnapshotDegradationReason.BAD_HMAC.name(),
                                marker.reasonCode(),
                                "a tampered integrity tag must bind a marker with reasonCode BAD_HMAC, not a "
                                        + "hardcoded catch-all");
                    }
                    assertTrue(
                            holder.current(SecurityContext.class).isEmpty(),
                            "scope close must remove the bound service context");
                    assertTrue(
                            holder.current(SnapshotDegradationMarker.class).isEmpty(),
                            "scope close must remove the bound degradation marker");
                }
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("reconstruction re-verification failure (tampered snapshot) with NO DeferredExecutionOrigin "
            + "binds only the degradation marker, no context")
    void reconstructionReverifyFailureWithoutOriginBindsNoContext(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentitySnapshotCodec codec = newCodec();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
            IdentitySnapshot valid = signedSnapshot(codec);
            IdentitySnapshot tampered = new IdentitySnapshot(
                    valid.schemaVersion(),
                    valid.content(),
                    valid.carrier(),
                    valid.issuedAt(),
                    valid.expiresAt(),
                    new SnapshotIntegrity(
                            valid.integrity().algorithm(),
                            valid.integrity().keyId(),
                            valid.integrity().tag() + "x"));

            try (ContextHolder.Scope snapshotScope =
                    holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.verified(tampered))) {
                IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("services/svc/exec"))) {
                    assertTrue(
                            holder.current(SecurityContext.class).isEmpty(),
                            "a reconstruction re-verification failure with no DeferredExecutionOrigin must not "
                                    + "mint a SecurityContext — no evidence proves the dispatch is deferred "
                                    + "execution");

                    SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                            .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                    assertEquals(
                            SnapshotDegradationReason.BAD_HMAC.name(),
                            marker.reasonCode(),
                            "a tampered integrity tag must bind a marker with reasonCode BAD_HMAC, not a "
                                    + "hardcoded catch-all");
                }
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "scope close must remove the bound degradation marker's absent context (no-op)");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "scope close must remove the bound degradation marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("unverifiable (decoder-detected) snapshot with NO DeferredExecutionOrigin binds only the "
            + "degradation marker, no context")
    void unverifiableSnapshotWithoutOriginBindsNoContext(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);

            try (ContextHolder.Scope snapshotScope = holder.bind(
                    IdentitySnapshotContext.class,
                    IdentitySnapshotContext.unverifiable(SnapshotDegradationReason.BAD_HMAC))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("services/svc/exec"))) {
                    assertTrue(
                            holder.current(SecurityContext.class).isEmpty(),
                            "an unverifiable snapshot with no DeferredExecutionOrigin must not mint a "
                                    + "SecurityContext — no evidence proves the dispatch is deferred execution");

                    SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                            .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                    assertEquals(SnapshotDegradationReason.BAD_HMAC.name(), marker.reasonCode());
                }
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "scope close leaves no bound context (there never was one)");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "scope close must remove the bound degradation marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("unverifiable (decoder-detected) snapshot with a DeferredExecutionOrigin still binds a "
            + "service-only context plus a degradation marker")
    void unverifiableSnapshotWithOriginStillMints(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-42"))) {
                try (ContextHolder.Scope snapshotScope = holder.bind(
                        IdentitySnapshotContext.class,
                        IdentitySnapshotContext.unverifiable(SnapshotDegradationReason.UNKNOWN_KEY))) {
                    try (ContextHolder.Scope scope =
                            initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                        SecurityContext bound =
                                holder.current(SecurityContext.class).orElseThrow();
                        assertEquals(
                                "system:scheduledJob", bound.identity().actor().id());
                        assertTrue(
                                bound.identity().subject().isEmpty(),
                                "an unverifiable snapshot must not carry over any subject");

                        SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                                .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                        assertEquals(SnapshotDegradationReason.UNKNOWN_KEY.name(), marker.reasonCode());
                    }
                    assertTrue(
                            holder.current(SecurityContext.class).isEmpty(),
                            "scope close must remove the bound service context");
                    assertTrue(
                            holder.current(SnapshotDegradationMarker.class).isEmpty(),
                            "scope close must remove the bound degradation marker");
                }
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("no snapshot but a DeferredExecutionOrigin mints a system context reasoned on the origin reference")
    void noSnapshotWithOriginMintsSystemContext(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-42"))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                    SecurityContext bound =
                            holder.current(SecurityContext.class).orElseThrow();
                    assertEquals("system:scheduledJob", bound.identity().actor().id());
                    assertEquals(
                            "order-42",
                            bound.identity().actor().attributes().get("system.reason"),
                            "the minted SYSTEM context's reason must equal the origin reference");
                    assertTrue(bound.identity().subject().isEmpty());
                    assertTrue(
                            holder.current(SnapshotDegradationMarker.class).isEmpty(),
                            "no degradation marker when there was never a snapshot to begin with");
                }
                assertTrue(holder.current(SecurityContext.class).isEmpty());
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("no snapshot AND no DeferredExecutionOrigin fails closed — nothing is bound")
    void noOriginNoSnapshotFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction);

            try (ContextHolder.Scope scope =
                    initializer.initialize(new InboundContextInitializationContext("services/svc/exec"))) {
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "an ordinary context-empty dispatch with no deferred-execution provenance must bind no "
                                + "SecurityContext (fail closed)");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no marker is bound on the fail-closed path");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a SERVICE executing identity is minted via unauthenticated() with NONE authentication")
    void serviceActorMintedViaUnauthenticated(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            ServiceIdentityResolver serviceResolver = origin -> SecurityIdentity.service(
                    new PrincipalRef(PrincipalType.SERVICE, "svc-worker", Map.of("tenant", "acme")));
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(serviceResolver));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("outbox-relay", "OrderPlaced"))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("outbox-relay"))) {
                    SecurityContext bound =
                            holder.current(SecurityContext.class).orElseThrow();
                    assertEquals(
                            PrincipalType.SERVICE,
                            bound.identity().actor().type(),
                            "a SERVICE resolver result must yield a SERVICE-actor context");
                    assertEquals(
                            AuthMethodKind.NONE,
                            bound.authentication().primaryMethod().normalizedKind(),
                            "a SERVICE actor is minted via unauthenticated() — NONE auth, never custom(system)");
                    assertTrue(bound.identity().subject().isEmpty());
                }
                assertTrue(holder.current(SecurityContext.class).isEmpty());
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("the built-in default resolver is used when no application resolver is bound (empty Optional)")
    void defaultResolverUsedWhenAbsent(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty());

            try (ContextHolder.Scope originScope =
                    holder.bind(DeferredExecutionOrigin.class, new DeferredExecutionOrigin("cron", "nightly"))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("cron"))) {
                    SecurityContext bound =
                            holder.current(SecurityContext.class).orElseThrow();
                    assertEquals("system:scheduledJob", bound.identity().actor().id());
                    assertEquals(
                            "nightly",
                            bound.identity().actor().attributes().get("system.reason"),
                            "the built-in default resolver keys the system reason on the origin reference");
                }
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a resolver returning null for a present origin fails closed with a clear NPE, binding nothing")
    void nullReturningResolverFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            ServiceIdentityResolver nullResolver = origin -> null;
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(nullResolver));

            try (ContextHolder.Scope originScope =
                    holder.bind(DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "x"))) {
                NullPointerException thrown = assertThrows(
                        NullPointerException.class,
                        () -> initializer.initialize(new InboundContextInitializationContext("delayed-job")),
                        "a resolver returning null for a present origin must fail closed, not silently degrade to the "
                                + "boundary-default identity");
                assertTrue(
                        thrown.getMessage().contains("must not return null"),
                        "the NPE must carry a clear resolver-contract message");
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no SecurityContext may be left bound after the null-resolver contract fails closed");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no degradation marker may be left bound after the fail-closed throw");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a resolver returning a USER-actor identity fails closed via the actor-type guard, binding nothing")
    void badActorTypeResolverFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            ServiceIdentityResolver userResolver = origin -> SecurityIdentity.user(SUBJECT);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(userResolver));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-99"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> initializer.initialize(new InboundContextInitializationContext("delayed-job")),
                        "a USER-actor resolver result must fail closed via the actor-type guard, never mint a context");
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no SecurityContext may be left bound after the actor-type guard fails closed");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no degradation marker may be left bound after the fail-closed throw");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a SYSTEM resolver result carrying a subject fails closed at the resolver chokepoint, binding nothing")
    void systemResolverWithSubjectFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            // A SYSTEM actor (via SystemIdentities) but illegally carrying a USER subject — the
            // resolver contract forbids a subject/delegation on the executing identity.
            PrincipalRef systemActor = SystemIdentities.scheduledJob("x").actor();
            SecurityIdentity systemWithSubject =
                    new SecurityIdentity(systemActor, Optional.of(SUBJECT), Optional.empty(), Optional.empty());
            ServiceIdentityResolver subjectBearingResolver = origin -> systemWithSubject;
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(subjectBearingResolver));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-77"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> initializer.initialize(new InboundContextInitializationContext("delayed-job")),
                        "a SYSTEM resolver result carrying a subject must fail closed at the resolver chokepoint, "
                                + "never mint a subject-bearing context");
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no SecurityContext may be left bound after the subject-bearing resolver fails closed");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no degradation marker may be left bound after the fail-closed throw");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName(
            "a SERVICE resolver result carrying a subject fails closed at the resolver chokepoint, binding nothing")
    void serviceResolverWithSubjectFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            // A SERVICE actor illegally carrying a USER subject — SecurityIdentity.service(...) is
            // actor-only, so the offending identity is constructed directly to exercise the guard.
            PrincipalRef serviceActor = new PrincipalRef(PrincipalType.SERVICE, "svc-worker", Map.of("tenant", "acme"));
            SecurityIdentity serviceWithSubject =
                    new SecurityIdentity(serviceActor, Optional.of(SUBJECT), Optional.empty(), Optional.empty());
            ServiceIdentityResolver subjectBearingResolver = origin -> serviceWithSubject;
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(subjectBearingResolver));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("outbox-relay", "OrderPlaced"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> initializer.initialize(new InboundContextInitializationContext("outbox-relay")),
                        "a SERVICE resolver result carrying a subject must fail closed at the resolver chokepoint, "
                                + "never mint a subject-bearing context");
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no SecurityContext may be left bound after the subject-bearing resolver fails closed");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no degradation marker may be left bound after the fail-closed throw");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a resolver result carrying a client fails closed at the resolver chokepoint, binding nothing")
    void clientBearingResolverFailsClosed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            // A SYSTEM actor with no subject/delegation but illegally carrying a request-client — a
            // deferred executor identity carries no request-client provenance, so the resolver
            // contract forbids a client on the executing identity just as it forbids subject/delegation.
            PrincipalRef systemActor = SystemIdentities.scheduledJob("x").actor();
            SecurityIdentity systemWithClient =
                    new SecurityIdentity(systemActor, Optional.empty(), Optional.empty(), Optional.of(CLIENT));
            ServiceIdentityResolver clientBearingResolver = origin -> systemWithClient;
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.of(clientBearingResolver));

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-55"))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> initializer.initialize(new InboundContextInitializationContext("delayed-job")),
                        "a resolver result carrying a client must fail closed at the resolver chokepoint, "
                                + "never mint a client-bearing context");
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no SecurityContext may be left bound after the client-bearing resolver fails closed");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "no degradation marker may be left bound after the fail-closed throw");
            }
            ctx.completeNow();
        });
    }

    // --- Expected-but-absent carriage detection (§14.6 A9, P2.S0 commit 5b) ---

    @Test
    @DisplayName("REQUIRED target-kind, no snapshot, no origin: binds only the EXPECTED_ABSENT marker, no context")
    void requiredTargetNoSnapshotNoOriginEmitsExpectedAbsentMarker(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotConfig config = configWith("delayed-job", CarriageRequirement.REQUIRED);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty(), config);

            try (ContextHolder.Scope scope =
                    initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "no origin proves deferred execution, so a REQUIRED target must still not mint a "
                                + "SecurityContext — only the marker is bound");

                SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                        .orElseThrow(() -> new AssertionError("expected an EXPECTED_ABSENT marker to be bound"));
                assertEquals(SnapshotDegradationReason.EXPECTED_ABSENT.name(), marker.reasonCode());
            }
            assertTrue(
                    holder.current(SnapshotDegradationMarker.class).isEmpty(),
                    "scope close must remove the bound degradation marker");
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("REQUIRED target-kind, no snapshot, with origin: mints the service context AND binds the "
            + "EXPECTED_ABSENT marker")
    void requiredTargetNoSnapshotWithOriginMintsAndMarks(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotConfig config = configWith("delayed-job", CarriageRequirement.REQUIRED);
            IdentitySnapshotReconstructionInitializer initializer = newInitializer(holder, reconstruction, config);

            try (ContextHolder.Scope originScope = holder.bind(
                    DeferredExecutionOrigin.class, new DeferredExecutionOrigin("delayed-job", "order-42"))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                    SecurityContext bound =
                            holder.current(SecurityContext.class).orElseThrow();
                    assertEquals("system:scheduledJob", bound.identity().actor().id());

                    SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                            .orElseThrow(() -> new AssertionError("expected an EXPECTED_ABSENT marker to be bound"));
                    assertEquals(SnapshotDegradationReason.EXPECTED_ABSENT.name(), marker.reasonCode());
                }
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "scope close must remove the bound service context");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "scope close must remove the bound degradation marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("OPTIONAL target-kind, no snapshot, no origin: fails closed with no marker (unchanged Case 5 no-op)")
    void optionalTargetNoSnapshotNoMarker(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotConfig config = configWith("delayed-job", CarriageRequirement.OPTIONAL);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty(), config);

            try (ContextHolder.Scope scope =
                    initializer.initialize(new InboundContextInitializationContext("delayed-job"))) {
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "an OPTIONAL target-kind must keep the plain fail-closed no-op");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "an OPTIONAL target-kind must never bind an EXPECTED_ABSENT marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("FORBIDDEN target-kind, no snapshot, no origin: fails closed with no marker (unchanged Case 5 no-op)")
    void forbiddenTargetNoSnapshotNoMarker(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotConfig config = configWith("cron", CarriageRequirement.FORBIDDEN);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty(), config);

            try (ContextHolder.Scope scope = initializer.initialize(new InboundContextInitializationContext("cron"))) {
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "a FORBIDDEN target-kind must keep the plain fail-closed no-op");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "a FORBIDDEN target-kind must never bind an EXPECTED_ABSENT marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("F4 security review: FORBIDDEN target-kind with a PRESENT verified snapshot refuses — no "
            + "SecurityContext is minted, only the degradation marker is bound")
    void forbiddenTargetWithPresentSnapshotRefuses(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentitySnapshotCodec codec = newCodec();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(codec);
            IdentitySnapshot snapshot = signedSnapshot(codec);
            IdentitySnapshotConfig config = configWith("cron", CarriageRequirement.FORBIDDEN);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty(), config);

            try (ContextHolder.Scope snapshotScope =
                    holder.bind(IdentitySnapshotContext.class, IdentitySnapshotContext.verified(snapshot))) {
                try (ContextHolder.Scope scope =
                        initializer.initialize(new InboundContextInitializationContext("cron"))) {
                    assertTrue(
                            holder.current(SecurityContext.class).isEmpty(),
                            "a FORBIDDEN target-kind must never reconstruct an identity from a present snapshot, "
                                    + "even a verified one");

                    SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                            .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                    assertEquals(SnapshotDegradationReason.EXPECTED_ABSENT.name(), marker.reasonCode());
                }
                assertTrue(
                        holder.current(SecurityContext.class).isEmpty(),
                        "scope close leaves no bound context (there never was one)");
                assertTrue(
                        holder.current(SnapshotDegradationMarker.class).isEmpty(),
                        "scope close must remove the bound degradation marker");
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("F4 security review: FORBIDDEN target-kind with a PRESENT unverifiable snapshot refuses — "
            + "verification status is irrelevant once the target is FORBIDDEN")
    void forbiddenTargetWithUnverifiableSnapshotRefuses(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            ContextHolder holder = new DefaultContextHolder();
            IdentityReconstruction reconstruction = new DefaultIdentityReconstruction(newCodec());
            IdentitySnapshotConfig config = configWith("cron", CarriageRequirement.FORBIDDEN);
            IdentitySnapshotReconstructionInitializer initializer =
                    newInitializer(holder, reconstruction, Optional.empty(), config);

            try (ContextHolder.Scope originScope =
                    holder.bind(DeferredExecutionOrigin.class, new DeferredExecutionOrigin("cron", "nightly"))) {
                try (ContextHolder.Scope snapshotScope = holder.bind(
                        IdentitySnapshotContext.class,
                        IdentitySnapshotContext.unverifiable(SnapshotDegradationReason.BAD_HMAC))) {
                    try (ContextHolder.Scope scope =
                            initializer.initialize(new InboundContextInitializationContext("cron"))) {
                        assertTrue(
                                holder.current(SecurityContext.class).isEmpty(),
                                "a FORBIDDEN target-kind must never mint a fallback context from a present "
                                        + "unverifiable snapshot, even with a DeferredExecutionOrigin present");

                        SnapshotDegradationMarker marker = holder.current(SnapshotDegradationMarker.class)
                                .orElseThrow(() -> new AssertionError("expected a degradation marker to be bound"));
                        assertEquals(SnapshotDegradationReason.EXPECTED_ABSENT.name(), marker.reasonCode());
                    }
                }
            }
            ctx.completeNow();
        });
    }
}
