// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import dev.vertique.security.AuthenticationEvidence;
import java.util.List;

/**
 * Transport-neutral accumulator for {@link AuthenticationEvidence} entries gathered during a
 * single request authentication pass.
 *
 * <p>Each transport adapter (REST, gRPC, etc.) creates an instance of this collector, populates it
 * as credentials are verified, then hands the snapshot to
 * {@link SecurityIdentityResolutionContext} for use by the {@link SecurityIdentityResolver} chain.
 *
 * <p>Implementations MUST preserve insertion order so resolvers can rely on "first verified
 * credential" semantics where needed.
 *
 * <p>The REST adapter ships {@code RestAuthenticationEvidence} in a later slice; future transport
 * adapters implement their own {@code AuthenticationEvidenceCollector} without depending on
 * Vert.x Web.
 */
public interface AuthenticationEvidenceCollector {

    /**
     * Appends an evidence entry to the collector.
     *
     * <p>Implementations MUST preserve insertion order so that resolvers can rely on
     * "first verified credential" semantics when needed.
     *
     * @param evidence the evidence entry to add; must not be {@code null}
     */
    void add(AuthenticationEvidence evidence);

    /**
     * Returns a snapshot of all accumulated evidence entries in insertion order.
     *
     * <p>The returned list MUST be unmodifiable; each call MAY return a new snapshot.
     *
     * @return an unmodifiable {@link List} of accumulated evidence entries; never {@code null}
     */
    List<AuthenticationEvidence> evidence();
}
