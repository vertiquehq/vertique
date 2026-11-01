// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

import dev.vertique.workflow.contract.IdempotencyKeyed;

/**
 * Start payload of the load workflow.
 *
 * <p>The key is the idempotency key of the start: a start that repeats a key returns the instance the first one
 * created, so a driver can send the same start again after a refusal without creating a second instance.
 *
 * @param key the caller-chosen key of the instance; unique per instance and stable across retries
 */
public record LoadStart(String key) implements IdempotencyKeyed {

    /**
     * Returns the key as the idempotency key of the start.
     *
     * @return the key
     */
    @Override
    public String idempotencyKey() {
        return key;
    }
}
