// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;

/**
 * Captures the merged canonicalizer and sanitizer chains for a given request invocation.
 *
 * <p>Route-level chains are derived from {@code @Canonicalize} / {@code @Sanitize} annotations
 * on the JAX-RS resource class and method, and are applied to ALL string values in the body.
 * Object-level and field-level chains are resolved separately by
 * {@link InputPolicyMetadataResolver} from the target DTO type.
 *
 * @param routeCanonicalizers route-level canonicalizer chain (from resource class + method)
 * @param routeSanitizers     route-level sanitizer chain (from resource class + method)
 */
public record EffectiveInputPolicies(
        List<Class<? extends Canonicalizer>> routeCanonicalizers, List<Class<? extends Sanitizer>> routeSanitizers) {

    /** Empty policies — no route-level processing. */
    public static final EffectiveInputPolicies NONE = new EffectiveInputPolicies(List.of(), List.of());

    /**
     * Returns {@code true} if no route-level processors are configured.
     *
     * <p>Object-level and field-level processors may still be active based on the target
     * type's annotation metadata — this method only reflects the route-level chains.
     *
     * @return {@code true} when both route-level chains are empty
     */
    public boolean hasNoRouteChains() {
        return routeCanonicalizers.isEmpty() && routeSanitizers.isEmpty();
    }
}
