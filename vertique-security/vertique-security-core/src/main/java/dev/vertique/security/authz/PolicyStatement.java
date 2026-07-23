// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;
import java.util.Set;

/**
 * A single statement within a {@link PolicyDefinition}: an {@link Effect} applied to a set of
 * {@link ActionPattern}s.
 *
 * <p>In the allow-only V1 model a statement with {@link Effect#ALLOW} grants every action matched by
 * any of its {@code actions} patterns. The pattern set is defensively copied to an unmodifiable
 * {@link Set} by the compact constructor, so a statement is immutable and safe to share once
 * constructed.
 *
 * @param effect  the effect this statement applies; must not be {@code null}
 * @param actions the action patterns the effect applies to; must not be {@code null} (an empty set is
 *                permitted and matches no action)
 */
public record PolicyStatement(Effect effect, Set<ActionPattern> actions) {

    /**
     * Compact constructor — validates non-null fields and defensively copies {@code actions} to an
     * unmodifiable set.
     *
     * @throws NullPointerException if {@code effect} or {@code actions} (or any contained pattern) is
     *                              {@code null}
     */
    public PolicyStatement {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(actions, "actions");
        actions = Set.copyOf(actions);
    }
}
