// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.events.CapturedAuthorityActivatedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for the Mode-3 activation seam ({@link CapturedAuthorityActivation}) and the
 * {@link CapturedAuthorityActivatedEvent} it emits — PRD identity-002 §14.3 Phase-2 Appendix,
 * §14.6 P2.S4b-ii (FR-ID-CA-007, FR-ID-CA-010).
 *
 * <p>Verifies the emit-and-await contract: {@link CapturedAuthorityActivation#activateResume}
 * invokes the underlying {@link CapturedAuthorityReconstruction} synchronously, builds a
 * {@link CapturedAuthorityActivatedEvent} carrying the activated subject-of-record and the
 * snapshot's durable target, fans it out via {@link SecurityEventEmitter}, and its returned
 * {@link Future} resolves only <em>after</em> every registered observer's future has settled — not
 * merely after the observer method was invoked.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CapturedAuthorityActivatedEventTest {

    private static final String HMAC_SECRET = "super-secret-signing-key-material";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String ALLOWED_KIND = "outbox";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));

    private static final SnapshotCarrierBinding ALLOWED_CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget(ALLOWED_KIND, "orders", Optional.empty()));

    @Test
    @DisplayName("activateResume emits the CapturedAuthorityActivatedEvent and awaits full observer delivery "
            + "before resolving")
    void emittedAndAwaitedOnActivation(Vertx vertx, VertxTestContext testCtx) {
        IdentitySnapshotCodec codec =
                new IdentitySnapshotCodec(new SnapshotHmac(Map.of(ACTIVE_KEY_ID, HMAC_SECRET), ACTIVE_KEY_ID));
        DefaultCapturedAuthorityReconstruction reconstruction =
                new DefaultCapturedAuthorityReconstruction(codec, Set.of(ALLOWED_KIND));

        AuthorityClaim roleClaim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of());
        IdentitySnapshot snapshot =
                signedSnapshot(codec, ALLOWED_CARRIER, ACTOR, Optional.of(SUBJECT), List.of(roleClaim));
        DurableCarrierDescriptor expectedCarrier = matching(snapshot);

        AtomicBoolean observerSettled = new AtomicBoolean(false);
        AtomicReference<CapturedAuthorityActivatedEvent> captured = new AtomicReference<>();

        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onCapturedAuthorityActivated(CapturedAuthorityActivatedEvent event) {
                captured.set(event);
                Promise<Void> promise = Promise.promise();
                // Genuinely asynchronous completion (not resolved on the calling thread) so the
                // test can prove the activation future does not resolve before this settles.
                vertx.setTimer(50, id -> {
                    observerSettled.set(true);
                    promise.complete();
                });
                return promise.future();
            }
        };

        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
        CapturedAuthorityActivation activation = new CapturedAuthorityActivation(reconstruction, emitter);

        activation
                .activateResume(snapshot, expectedCarrier)
                .onComplete(testCtx.succeeding(ctx -> testCtx.verify(() -> {
                    assertTrue(
                            observerSettled.get(),
                            "the activation future must not resolve before the observer's own future settled");

                    assertEquals(
                            ReconstructedAuthorityMode.CAPTURED,
                            ctx.reconstruction().orElseThrow().mode(),
                            "the resolved context must be the CAPTURED-mode reconstruction");
                    assertEquals(
                            Set.of(roleClaim),
                            ctx.authorization().claims(),
                            "the resolved context must carry the snapshot's captured claims as current authority");

                    CapturedAuthorityActivatedEvent event = captured.get();
                    assertNotNull(event, "the observer must have received exactly one activation event");
                    assertEquals(
                            SUBJECT,
                            event.principal(),
                            "the activated principal must be the subject-of-record, not the acting service");
                    assertEquals(
                            ALLOWED_CARRIER.target(),
                            event.target(),
                            "the event's target must be the snapshot's carrier target");

                    testCtx.completeNow();
                })));
    }

    /**
     * The trusted receive-side expected carrier that matches {@code snapshot}'s signed carrier.
     * Mirrors {@code CapturedAuthorityReconstructionTest.matching}.
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
     * {@code carrier}, round-tripped through the codec's {@code encode}/{@code decode} so it
     * carries a real, verifiable tag. Mirrors {@code CapturedAuthorityReconstructionTest.signedSnapshot}.
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
        Instant issuedAt = Instant.now();
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2,
                content,
                carrier,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", ACTIVE_KEY_ID, "unsigned"));
        return codec.decode(codec.encode(unsigned));
    }
}
