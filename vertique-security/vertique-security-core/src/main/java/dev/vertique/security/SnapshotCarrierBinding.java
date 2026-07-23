// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.context.DurableTarget;
import java.util.Objects;

/**
 * Binds a signed {@link IdentitySnapshot} to the specific durable row-carrier it was written for,
 * backing the F5 replay defense of PRD identity-002 (§14.6 amendment A9): a snapshot signed for one
 * carrier cannot be transplanted onto another row and reconstructed.
 *
 * <p>The {@link #carrierId()} is a framework-generated identity allocated <em>before</em> the durable
 * row is persisted (e.g. a delayed-job execution UUID, an outbox {@code carrier_id}, a workflow-timer
 * id), and the {@link #target()} names the durable destination that row was written for. Both are
 * signed into the snapshot envelope, so receive-side reconstruction can confirm the snapshot is being
 * reinstated against the same carrier identity it was encoded for rather than a replayed or mismatched
 * one.
 *
 * <p>Mirrors {@link dev.vertique.core.context.DurableCarrierDescriptor} (the seam-carried
 * expected-carrier facts) shape-for-shape — {@code carrierId} plus {@code target} — but is the value
 * <em>signed into</em> the snapshot, whereas {@link dev.vertique.core.context.DurableCarrierDescriptor}
 * is the trusted receive-side fact reconstruction checks it against.
 *
 * @param carrierId the identity of the durable row this snapshot is bound to; never {@code null} or
 *                  blank
 * @param target    the durable target the carrier row was written for; never {@code null}
 */
public record SnapshotCarrierBinding(String carrierId, DurableTarget target) {

    /**
     * Compact constructor — validates required fields.
     *
     * @throws NullPointerException     if {@code carrierId} or {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code carrierId} is blank
     */
    public SnapshotCarrierBinding {
        Objects.requireNonNull(carrierId, "carrierId");
        Objects.requireNonNull(target, "target");
        if (carrierId.isBlank()) {
            throw new IllegalArgumentException("carrierId must not be blank");
        }
    }
}
