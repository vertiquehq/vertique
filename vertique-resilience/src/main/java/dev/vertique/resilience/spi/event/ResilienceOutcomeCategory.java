// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Safe terminal outcome categories. */
public enum ResilienceOutcomeCategory {
    SUCCESS,
    APPLICATION_FAILURE,
    POLICY_FAILURE,
    TIMEOUT,
    CIRCUIT_OPEN,
    BULKHEAD_REJECTED,
    BULKHEAD_QUEUE_TIMEOUT,
    RUNTIME_CLOSED
}
