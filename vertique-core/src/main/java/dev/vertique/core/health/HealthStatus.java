// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

/**
 * Health status of a component or the overall application.
 */
public enum HealthStatus {
    /** The component is functioning normally. */
    UP,
    /** The component is unavailable or unhealthy. */
    DOWN
}
