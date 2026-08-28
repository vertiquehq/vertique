// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;
/** Observable local circuit states. */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
