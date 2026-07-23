// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

/**
 * Optional payload-side interface that provides the idempotency key for a {@link WorkflowStart}
 * operation.
 *
 * <p>If the start payload implements this interface, the proxy will call {@link #idempotencyKey()}
 * to populate the {@code StartCommand.idempotencyKey} field. A parameter-level {@link IdempotencyKey}
 * annotation takes precedence if both are present.
 *
 * <p>Proxy creation fails if neither this interface nor {@code @IdempotencyKey} is available on
 * the {@code @WorkflowStart} method.
 */
public interface IdempotencyKeyed {

    /**
     * Returns the idempotency key for this payload.
     *
     * @return the idempotency key; must be non-null and stable across retries for the same
     *     logical start request
     */
    String idempotencyKey();
}
