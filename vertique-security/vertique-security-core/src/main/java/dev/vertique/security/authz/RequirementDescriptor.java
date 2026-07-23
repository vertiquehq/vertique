// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;

/**
 * Immutable, audit-safe description of a requirement an {@link AuthorizationNarrower} places on an
 * action for a given actor.
 *
 * <p>Surfaced by {@link AuthorizationNarrower#requirementFor(dev.vertique.security.SecurityContext, ActionRef)} and
 * carried on an {@link ActionCapability}. Like {@link AuthorizationDecision#safeAttributes()}, both
 * components are intended to be shown to the caller (e.g. in a capability-introspection response),
 * so implementations MUST NOT place sensitive data (tokens, credentials, PII) in either field.
 *
 * @param kind   a stable, machine-readable category for the requirement (e.g.
 *               {@code "DELEGATION_GRANT"}); must not be blank
 * @param detail a human-readable description of the requirement; must not be {@code null}, may be
 *               blank
 */
public record RequirementDescriptor(String kind, String detail) {

    /**
     * Compact constructor — validates required fields.
     */
    public RequirementDescriptor {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Objects.requireNonNull(detail, "detail");
    }
}
