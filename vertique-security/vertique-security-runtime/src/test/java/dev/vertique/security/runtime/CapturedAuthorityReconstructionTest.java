// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.CapturedAuthorityReconstruction;
import dev.vertique.security.CarriageRequirement;
import dev.vertique.security.IdentityReconstructionException;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.runtime.authz.SecurityAuthzModule;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultCapturedAuthorityReconstruction} and {@link
 * CapturedAuthorityReconstructionModule} — Mode 3 of PRD identity-002 §14.3 Phase-2 Appendix
 * (FR-ID-CA-010). Covers the captured-claims-as-current-authority inversion, the per-target-kind
 * allowlist fail-closed gate, the startup REQUIRED-carriage validation, and the structural
 * never-default binding boundary (§14.6 P2.S4) — including that the opt-in module exposes only
 * the audited {@link CapturedAuthorityActivation} seam, never the raw, event-silent {@link
 * CapturedAuthorityReconstruction} as an injectable binding.
 */
class CapturedAuthorityReconstructionTest {

    private static final String HMAC_SECRET = "super-secret-signing-key-material";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String ALLOWED_KIND = "outbox";
    private static final String DISALLOWED_KIND = "cron";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));

    private static final SnapshotCarrierBinding ALLOWED_CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget(ALLOWED_KIND, "orders", Optional.empty()));
    private static final SnapshotCarrierBinding DISALLOWED_CARRIER =
            new SnapshotCarrierBinding("carrier-2", new DurableTarget(DISALLOWED_KIND, "nightly", Optional.empty()));

    @Test
    @DisplayName("DefaultCapturedAuthorityReconstruction is not publicly instantiable — no Dagger binding can "
            + "bypass the audited CapturedAuthorityActivation seam, though a deliberate hand-wired "
            + "CapturedAuthorityReconstruction implementation can (review)")
    void reconstructionImplNotPubliclyInstantiable() {
        assertFalse(
                java.lang.reflect.Modifier.isPublic(DefaultCapturedAuthorityReconstruction.class.getModifiers()),
                "DefaultCapturedAuthorityReconstruction must be package-private — application code must not "
                        + "instantiate it directly and bypass the audited CapturedAuthorityActivation seam");
        for (var ctor : DefaultCapturedAuthorityReconstruction.class.getDeclaredConstructors()) {
            assertFalse(
                    java.lang.reflect.Modifier.isPublic(ctor.getModifiers()),
                    "DefaultCapturedAuthorityReconstruction must have no public constructor");
        }
    }

    @Test
    @DisplayName("captured claims are presented as the reconstructed context's current authority under the "
            + "Mode-3 opt-in — contrast Mode 1's always-empty authorization")
    void capturedClaimsAuthorizeUnderOptIn() {
        IdentitySnapshotCodec codec =
                new IdentitySnapshotCodec(new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID));
        DefaultCapturedAuthorityReconstruction reconstruction =
                new DefaultCapturedAuthorityReconstruction(codec, Set.of(ALLOWED_KIND));

        AuthorityClaim roleClaim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        IdentitySnapshot snapshot =
                signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of(roleClaim));

        SecurityContext resumed = reconstruction.resumeWithCapturedAuthority(snapshot, matching(snapshot));

        assertEquals(
                Set.of(roleClaim),
                resumed.authorization().claims(),
                "the frozen snapshot claims must be presented as the context's current authority");
        assertTrue(resumed.reconstruction().isPresent(), "a captured-authority reconstruction must carry the marker");
        assertEquals(
                ReconstructedAuthorityMode.CAPTURED,
                resumed.reconstruction().get().mode(),
                "the marker's authority mode must be CAPTURED for a Mode-3 reconstruction");
        assertEquals(ACTOR, resumed.identity().actor());
        assertTrue(resumed.identity().subject().isPresent());
        assertEquals(SUBJECT, resumed.identity().subject().get());

        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");
        SecurityContext deferred =
                reconstruction.deferredExecutionWithCapturedAuthority(executingService, snapshot, matching(snapshot));
        assertEquals(
                Set.of(roleClaim),
                deferred.authorization().claims(),
                "deferredExecutionWithCapturedAuthority must also present the captured claims as current authority");
        assertEquals(
                ReconstructedAuthorityMode.CAPTURED,
                deferred.reconstruction().get().mode());
        assertEquals(executingService.actor(), deferred.identity().actor());
        assertTrue(deferred.identity().subject().isPresent());
        assertEquals(SUBJECT, deferred.identity().subject().get());

        // Contrast Mode 1: DefaultIdentityReconstruction never presents the same snapshot's claims as
        // current authority — its authorization dimension is always empty.
        DefaultIdentityReconstruction mode1 = new DefaultIdentityReconstruction(codec);
        SecurityContext mode1Ctx = mode1.resumeAsPrincipal(snapshot, matching(snapshot));
        assertEquals(
                AuthorizationClaims.empty(),
                mode1Ctx.authorization(),
                "Mode 1's reconstruction must never present the snapshot's claims as current authority");
    }

    @Test
    @DisplayName("a carrier target kind outside the allowlist fails closed on both entry points, never minting "
            + "a context")
    void disallowedTargetFailsClosed() {
        IdentitySnapshotCodec codec =
                new IdentitySnapshotCodec(new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID));
        DefaultCapturedAuthorityReconstruction reconstruction =
                new DefaultCapturedAuthorityReconstruction(codec, Set.of(ALLOWED_KIND));

        IdentitySnapshot snapshot = signedSnapshot(codec, DISALLOWED_CARRIER, ACTOR, Optional.empty(), List.of());
        DurableCarrierDescriptor carrier = matching(snapshot);

        IdentityReconstructionException resumeFailure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeWithCapturedAuthority(snapshot, carrier));
        assertTrue(
                resumeFailure.getMessage().contains(DISALLOWED_KIND),
                "the failure message must name the disallowed target kind");

        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");
        IdentityReconstructionException deferredFailure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.deferredExecutionWithCapturedAuthority(executingService, snapshot, carrier));
        assertTrue(
                deferredFailure.getMessage().contains(DISALLOWED_KIND),
                "the failure message must name the disallowed target kind");
    }

    @Test
    @DisplayName("resumeWithCapturedAuthority and deferredExecutionWithCapturedAuthority fail closed for a "
            + "validly-signed snapshot that is now past its effective expiry, even though it was fresh when "
            + "originally decoded")
    void expiredSnapshotFailsClosedOnActivation() {
        Instant signedAt = Instant.parse("2026-07-01T11:00:00Z");
        IdentitySnapshotCodec signingCodec = fixedClockCodec(signedAt);
        IdentitySnapshot snapshot =
                signedSnapshot(signingCodec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of(), signedAt);

        // The snapshot was fresh when decoded via signingCodec above. Activation now runs against a
        // codec whose "current" clock is 2h later — well past the snapshot's 1h signed window plus the
        // tolerated skew — modeling a typed snapshot retained in memory past its effective expiry.
        IdentitySnapshotCodec verifyingCodec = fixedClockCodec(signedAt.plus(Duration.ofHours(2)));
        DefaultCapturedAuthorityReconstruction reconstruction =
                new DefaultCapturedAuthorityReconstruction(verifyingCodec, Set.of(ALLOWED_KIND));

        IdentityReconstructionException resumeFailure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.resumeWithCapturedAuthority(snapshot, matching(snapshot)),
                "resumeWithCapturedAuthority must fail closed for a snapshot that is now past its effective "
                        + "expiry, not merely on integrity failure");
        assertEquals(
                SnapshotDegradationReason.EXPIRED,
                resumeFailure.reason(),
                "an expired-but-validly-signed snapshot must surface reason EXPIRED");

        SecurityIdentity executingService = SystemIdentities.scheduledJob("nightly-reconciliation");
        IdentityReconstructionException deferredFailure = assertThrows(
                IdentityReconstructionException.class,
                () -> reconstruction.deferredExecutionWithCapturedAuthority(
                        executingService, snapshot, matching(snapshot)),
                "deferredExecutionWithCapturedAuthority must fail closed for a snapshot that is now past its "
                        + "effective expiry, not merely on integrity failure");
        assertEquals(
                SnapshotDegradationReason.EXPIRED,
                deferredFailure.reason(),
                "an expired-but-validly-signed snapshot must surface reason EXPIRED");
    }

    @Test
    @DisplayName("the module provider rejects an allowlisted kind whose carriage requirement is not REQUIRED")
    void optionalCarriageTargetRejected() {
        IdentitySnapshotCodec codec =
                new IdentitySnapshotCodec(new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID));
        IdentitySnapshotConfig snapshotConfig = new IdentitySnapshotConfig(
                true,
                IdentitySnapshotDegradationPolicy.FAIL,
                new SnapshotHmacConfig(new SnapshotHmacKeyConfig(ACTIVE_KEY_ID, HMAC_SECRET), List.of()),
                null,
                null,
                30_000L,
                Map.of(ALLOWED_KIND, CarriageRequirement.OPTIONAL));
        CapturedAuthorityConfig capturedConfig = new CapturedAuthorityConfig(Set.of(ALLOWED_KIND));
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> CapturedAuthorityReconstructionModule.capturedAuthorityActivation(
                        codec, snapshotConfig, capturedConfig, emitter));
        assertTrue(failure.getMessage().contains(ALLOWED_KIND), "the failure must name the offending kind");
        assertTrue(
                failure.getMessage().contains("OPTIONAL"),
                "the failure must name the kind's actual (non-REQUIRED) carriage requirement");
    }

    @Test
    @DisplayName("CapturedAuthorityReconstruction has no injectable binding anywhere in the framework — not "
            + "PrivilegedIdentityModule, not SecurityAuthzModule, and not even the opt-in "
            + "CapturedAuthorityReconstructionModule itself, which exposes only the audited "
            + "CapturedAuthorityActivation seam")
    void reconstructionHasNoInjectableBinding_onlyActivationIsBound() {
        assertNoProviderReturns(PrivilegedIdentityModule.class, CapturedAuthorityReconstruction.class);
        assertNoProviderReturns(SecurityAuthzModule.class, CapturedAuthorityReconstruction.class);
        // W_c fix: the opt-in module itself must not expose the raw, event-silent reconstruction as
        // an injectable binding — otherwise a caller could inject it directly and bypass the
        // CapturedAuthorityActivatedEvent audit trail CapturedAuthorityActivation guarantees.
        assertNoProviderReturns(CapturedAuthorityReconstructionModule.class, CapturedAuthorityReconstruction.class);

        TestComponent component = DaggerCapturedAuthorityReconstructionTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(validConfigWithCapturedAuthority()))
                .build();

        assertNotNull(
                component.capturedAuthorityActivation(),
                "installing CapturedAuthorityReconstructionModule must bind CapturedAuthorityActivation — the "
                        + "only sanctioned, audited seam for putting captured authority into effect");
    }

    /**
     * Asserts that {@code moduleClass} declares no method whose return type is {@code returnType} —
     * i.e. no {@code @Provides} binding for it — proving the type is not bound by that module.
     *
     * @param moduleClass the Dagger module class to inspect
     * @param returnType  the binding type that must not be provided
     */
    private static void assertNoProviderReturns(Class<?> moduleClass, Class<?> returnType) {
        boolean anyMatch =
                Arrays.stream(moduleClass.getDeclaredMethods()).anyMatch(m -> returnType.equals(m.getReturnType()));
        assertFalse(
                anyMatch,
                moduleClass.getSimpleName() + " must not declare a provider returning " + returnType.getSimpleName());
    }

    /**
     * The trusted receive-side expected carrier that matches {@code snapshot}'s signed carrier.
     *
     * @param snapshot the snapshot whose carrier to mirror
     * @return the matching {@link DurableCarrierDescriptor}
     */
    private static DurableCarrierDescriptor matching(IdentitySnapshot snapshot) {
        return new DurableCarrierDescriptor(
                snapshot.carrier().carrierId(), snapshot.carrier().target());
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} bound to
     * {@code carrier}, round-tripped through the codec's {@code encode}/{@code decode} so it carries
     * a real, verifiable tag.
     *
     * @param codec   the codec used to sign and verify the snapshot
     * @param carrier the carrier binding to sign the snapshot for
     * @param actor   the acting principal to record on the snapshot content
     * @param subject the optional subject-on-behalf-of to record
     * @param claims  the authority claims to record
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            SnapshotCarrierBinding carrier,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            List<AuthorityClaim> claims) {
        return signedSnapshot(codec, carrier, actor, subject, claims, Instant.now());
    }

    /**
     * Builds a genuinely valid, HMAC-signed schema-v2 {@link IdentitySnapshot} bound to
     * {@code carrier}, signed with the given {@code issuedAt} (a 1h signed window from it) rather than
     * {@link Instant#now()} — so a caller can pin a snapshot's temporal envelope to a deterministic
     * instant, e.g. to later re-verify it against a codec whose clock has moved past its effective
     * expiry.
     *
     * @param codec    the codec used to sign and verify the snapshot
     * @param carrier  the carrier binding to sign the snapshot for
     * @param actor    the acting principal to record on the snapshot content
     * @param subject  the optional subject-on-behalf-of to record
     * @param claims   the authority claims to record
     * @param issuedAt the envelope's {@code issuedAt}; must not precede the fixed
     *                 {@code capturedAt}/{@code authenticatedAt} baked into this helper's content
     * @return a decoded, HMAC-verified {@link IdentitySnapshot}
     */
    private static IdentitySnapshot signedSnapshot(
            IdentitySnapshotCodec codec,
            SnapshotCarrierBinding carrier,
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            List<AuthorityClaim> claims,
            Instant issuedAt) {
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                actor,
                subject,
                Optional.empty(),
                Optional.empty(),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                claims,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                carrier,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", ACTIVE_KEY_ID, "unsigned"));
        return codec.decode(codec.encode(unsigned));
    }

    /**
     * Builds an {@link IdentitySnapshotCodec} whose {@link SnapshotFreshnessPolicy} clock is fixed at
     * {@code now}, with no carrier/snapshot lifetime budgets and a 30s clock skew — used to sign/verify
     * a snapshot at a deterministic instant, and to model a later reconstruction attempt by fixing the
     * clock further ahead.
     *
     * @param now the fixed instant this codec's freshness policy treats as "current"
     * @return the fixed-clock codec, sharing {@link #ACTIVE_KEY_ID}/{@link #HMAC_SECRET}
     */
    private static IdentitySnapshotCodec fixedClockCodec(Instant now) {
        return new IdentitySnapshotCodec(
                new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID),
                new SnapshotFreshnessPolicy(
                        Optional.empty(), Optional.empty(), Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC)));
    }

    /**
     * Builds a valid {@code identity.snapshot} config JSON carrying one active HMAC key, finite
     * freshness budgets, a {@code REQUIRED} carriage requirement for {@link #ALLOWED_KIND}, and a
     * {@code capturedAuthority.allowedTargetKinds} allowlist naming it — the minimum shape the
     * {@link CapturedAuthorityReconstructionModule} provider accepts.
     *
     * @return the config {@link JsonObject}
     */
    private static JsonObject validConfigWithCapturedAuthority() {
        return new JsonObject()
                .put(
                        "identity",
                        new JsonObject()
                                .put(
                                        "snapshot",
                                        new JsonObject()
                                                .put(
                                                        "hmacKeys",
                                                        new JsonObject()
                                                                .put(
                                                                        "active",
                                                                        new JsonObject()
                                                                                .put("keyId", ACTIVE_KEY_ID)
                                                                                .put("secretRef", HMAC_SECRET)))
                                                .put("maxCarrierLifetimeMs", 3_600_000L)
                                                .put("maxSnapshotLifetimeMs", 3_600_000L)
                                                .put(
                                                        "carriageRequirements",
                                                        new JsonObject().put(ALLOWED_KIND, "REQUIRED"))
                                                .put(
                                                        "capturedAuthority",
                                                        new JsonObject()
                                                                .put(
                                                                        "allowedTargetKinds",
                                                                        new JsonArray().add(ALLOWED_KIND)))));
    }

    // --- test Dagger component ---

    /** Minimal component including {@link CapturedAuthorityReconstructionModule} and its config deps. */
    @Singleton
    @Component(
            modules = {
                CapturedAuthorityReconstructionModule.class,
                IdentitySnapshotCarriageModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface TestComponent {

        /** Exposes the {@link CapturedAuthorityActivation} binding. */
        CapturedAuthorityActivation capturedAuthorityActivation();
    }

    /** Provides the {@code @VertxConfig JsonObject} from a caller-supplied value. */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        /**
         * Provides the application configuration as the {@code @VertxConfig} binding.
         *
         * @return the application configuration; non-null
         */
        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
