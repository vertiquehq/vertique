// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the request state passed to each {@link SecurityIdentityResolver} in the
 * resolution chain.
 *
 * <p>A {@code SecurityIdentityResolutionContext} aggregates the authentication evidence collected
 * so far (via {@link AuthenticationEvidenceCollector}), the optional network origin of the
 * request, the optional correlation context that arrived with the request, and any transport- or
 * layer-specific attributes the adapter chose to attach.
 *
 * <p>The context is transport-neutral: the REST adapter populates {@code origin} and
 * {@code correlation} from HTTP headers; a future gRPC adapter would do the same from gRPC
 * metadata. Resolvers that need origin or correlation should guard their use with
 * {@link Optional#isPresent()}.
 *
 * <p>See PRD §7.5 and the Contract Appendix sections "SecurityIdentityResolver" and
 * "SecurityIdentityResolutionContext" for the full contract.
 *
 * @param evidence    accumulated authentication evidence in insertion order; never {@code null};
 *                    defensive copy taken at construction; returned list is unmodifiable
 * @param origin      network-envelope snapshot captured before authentication; {@link Optional#empty()}
 *                    when the transport layer has not populated it
 * @param correlation correlation identifiers that arrived with the request; {@link Optional#empty()}
 *                    when no correlation context is available
 * @param attributes  transport- or layer-specific key/value pairs; defensive copy taken at
 *                    construction; returned map is unmodifiable
 */
public record SecurityIdentityResolutionContext(
        List<AuthenticationEvidence> evidence,
        Optional<RequestOrigin> origin,
        Optional<CorrelationContext> correlation,
        Map<String, Object> attributes) {

    /**
     * Compact constructor — validates all components and takes defensive copies of mutable
     * collections.
     */
    public SecurityIdentityResolutionContext {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(correlation, "correlation");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
