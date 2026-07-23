// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code SIGNAL_RECEIVED} history entry.
 *
 * <p>Records the signal name, dedup key, and coerced payload at the point the engine applies a
 * signal to a waiting instance. This payload is a required input for the compensation-matching
 * algorithm: {@code signalName} is used to correlate dispatch occurrences with received signals.
 *
 * @param signalName the name of the signal that was delivered
 * @param dedupKey the caller-supplied dedup key used to prevent double delivery
 * @param payload the signal payload after type coercion; may be {@code null} for signals with no
 *     payload type declared
 */
record SignalReceivedHistoryPayload(String signalName, String dedupKey, Object payload) {}
