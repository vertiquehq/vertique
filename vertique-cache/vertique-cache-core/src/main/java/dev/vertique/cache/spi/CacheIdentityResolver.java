// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import dev.vertique.security.SecurityIdentity;
import java.util.Optional;

/** Provider-neutral seam for resolving the current canonical cache identity bucket. */
public interface CacheIdentityResolver {
    /** Supplies the typed current identity, or empty when no request identity is available. */
    Optional<SecurityIdentity> current();
}
