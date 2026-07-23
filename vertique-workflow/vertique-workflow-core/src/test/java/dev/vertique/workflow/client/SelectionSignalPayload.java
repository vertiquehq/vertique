// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal-payload record shared by the {@code "order.confirmed"} and {@code "order.shipped"} signal
 * methods on {@link SelectionWorkflow} (and its nested/fallback/broken variants), providing a dedup
 * key via {@link SignalDedupKeyed}.
 *
 * @param eventId the unique event identifier; used as the dedup key
 */
record SelectionSignalPayload(String eventId) implements SignalDedupKeyed {

    @Override
    public String dedupKey() {
        return eventId;
    }
}
