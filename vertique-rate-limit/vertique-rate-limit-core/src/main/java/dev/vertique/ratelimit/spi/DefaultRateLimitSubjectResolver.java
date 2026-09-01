// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework-default {@link RateLimitSubjectResolver}, backed by the current {@code SecurityContext}
 * bound in the framework {@link ContextHolder} (contracts/rate-limit-runtime.md, "Subject
 * resolution SPI"). Mirrors {@code dev.vertique.cache.DefaultCacheIdentityResolver}.
 *
 * <p>Public (unlike its cache-module precedent) so {@code RateLimitCoreModule}
 * ({@code dev.vertique.ratelimit.dagger}) can inject it across the package boundary between the
 * SPI package and the Dagger-wiring package.
 */
@Singleton
public final class DefaultRateLimitSubjectResolver implements RateLimitSubjectResolver {
    private final ContextHolder contextHolder;

    @Inject
    public DefaultRateLimitSubjectResolver(ContextHolder contextHolder) {
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
    }

    @Override
    public Optional<SecurityIdentity> current() {
        return contextHolder.current(SecurityContext.class).map(SecurityContext::identity);
    }
}
