// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.util.Map;

/**
 * Maps each registered {@link DestinationType} to the {@link ClaimScope} that this relay node
 * applies when claiming outbox rows of that type.
 *
 * <p>Built by the relay from the set of registered {@link OutboxDestinationHandler} instances:
 * each handler contributes a {@code (destinationType(), claimScope())} pair. The resulting map is
 * passed to {@link OutboxRepository#claimBatch} so the claim query admits only rows this node is
 * capable of delivering.
 *
 * <p>Destination types not present in {@link #byType()} are not claimed — a missing entry is
 * treated as "claim nothing" rather than "claim everything".
 *
 * @param byType immutable mapping from destination type to the claim scope that governs which rows
 *               of that type this relay node may claim; never {@code null}
 */
public record RelayCapabilities(Map<DestinationType, ClaimScope> byType) {

    /**
     * Compact constructor that defensively copies the map to ensure immutability.
     *
     * @param byType mutable or immutable map from destination type to claim scope
     */
    public RelayCapabilities {
        byType = Map.copyOf(byType);
    }
}
