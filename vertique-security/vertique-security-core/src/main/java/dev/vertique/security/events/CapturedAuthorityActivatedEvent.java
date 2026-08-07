// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Event fired when Mode-3 captured authority is put into effect — a durably-captured
 * {@link dev.vertique.security.IdentitySnapshot}'s frozen authorization claims are presented as a
 * reconstructed {@link dev.vertique.security.SecurityContext}'s current authority.
 *
 * <p><strong>Emitted and awaited by the activation seam</strong>
 * ({@code CapturedAuthorityActivation} in {@code vertique-security-runtime}), never by
 * {@link dev.vertique.security.CapturedAuthorityReconstruction}, which stays event-silent per
 * FR-ID-CA-007. Awaiting delivery before the reconstructed context reaches the caller is an
 * <strong>emission-ordering</strong> guarantee and, for an audit consumer, an
 * <strong>accepted-for-delivery</strong> one. It is <strong>not</strong> a durable or acknowledged
 * audit-delivery guarantee: an observer that fails to persist is isolated exactly as one that
 * succeeds.
 *
 * <p>This event carries the reconstructed identity <strong>uncollapsed</strong>. On the deferred
 * path {@code identity().actor()} is the executing service and {@code identity().subject()} is the
 * captured subject-of-record; on the resume path both come from the snapshot's own content. Callers
 * needing "whose authority is in effect" read {@code identity().subject().orElse(identity().actor())}
 * themselves rather than receiving a pre-collapsed principal.
 *
 * @param occurredAt     wall-clock instant when captured authority was activated; never null
 * @param correlation    correlation context for the activation; never null — activation is not
 *                       necessarily tied to a live inbound request, so this is typically
 *                       {@link dev.vertique.core.correlation.CorrelationContext#unbound()}
 * @param origin         captured network-envelope facts when present; non-null {@link Optional}.
 *                       A Mode-3 reconstruction never carries one, so in practice always empty
 * @param authentication the reconstructed authentication state — its {@code primaryMethod} is the
 *                       <em>original captured</em> method, not a reconstruction marker; never null
 * @param identity       the full reconstructed identity: actor, subject, delegation, client;
 *                       never null
 * @param mode           whether this activation resumed a captured session or began deferred
 *                       execution under captured authority; never null
 * @param activationId   identifier of <em>this activation occurrence</em>, minted fresh per
 *                       activation. Distinct activations of the same carrier row receive distinct
 *                       ids, so this is a safe source-event deduplication key; never null
 * @param carrier        the signed binding of the snapshot to its durable row — {@code carrierId}
 *                       plus {@code target}; never null
 */
public record CapturedAuthorityActivatedEvent(
        Instant occurredAt,
        CorrelationContext correlation,
        Optional<RequestOrigin> origin,
        AuthenticationState authentication,
        SecurityIdentity identity,
        Mode mode,
        UUID activationId,
        SnapshotCarrierBinding carrier) {

    /** Which Mode-3 activation entry point put the captured authority into effect. */
    public enum Mode {
        /** A captured session was resumed for its own subject-of-record. */
        RESUME,
        /** A system component began deferred execution under a captured subject's authority. */
        DEFERRED
    }

    /** Compact constructor — validates that all components are non-null. */
    public CapturedAuthorityActivatedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(activationId, "activationId");
        Objects.requireNonNull(carrier, "carrier");
    }
}
