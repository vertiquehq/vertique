// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Resilience concerns enabled for one logical execution. */
public enum ResilienceConcern {
    TIMEOUT,
    RETRY,
    CIRCUIT_BREAKER,
    BULKHEAD
}
