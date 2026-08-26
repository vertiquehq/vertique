// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import dev.vertique.cache.CacheIdentity;
import java.util.Optional;

/** Provider-neutral seam for resolving the current canonical cache identity bucket. */
@FunctionalInterface
public interface CacheIdentityResolver {
    /**
     * Resolves the identity component for the current request. The standard cache graph contributes
     * a {@code DefaultCacheIdentityResolver}; an additional resolver is intended for an
     * explicitly composed context source and must not derive identity from untrusted request data.
     *
     * @param identity requested cache identity mode
     * @return a canonical identity component, or empty when the request is anonymous/unavailable
     */
    Optional<String> resolve(CacheIdentity identity);
}
