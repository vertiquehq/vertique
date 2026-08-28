// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Safe failure categories used by retry events. */
public enum ResilienceFailureCategory {
    APPLICATION_FAILURE,
    POLICY_FAILURE,
    TIMEOUT,
    RUNTIME_CLOSED
}
