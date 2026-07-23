// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Bundles the per-delivery parameters of a workflow signal into a single value after the initial
 * dedup check, avoiding a 4-argument hand-off into {@code doApplySignal}.
 *
 * <p>All fields are non-null at the call site because the engine validates them before bundling.
 *
 * @param signalName the name of the signal being delivered; must match the {@code waitKey} stored
 *     on the waiting instance
 * @param payload the incoming signal payload; may require coercion to the declared type
 * @param dedupKey the caller-supplied dedup key used to prevent double delivery
 */
record SignalDeliveryContext(String signalName, Object payload, String dedupKey) {}
