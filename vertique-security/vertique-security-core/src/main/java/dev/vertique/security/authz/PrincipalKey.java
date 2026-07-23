// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import java.util.Objects;

/**
 * The capability-minimized durable principal key used to re-resolve a reconstructed principal's
 * <strong>current</strong> authority (PRD identity-002 §14.3 Phase-2 Appendix, A8/FR-ID-CA-012).
 *
 * <p>{@code id} is treated as <strong>opaque</strong>: the framework never parses tenant, issuer,
 * or realm delimiters out of it, mirroring {@link PrincipalRef}'s {@code (type, id)} durable
 * identity tuple.
 *
 * <p>Deliberately carries <strong>no attributes</strong>. A {@link PrincipalAuthorityResolver}
 * receives only {@code (type, id)}; the absence of an attributes accessor on this type is a
 * structural guarantee — not merely a convention — that a resolver cannot resolve authority scope
 * from request-scoped attributes: it must consult durable, principal-keyed authority storage.
 *
 * @param type the kind of principal
 * @param id   the principal's stable, opaque identifier; must not be blank
 */
public record PrincipalKey(PrincipalType type, String id) {

    /**
     * Compact constructor — validates that {@code type} is non-null and {@code id} is non-null and
     * non-blank.
     */
    public PrincipalKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
