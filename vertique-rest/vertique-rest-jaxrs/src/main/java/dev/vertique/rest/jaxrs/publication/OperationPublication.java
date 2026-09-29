// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * INTERNAL: one operation's detached publication within a {@link MountPublication}. Public only
 * for cross-module use by sibling framework modules; outside the maturity promise and not an
 * application contract.
 *
 * @param operationId           the operation id
 * @param httpMethod             the HTTP method the operation responds to
 * @param jaxRsPathTemplate      the operation's declared JAX-RS path template
 * @param vertxRouteValue        the route value exactly as registered with the Vert.x router
 * @param vertxRouteIsRegex      whether {@code vertxRouteValue} was registered as a regex route
 * @param effectivePolicy        the operation's effective, scope-folded security policy
 * @param securityRequirementSets the operation's security requirement sets
 * @param requiresAction         whether the operation declares an authorization action
 * @param detail                 the operation's captured detail; non-{@code null} exactly when a
 *                                sink wanted detail for the enclosing mount
 */
public record OperationPublication(
        String operationId,
        String httpMethod,
        String jaxRsPathTemplate,
        String vertxRouteValue,
        boolean vertxRouteIsRegex,
        SecurityPolicy effectivePolicy,
        List<SecurityRequirementSet> securityRequirementSets,
        boolean requiresAction,
        @Nullable OperationDetail detail) {

    /**
     * Compact constructor storing an unmodifiable copy of {@code securityRequirementSets}.
     */
    public OperationPublication {
        securityRequirementSets = List.copyOf(securityRequirementSets);
    }
}
