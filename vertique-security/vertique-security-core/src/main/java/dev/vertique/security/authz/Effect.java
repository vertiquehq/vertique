// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

/**
 * The effect a {@link PolicyStatement} has on the actions it names.
 *
 * <p>V1 of the action-policy model is <strong>allow-only</strong>: a policy can only grant actions,
 * never deny them. Authorization is therefore default-deny — an action is permitted only if some
 * statement explicitly {@link #ALLOW}s it. An explicit {@code DENY} effect is intentionally not part
 * of this enum; should one ever be added, the evaluation precedence would itself become a new
 * load-bearing decision requiring its own ADR.
 */
public enum Effect {

    /** Grants the actions named by the statement. */
    ALLOW
}
