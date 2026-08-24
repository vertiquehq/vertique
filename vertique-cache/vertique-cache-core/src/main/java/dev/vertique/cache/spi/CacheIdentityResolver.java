// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import dev.vertique.cache.CacheIdentity;
import java.util.Optional;

/** Provider-neutral seam for resolving the current canonical security identity bucket. */
@FunctionalInterface
public interface CacheIdentityResolver {
    /**
     * Resolves the identity component for the current request.
     *
     * @param identity requested cache identity mode
     * @return a canonical identity component, or empty when the request is anonymous/unavailable
     */
    Optional<String> resolve(CacheIdentity identity);
}
