// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.security.SecurityIdentity;
import java.util.Optional;

/**
 * Provider-neutral seam for resolving the current caller's security identity, shared by every
 * framework adapter (the {@code @RateLimited} aspect, the REST edge limiter, and future MCP tools)
 * (contracts/rate-limit-runtime.md, "Subject resolution SPI"; {@code spec.md} §5.2). Mirrors {@code
 * dev.vertique.cache.spi.CacheIdentityResolver}.
 *
 * <p>Bound {@code @BindsOptionalOf} in {@code RateLimitCoreModule}; the framework default reads
 * {@code SecurityContext} off the {@code ContextHolder}. Exactly one custom resolver may replace
 * it.
 */
public interface RateLimitSubjectResolver {

    /** Supplies the typed current identity, or empty when no request identity is available. */
    Optional<SecurityIdentity> current();
}
