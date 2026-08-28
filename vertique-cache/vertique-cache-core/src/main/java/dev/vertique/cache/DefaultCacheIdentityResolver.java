// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/** Default cache identity resolver backed by the current framework security context. */
@Singleton
public final class DefaultCacheIdentityResolver implements CacheIdentityResolver {
    private final ContextHolder contextHolder;

    @Inject
    public DefaultCacheIdentityResolver(ContextHolder contextHolder) {
        this.contextHolder = contextHolder;
    }

    @Override
    public Optional<SecurityIdentity> current() {
        return contextHolder.current(SecurityContext.class).map(SecurityContext::identity);
    }
}
