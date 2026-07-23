// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;

/**
 * Identifies the durable row-carrier a piece of propagated context is bound to.
 *
 * <p>A carrier descriptor pins an in-flight durable envelope to the specific persisted row (the
 * {@code carrierId}) and the {@link DurableTarget} that row was written for. It backs the F5 replay
 * defense of PRD identity-002: when a durable metadata blob is decoded at a boundary, the decoder
 * can confirm the blob is being reinstated against the same carrier identity it was encoded for,
 * rather than a replayed or mismatched one.
 *
 * <p>The type lives in {@code vertique-core} because the durable seam is a core concept; it is also
 * referenced by the security snapshot envelope in downstream modules.
 *
 * @param carrierId the identity of the durable row the context is bound to; never {@code null} or blank
 * @param target the durable target the carrier row was written for; never {@code null}
 */
public record DurableCarrierDescriptor(String carrierId, DurableTarget target) {

    /**
     * Canonical constructor.
     *
     * @param carrierId the identity of the durable row the context is bound to
     * @param target the durable target the carrier row was written for
     * @throws NullPointerException if {@code carrierId} or {@code target} is {@code null}
     * @throws IllegalArgumentException if {@code carrierId} is blank
     */
    public DurableCarrierDescriptor {
        Objects.requireNonNull(carrierId, "carrierId must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (carrierId.isBlank()) {
            throw new IllegalArgumentException("carrierId must not be blank");
        }
    }
}
