// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.events.IdentitySnapshotDegradationEvent;
import dev.vertique.security.runtime.IdentitySnapshotDegradationPolicy;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Service-dispatch {@link ServiceInterceptor} that consults the
 * {@link SnapshotDegradationMarker} bound by the receive-side identity-snapshot reconstruction
 * initializer and enforces the configured {@link IdentitySnapshotDegradationPolicy}
 * (PRD-ID-002 §14.3 "Durable carriage").
 *
 * <p>The receive-side {@code IdentitySnapshotReconstructionInitializer} runs synchronously and
 * cannot itself emit the {@link IdentitySnapshotDegradationEvent} — event emission is async
 * ({@link SecurityEventEmitter#emit(IdentitySnapshotDegradationEvent)} returns {@code Future<Void>}).
 * This gate is the async counterpart: it reads the marker (if any) off the {@link ContextHolder},
 * emits the degradation event, and only <em>then</em> applies the configured policy — {@code FAIL}
 * short-circuits the dispatch, {@code CONTINUE_WITHOUT_IDENTITY} lets it proceed with no verified subject.
 * The emit call is always awaited before the abort/continue decision, so the event can never be
 * skipped by the dispatch outcome racing ahead of it — an <em>emission-ordering</em> guarantee.
 *
 * <p>This is <strong>not</strong> a guarantee that an audit consumer actually received the event.
 * With the framework's default {@link SecurityEventEmitter}, per-observer failures are isolated
 * (identity-001 AC-SE-6) and the observer fan-out always succeeds, so an absent or failing audit
 * consumer never surfaces as an emit failure here — see {@link #beforeDispatch} for how this bounds
 * the fail-closed branch. See PRD identity-002 §13 for the deferred acknowledged-delivery path.
 *
 * <p>When no marker is bound — the overwhelmingly common path — {@link #beforeDispatch} is a
 * pure passthrough: no event is emitted and no policy is consulted.
 *
 * <p>Runs in the {@link ExtensionPhase#SYSTEM_FIRST} phase at a priority lower (earlier) than
 * {@link ServiceAuthorizationInterceptor}'s {@code -100}, so a {@code FAIL} verdict here preempts
 * the action gate: a dispatch carrying an unverifiable identity never reaches authorization at all.
 *
 * <p>The policy is injected as {@code Optional<IdentitySnapshotDegradationPolicy>} so this gate
 * constructs safely whether or not the application installs
 * {@code IdentitySnapshotCarriageModule} (identity-snapshot durable carriage is opt-in). When
 * absent, {@link IdentitySnapshotDegradationPolicy#FAIL} is assumed — matching
 * {@code IdentitySnapshotConfig}'s own fail-closed default when {@code onDegradation} is omitted
 * from config. In practice this default is never exercised: the marker this gate consults is
 * itself only ever bound by the receive-side reconstruction initializer, which is only wired when
 * carriage is installed.
 *
 * @see SnapshotDegradationMarker
 * @see IdentitySnapshotDegradationEvent
 * @see IdentitySnapshotDegradationPolicy
 */
@Slf4j
@Singleton
public final class SnapshotDegradationGate implements ServiceInterceptor {

    private final ContextHolder contextHolder;
    private final SecurityEventEmitter emitter;
    private final IdentitySnapshotDegradationPolicy policy;

    /**
     * Constructs the gate.
     *
     * @param contextHolder the request-scoped context holder used to read the bound
     *                      {@link SnapshotDegradationMarker} and {@link CorrelationContext}; must
     *                      not be {@code null}
     * @param emitter       the security event emitter used to emit the non-droppable
     *                      {@link IdentitySnapshotDegradationEvent}; must not be {@code null}
     * @param policy        the configured degradation policy ({@code FAIL} or
     *                      {@code CONTINUE_WITHOUT_IDENTITY}); {@link Optional#empty()} when identity-
     *                      snapshot durable carriage is not installed, in which case
     *                      {@link IdentitySnapshotDegradationPolicy#FAIL} is assumed; must not be
     *                      {@code null}
     */
    @Inject
    public SnapshotDegradationGate(
            ContextHolder contextHolder,
            SecurityEventEmitter emitter,
            Optional<IdentitySnapshotDegradationPolicy> policy) {
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.policy = Objects.requireNonNull(policy, "policy").orElse(IdentitySnapshotDegradationPolicy.FAIL);
    }

    // --- Ordering ---

    /**
     * {@inheritDoc}
     *
     * <p>The degradation gate is a security enforcement point, so it runs in the
     * {@link ExtensionPhase#SYSTEM_FIRST} phase — before application interceptors and the dispatch
     * pipeline.
     */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs earlier than {@link ServiceAuthorizationInterceptor}'s {@code -100} so a
     * {@code FAIL} verdict preempts authorization entirely — a dispatch carrying an unverifiable
     * identity never reaches the action gate.
     */
    @Override
    public int priority() {
        return -200;
    }

    // --- Runtime gate ---

    /**
     * Consults the bound {@link SnapshotDegradationMarker}, if any, and enforces the configured
     * {@link IdentitySnapshotDegradationPolicy}.
     *
     * <p>Two distinct paths fail the dispatch: a failed emission {@link Future} propagates as the
     * dispatch failure regardless of the configured policy, and — after a successful emission — the
     * {@code FAIL} policy aborts with a {@link SnapshotDegradationForbiddenException} while
     * {@code CONTINUE_WITHOUT_IDENTITY} proceeds. With the framework's default {@link SecurityEventEmitter},
     * per-observer failures are isolated (identity-001 AC-SE-6) and the observer fan-out always
     * succeeds, so an absent or failing audit consumer does <strong>not</strong> produce an emission
     * failure. The guarantee this gate makes is emission <em>ordering</em> — the emit future is
     * awaited before the FAIL/CONTINUE decision — not acknowledged audit <em>delivery</em>; see PRD
     * identity-002 §13 for the deferred acknowledged-delivery path.
     *
     * @param ctx the dispatch context; must not be {@code null}
     * @return a succeeded future carrying {@code ctx} unchanged when no marker is bound, or when a
     *     marker is bound and the policy is {@code CONTINUE_WITHOUT_IDENTITY}; a failed future carrying a
     *     {@link SnapshotDegradationForbiddenException} when a marker is bound and the policy is
     *     {@code FAIL}, or carrying the emission failure itself when the emit {@link Future} fails
     *     (regardless of policy). The degradation event is always emitted (and awaited) before
     *     either policy outcome.
     */
    @Override
    public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
        Optional<SnapshotDegradationMarker> marker = contextHolder.current(SnapshotDegradationMarker.class);
        if (marker.isEmpty()) {
            return Future.succeededFuture(ctx);
        }

        SnapshotDegradationMarker degradation = marker.get();
        CorrelationContext correlation =
                contextHolder.current(CorrelationContext.class).orElse(CorrelationContext.unbound());
        IdentitySnapshotDegradationEvent event = new IdentitySnapshotDegradationEvent(
                Instant.now(), correlation, degradation.origin(), degradation.reasonCode());

        return emitter.emit(event).compose(v -> {
            if (policy == IdentitySnapshotDegradationPolicy.FAIL) {
                log.debug(
                        "[{}] Degraded identity snapshot (reasonCode={}) failing dispatch per FAIL policy",
                        ctx.address(),
                        degradation.reasonCode());
                return Future.failedFuture(
                        new SnapshotDegradationForbiddenException("identity snapshot degraded (reasonCode="
                                + degradation.reasonCode() + ") and onDegradation policy is FAIL"));
            }
            log.debug(
                    "[{}] Degraded identity snapshot (reasonCode={}) proceeding per CONTINUE_WITHOUT_IDENTITY policy",
                    ctx.address(),
                    degradation.reasonCode());
            return Future.succeededFuture(ctx);
        });
    }
}
