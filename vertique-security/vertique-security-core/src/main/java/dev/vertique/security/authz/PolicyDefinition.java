// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.List;
import java.util.Objects;

/**
 * A named authorization policy: a stable {@code name} and an ordered list of {@link PolicyStatement}s.
 *
 * <p>A policy is the unit a {@link PolicyDefinitionSource} contributes and that a
 * {@link RolePolicyResolver} maps roles to (by {@code name}). In the allow-only V1 model an action is
 * permitted by a policy when any of its statements allows it. The statement list is defensively
 * copied to an unmodifiable {@link List} by the compact constructor, so a policy is immutable and
 * safe to share once constructed.
 *
 * @param name       the policy's stable identifier; must not be {@code null} or blank
 * @param statements the policy's statements; must not be {@code null} (an empty list is permitted and
 *                   grants nothing)
 */
public record PolicyDefinition(String name, List<PolicyStatement> statements) {

    /**
     * Compact constructor — validates a non-blank name and a non-null statement list, and defensively
     * copies {@code statements} to an unmodifiable list.
     *
     * @throws NullPointerException     if {@code name} or {@code statements} (or any contained
     *                                  statement) is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public PolicyDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("policy name must not be blank");
        }
        Objects.requireNonNull(statements, "statements");
        statements = List.copyOf(statements);
    }
}
