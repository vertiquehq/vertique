// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import java.util.List;

/**
 * An <strong>AND-group</strong> of scheme requirements: a request satisfies the set only if it
 * satisfies <em>every</em> scheme in it.
 *
 * <p>This is one alternative within an operation's security model. The operation's overall security
 * is an <strong>OR</strong> across sets — a request that satisfies <em>any one</em> set is
 * authorized — while the schemes <em>within</em> a set are an AND (all must be satisfied). Scopes
 * within a single scheme are themselves an AND. An empty outer list of sets means the operation is
 * public.
 *
 * <p>From Swagger annotations, every {@code @SecurityRequirement} maps to a separate single-scheme
 * set, so the operation's sets are an OR of single schemes. Multi-scheme sets (AND-within-a-set)
 * arise from {@code @SecurityRequirement(combine=…)} and the {@code openapi-contract} strategy.
 *
 * @param schemes the schemes that must <em>all</em> be satisfied for this set to be satisfied; a
 *     defensive immutable copy is stored
 */
public record SecurityRequirementSet(List<SecurityRequirement> schemes) {

    /**
     * Canonical constructor taking a defensive immutable copy of {@code schemes}.
     *
     * @param schemes the schemes that must all be satisfied for this set
     */
    public SecurityRequirementSet {
        schemes = List.copyOf(schemes);
    }

    /**
     * Returns whether this set contains exactly one scheme.
     *
     * @return {@code true} if the set has a single scheme, {@code false} otherwise
     */
    public boolean isSingleScheme() {
        return schemes.size() == 1;
    }

    /**
     * Returns whether any scheme in this set declares one or more scopes.
     *
     * @return {@code true} if at least one scheme has a non-empty scopes list, {@code false}
     *     otherwise
     */
    public boolean hasScopes() {
        return schemes.stream().anyMatch(s -> !s.scopes().isEmpty());
    }
}
