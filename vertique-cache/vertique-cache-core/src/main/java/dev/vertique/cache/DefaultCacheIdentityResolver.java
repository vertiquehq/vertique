// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.cache.spi.CacheIdentityResolver;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
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
    public Optional<String> resolve(CacheIdentity requested) {
        if (requested == CacheIdentity.NONE) {
            return Optional.of("NONE");
        }
        Optional<SecurityContext> current = contextHolder.current(SecurityContext.class);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        SecurityIdentity identity = current.get().identity();
        if (identity.actor().type() == PrincipalType.ANONYMOUS) {
            return Optional.empty();
        }
        return switch (requested) {
            case ACTOR -> Optional.of(principalComponent("actor", identity.actor()));
            case EFFECTIVE_PRINCIPAL ->
                Optional.of(principalComponent("principal", identity.subject().orElse(identity.actor())));
            case ACTOR_AND_SUBJECT -> Optional.of(actorAndSubjectComponent(identity));
            case NONE -> Optional.of("NONE");
        };
    }

    @Override
    public Optional<SecurityIdentity> current() {
        return contextHolder.current(SecurityContext.class).map(SecurityContext::identity);
    }

    private static String actorAndSubjectComponent(SecurityIdentity identity) {
        String subject = identity.subject()
                .map(DefaultCacheIdentityResolver::principalValue)
                .orElse("NONE");
        return "actor:" + principalValue(identity.actor()) + "~subject:" + subject;
    }

    private static String principalComponent(String label, PrincipalRef principal) {
        return label + ":" + principalValue(principal);
    }

    private static String principalValue(PrincipalRef principal) {
        return principal.type() + ":" + principal.id();
    }
}
