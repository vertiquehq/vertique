// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import jakarta.annotation.Nullable;

/**
 * Bundles the per-delivery parameters of a workflow signal into a single value after the initial
 * dedup check, avoiding a 4-argument hand-off into {@code doApplySignal}.
 *
 * <p>The signal name, payload and dedup key are non-null at the call site because the engine
 * validates them before bundling.
 *
 * @param signalName the name of the signal being delivered; must match the {@code waitKey} stored
 *     on the waiting instance
 * @param payload the incoming signal payload; may require coercion to the declared type
 * @param dedupKey the caller-supplied dedup key used to prevent double delivery
 * @param callerContractName the logical name of the contract of the client that sends the signal, or
 *     {@code null} when the caller names no contract and no contract check applies
 */
record SignalDeliveryContext(
        String signalName,
        Object payload,
        String dedupKey,
        @Nullable String callerContractName,
        @Nullable Long commandId) {

    /**
     * Bundles a delivery that is not tied to a recorded command and whose caller names a contract.
     *
     * @param signalName the name of the signal being delivered
     * @param payload the incoming signal payload
     * @param dedupKey the caller-supplied dedup key
     * @param callerContractName the contract name of the caller, or {@code null}
     */
    SignalDeliveryContext(String signalName, Object payload, String dedupKey, @Nullable String callerContractName) {
        this(signalName, payload, dedupKey, callerContractName, null);
    }

    /**
     * Bundles a delivery whose caller names no contract.
     *
     * @param signalName the name of the signal being delivered
     * @param payload the incoming signal payload
     * @param dedupKey the caller-supplied dedup key
     */
    SignalDeliveryContext(String signalName, Object payload, String dedupKey) {
        this(signalName, payload, dedupKey, null, null);
    }
}
