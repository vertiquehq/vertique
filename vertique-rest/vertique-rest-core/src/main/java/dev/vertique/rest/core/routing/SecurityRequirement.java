// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import java.util.List;

/**
 * An effective security requirement on a REST operation: a named security scheme together with the
 * scopes required to satisfy it.
 *
 * <p>This is the rest-core neutral replacement for the vertx-openapi security-requirement type. The
 * framework resolves requirements operation-level-else-global and applies the registered
 * authentication handler for each named scheme.
 *
 * <p>A defensive immutable copy of {@code scopes} is stored, mirroring {@link SecurityRequirementSet};
 * a caller cannot mutate this requirement's scopes through the source list it passed in.
 *
 * @param schemeName the name of the security scheme (e.g. {@code "bearerAuth"})
 * @param scopes     the scopes required for this scheme; empty when the scheme requires only
 *                   authentication. A defensive immutable copy is stored; must not be {@code null}
 *                   (and must contain no {@code null} elements)
 */
public record SecurityRequirement(String schemeName, List<String> scopes) {

    /**
     * Canonical constructor taking a defensive immutable copy of {@code scopes}.
     *
     * @param schemeName the name of the security scheme
     * @param scopes     the required scopes; must not be {@code null} (and must contain no
     *                   {@code null} elements)
     */
    public SecurityRequirement {
        scopes = List.copyOf(scopes);
    }
}
