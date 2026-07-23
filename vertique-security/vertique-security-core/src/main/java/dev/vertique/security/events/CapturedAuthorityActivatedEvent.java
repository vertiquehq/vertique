// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Event fired when Mode-3 <strong>captured authority</strong> is actually put into effect — a
 * durably-captured {@link dev.vertique.security.IdentitySnapshot}'s frozen authorization claims
 * are presented as a reconstructed {@link dev.vertique.security.SecurityContext}'s
 * <strong>current</strong> authority (PRD identity-002 §14.3 Phase-2 Appendix, FR-ID-CA-010).
 *
 * <p><strong>Emitted-and-awaited by the consuming infrastructure.</strong>
 * {@link dev.vertique.security.CapturedAuthorityReconstruction} itself is deliberately
 * event-silent per FR-ID-CA-007 — a plain interface method cannot invoke an injected emitter by
 * construction. This event is instead emitted, and its delivery awaited, by the sanctioned
 * activation seam ({@code CapturedAuthorityActivation} in {@code vertique-security-runtime}) once
 * a reconstruction call has actually succeeded — never by reconstruction itself. Awaiting delivery
 * before the reconstructed context is handed back to the caller makes this event an
 * audit-visible, guaranteed-delivered record of a privileged captured-authority resume, not a
 * best-effort side channel.
 *
 * <p>All fields are non-null after construction; the {@code origin} optional carries the
 * {@link RequestOrigin} when network-envelope information was available on the activated
 * {@link dev.vertique.security.SecurityContext} — a Mode-3 reconstruction never carries one, so in
 * practice this is always {@link Optional#empty()} for this event, mirrored here rather than
 * hard-coded so the shape stays consistent with the other security events.
 *
 * @param occurredAt wall-clock instant when captured authority was activated; never null
 * @param correlation correlation context for the activation; never null — activation is not
 *                     necessarily tied to a live inbound request (e.g. a scheduled job or workflow
 *                     resume), so this is typically {@link CorrelationContext#unbound()}
 * @param origin      captured network-envelope facts carried by the activated context, when
 *                     present; non-null {@link Optional} — use {@link Optional#empty()} when no
 *                     origin was captured
 * @param principal    the activated principal — the subject-of-record whose captured authority
 *                     was activated; never null
 * @param target       the durable target the captured authority was activated for; never null
 */
public record CapturedAuthorityActivatedEvent(
        Instant occurredAt,
        CorrelationContext correlation,
        Optional<RequestOrigin> origin,
        PrincipalRef principal,
        DurableTarget target) {

    /**
     * Compact constructor — validates that all fields are non-null.
     */
    public CapturedAuthorityActivatedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(target, "target");
    }
}
