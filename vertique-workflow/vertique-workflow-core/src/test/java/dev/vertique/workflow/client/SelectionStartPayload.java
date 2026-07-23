// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.contract.IdempotencyKeyed;

/**
 * Start-payload record for the {@link SelectionWorkflow} contract, providing an idempotency key
 * via {@link IdempotencyKeyed}.
 *
 * @param orderId the unique order identifier; used as the idempotency key
 */
record SelectionStartPayload(String orderId) implements IdempotencyKeyed {

    @Override
    public String idempotencyKey() {
        return orderId;
    }
}
