// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Collection;

/**
 * SPI a subsystem implements to contribute its {@link ActionDefinition}s to the framework-wide
 * {@link ActionRegistry}.
 *
 * <p>Contributors are aggregated via Dagger {@code @IntoSet} multibinding and consulted once at
 * startup, when the registry is built. The registry validates the union of all contributed
 * definitions and fails fast on duplicates or grammar violations, so each contributor should return
 * a stable, self-consistent set of actions owned by its subsystem.
 */
public interface ActionContributor {

    /**
     * Returns the action definitions this contributor declares.
     *
     * <p>Called once during registry construction. The returned collection should be effectively
     * immutable for the lifetime of the application and must not contain {@code null} entries.
     *
     * @return the contributed action definitions; never {@code null}
     */
    Collection<ActionDefinition> actions();
}
