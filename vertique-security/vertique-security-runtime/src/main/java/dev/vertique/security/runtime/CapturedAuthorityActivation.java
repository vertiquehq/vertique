// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.CapturedAuthorityReconstruction;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.events.CapturedAuthorityActivatedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.Objects;

/**
 * The sanctioned Mode-3 activation entry point (PRD identity-002 §14.3 Phase-2 Appendix, §14.6
 * P2.S4b) — the "consuming infrastructure" that {@link CapturedAuthorityReconstruction}'s javadoc
 * reserves for emitting the activation-audit event once captured authority is actually put into
 * effect. {@link CapturedAuthorityReconstruction} itself stays event-silent (FR-ID-CA-007): a
 * plain interface method cannot invoke an injected emitter by construction, and this type is what
 * closes that gap on the caller's side rather than reconstruction's.
 *
 * <p><strong>Emit-and-await is an ordering guarantee, not a durable-delivery guarantee.</strong>
 * Both entry points invoke the matching {@link CapturedAuthorityReconstruction} method, then fan
 * out a {@link CapturedAuthorityActivatedEvent} via {@link SecurityEventEmitter} and resolve only
 * once that emission completes. {@link SecurityEventEmitter#emit} always succeeds — per {@code
 * SecurityEventEmitter}'s AC-SE-6 per-observer isolation, a synchronously-thrown or
 * asynchronously-failed observer is caught, logged, and treated as settled, never retried or
 * escalated — and its returned future resolving means every registered
 * {@link SecurityEventObserver} has been invoked and has settled, one way or the other. A caller
 * awaiting {@link #activateResume} or {@link #activateDeferred} is therefore guaranteed the
 * activation-audit event was <em>emitted to and settled by</em> every observer before it observes
 * the reconstructed {@link SecurityContext} — an <strong>emission-ordering</strong> guarantee.
 * This is <strong>not</strong> a durable or acknowledged audit-delivery guarantee: an observer
 * that fails to durably persist the event is isolated the same as one that succeeds, and this
 * seam has no fail-closed acknowledgement channel back to the caller. A fail-closed, acknowledged
 * audit sink is a future need, not something this seam provides today.
 *
 * <p>A reconstruction failure — fail-closed integrity verification, carrier mismatch, or
 * allowlist denial, all of which {@link CapturedAuthorityReconstruction} raises as a
 * synchronously thrown {@code IdentityReconstructionException} — surfaces here as a
 * <strong>failed</strong> {@link Future}, never a thrown exception, so callers can rely on the
 * {@code Future}-returning async contract throughout rather than wrapping calls in a
 * {@code try/catch}.
 *
 * <p>This type is the <strong>only</strong> binding {@code CapturedAuthorityReconstructionModule}
 * exposes for Mode-3 activation — that module constructs the raw {@link
 * CapturedAuthorityReconstruction} collaborator internally and hands it to this type's
 * constructor directly (a plain constructor call inside the module's {@code @Provides} method, not
 * Dagger constructor injection), so the raw reconstruction seam is never itself an injectable
 * binding a caller could obtain to bypass the activation-audit event. The module must always be
 * installed alongside {@code SecurityEventsModule} (for the {@link SecurityEventEmitter} binding
 * this type needs) in the application's Dagger component.
 */
public final class CapturedAuthorityActivation {

    private final CapturedAuthorityReconstruction reconstruction;
    private final SecurityEventEmitter emitter;

    /**
     * Constructs a {@code CapturedAuthorityActivation} backed by the given reconstruction service
     * and event emitter.
     *
     * <p>Deliberately <strong>not</strong> a Dagger {@code @Inject} constructor — see the class
     * javadoc. {@code CapturedAuthorityReconstructionModule} is this constructor's only sanctioned
     * caller, invoking it directly with an internally-constructed reconstruction collaborator, so
     * that collaborator never becomes an independently injectable binding.
     *
     * @param reconstruction the Mode-3 reconstruction service this seam invokes; must not be
     *                       {@code null}
     * @param emitter        the security-event emitter used to fan out the activation-audit
     *                       event; must not be {@code null}
     */
    public CapturedAuthorityActivation(CapturedAuthorityReconstruction reconstruction, SecurityEventEmitter emitter) {
        this.reconstruction = Objects.requireNonNull(reconstruction, "reconstruction");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    /**
     * Activates Mode-3 captured authority for a resumed principal — mirroring
     * {@link CapturedAuthorityReconstruction#resumeWithCapturedAuthority} — then emits and awaits
     * the {@link CapturedAuthorityActivatedEvent}.
     *
     * @param snapshot        the snapshot to reconstruct from; must not be {@code null}
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for; must
     *                        not be {@code null}
     * @return a {@link Future} that resolves to the reconstructed {@link SecurityContext} once the
     *         activation-audit event has been delivered to every observer, or fails when
     *         reconstruction itself fails
     */
    public Future<SecurityContext> activateResume(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
        return Future.succeededFuture().compose(v -> {
            SecurityContext ctx = reconstruction.resumeWithCapturedAuthority(snapshot, expectedCarrier);
            return activate(ctx, snapshot);
        });
    }

    /**
     * Activates Mode-3 captured authority for deferred execution — mirroring
     * {@link CapturedAuthorityReconstruction#deferredExecutionWithCapturedAuthority} — then emits
     * and awaits the {@link CapturedAuthorityActivatedEvent}.
     *
     * @param executingServiceIdentity the identity of the system component executing the deferred
     *                                 work; must not be {@code null}
     * @param snapshot                 the snapshot describing the identity that scheduled the
     *                                 work; must not be {@code null}
     * @param expectedCarrier          the durable row-carrier the receiving dispatch was written
     *                                 for; must not be {@code null}
     * @return a {@link Future} that resolves to the reconstructed {@link SecurityContext} once the
     *         activation-audit event has been delivered to every observer, or fails when
     *         reconstruction itself fails
     */
    public Future<SecurityContext> activateDeferred(
            SecurityIdentity executingServiceIdentity,
            IdentitySnapshot snapshot,
            DurableCarrierDescriptor expectedCarrier) {
        return Future.succeededFuture().compose(v -> {
            SecurityContext ctx = reconstruction.deferredExecutionWithCapturedAuthority(
                    executingServiceIdentity, snapshot, expectedCarrier);
            return activate(ctx, snapshot);
        });
    }

    /**
     * Builds and emits the {@link CapturedAuthorityActivatedEvent} for a freshly reconstructed
     * context, awaiting full observer delivery before resolving to {@code ctx}.
     *
     * <p>The event's correlation is always {@link CorrelationContext#unbound()} — Mode-3
     * activation is not necessarily tied to a live inbound request (e.g. a scheduled job or
     * workflow resume) — and its {@code origin} mirrors {@code ctx.origin()}, which a Mode-3
     * reconstruction always leaves {@link java.util.Optional#empty()}.
     *
     * @param ctx      the reconstructed context whose subject-of-record becomes the event's
     *                 activated principal
     * @param snapshot the snapshot whose carrier target becomes the event's durable target
     * @return a {@link Future} resolving to {@code ctx} once every observer has settled
     */
    private Future<SecurityContext> activate(SecurityContext ctx, IdentitySnapshot snapshot) {
        PrincipalRef principal = ctx.identity().subject().orElse(ctx.identity().actor());
        CapturedAuthorityActivatedEvent event = new CapturedAuthorityActivatedEvent(
                Instant.now(),
                CorrelationContext.unbound(),
                ctx.origin(),
                principal,
                snapshot.carrier().target());
        return emitter.emit(event).map(v -> ctx);
    }
}
