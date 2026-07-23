// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.security.authz.AuthorizationNarrower;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared helper that sorts a {@link Set} of {@link AuthorizationNarrower}s into
 * {@link OrderedExtension} order and fails fast on a duplicate {@code (priority, orderKey)} pair.
 *
 * <p>Both {@link NarrowingAuthorizer} and {@link NarrowingIntrospector} fold over the same ordered
 * narrower set; this helper is the single place that establishes that order and its startup
 * validation, so the two wrappers cannot silently disagree on ordering or duplicate detection.
 *
 * <p><strong>Duplicate guard.</strong> Mirrors the pattern used by
 * {@code dev.vertique.rest.security.IdentityResolutionMiddleware} for
 * {@code dev.vertique.security.resolver.SecurityIdentityResolver} (NFR-ID-003): two narrowers
 * sharing the same {@code (priority, orderKey)} pair are a configuration error and fail startup
 * with an {@link IllegalStateException} naming both conflicting classes. {@link AuthorizationNarrower}
 * has no independent {@code id()} distinct from {@link OrderedExtension#orderKey()}, so
 * {@code orderKey()} is the tie-break component used here.
 */
final class AuthorizationNarrowerOrdering {

    private AuthorizationNarrowerOrdering() {
        throw new AssertionError("AuthorizationNarrowerOrdering is not instantiable");
    }

    /**
     * Sorts {@code narrowers} by {@link OrderedExtension#comparator()} and validates that no two
     * narrowers share the same {@code (priority, orderKey)} pair.
     *
     * @param narrowers the contributed narrowers; must not be {@code null}
     * @return an immutable, ordered list of the narrowers
     * @throws NullPointerException  if {@code narrowers} is {@code null}
     * @throws IllegalStateException if two narrowers share the same {@code (priority, orderKey)} pair
     */
    static List<AuthorizationNarrower> sortedAndValidated(Set<AuthorizationNarrower> narrowers) {
        Objects.requireNonNull(narrowers, "narrowers");
        List<AuthorizationNarrower> sorted = new ArrayList<>(narrowers);
        sorted.sort(OrderedExtension.comparator());

        // Keyed by a typed (priority, orderKey) composite, independent of sort order:
        // OrderedExtension sorts phase-first, so two narrowers sharing a (priority, orderKey) but
        // differing in phase would not be adjacent in the sorted list.
        record PriorityOrderKey(int priority, String orderKey) {}
        Map<PriorityOrderKey, AuthorizationNarrower> byPriorityAndOrderKey = new LinkedHashMap<>();
        for (AuthorizationNarrower narrower : sorted) {
            PriorityOrderKey key = new PriorityOrderKey(narrower.priority(), narrower.orderKey());
            AuthorizationNarrower existing = byPriorityAndOrderKey.putIfAbsent(key, narrower);
            if (existing != null) {
                throw new IllegalStateException("Duplicate AuthorizationNarrower (priority="
                        + narrower.priority()
                        + ", orderKey="
                        + narrower.orderKey()
                        + ") between "
                        + existing.getClass().getName()
                        + " and "
                        + narrower.getClass().getName());
            }
        }
        return List.copyOf(sorted);
    }
}
