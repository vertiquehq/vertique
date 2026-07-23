// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, audit-safe summary of a delegated ("on-behalf-of") relationship carried on an
 * {@link IdentitySnapshot}.
 *
 * <p>Mirrors the shape of the shipped {@code DelegationContext} but is deliberately narrowed to
 * {@code kind} and {@code authorityId} only — no delegation reason or free-form attributes — so
 * that a snapshot never carries more than the minimal facts needed to reconstruct the delegated
 * identity structure at a later point in time.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code kind} is required (non-null, non-blank)</li>
 *   <li>{@code authorityId} is required (non-null {@link Optional}); {@link Optional#empty()} when
 *       the delegation carries no specific grant or policy identifier</li>
 * </ul>
 *
 * @param kind        the delegation kind (e.g. {@code "on-behalf-of"})
 * @param authorityId identifier of the specific grant or policy backing this delegation; empty
 *                    when not applicable
 */
public record DelegationSummary(String kind, Optional<String> authorityId) {

    /**
     * Compact constructor — validates required fields.
     */
    public DelegationSummary {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Objects.requireNonNull(authorityId, "authorityId");
    }
}
