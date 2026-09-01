// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-002: the framework-default {@link RateLimitSubjectResolver} —
 * {@link DefaultRateLimitSubjectResolver} — reads the current {@link SecurityIdentity} off the
 * {@link SecurityContext} bound in the framework {@link ContextHolder}
 * (contracts/rate-limit-runtime.md, "Subject resolution SPI"; {@code spec.md} §5.2), mirroring
 * {@code dev.vertique.cache.DefaultCacheIdentityResolver}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefaultRateLimitSubjectResolverTest {

    private final DefaultContextHolder holder = new DefaultContextHolder();

    @Test
    @DisplayName("current() reads the bound SecurityContext, and is empty when none is bound")
    void shouldReadIdentityFromTheContextHolderSecurityContext(Vertx vertx, VertxTestContext ctx) {
        SecurityIdentity known = RateLimitIdentityFixtures.actorOnly("service-1");
        SecurityContext boundContext = SecurityContexts.unauthenticated(known);
        DefaultRateLimitSubjectResolver resolver = new DefaultRateLimitSubjectResolver(holder);
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();

        // Scenario 1: a SecurityContext is bound through ContextHolder on a duplicated context.
        ContextInternal boundDuplicate = root.duplicate();
        boundDuplicate.runOnContext(ignored -> {
            try {
                try (ContextHolder.Scope scope = holder.bind(SecurityContext.class, boundContext)) {
                    Optional<SecurityIdentity> bound = resolver.current();
                    assertTrue(bound.isPresent(), "resolver.current() must be present when a SecurityContext is bound");
                    assertEquals(known, bound.orElseThrow(), "resolver.current() must equal the exact bound identity");
                }
                // Scenario 2: a fresh duplicated context that never had a SecurityContext bound.
                ContextInternal unboundDuplicate = root.duplicate();
                unboundDuplicate.runOnContext(ignoredToo -> {
                    try {
                        Optional<SecurityIdentity> unbound = resolver.current();
                        assertTrue(
                                unbound.isEmpty(), "resolver.current() must be empty when no SecurityContext is bound");
                        ctx.completeNow();
                    } catch (Throwable failure) {
                        ctx.failNow(failure);
                    }
                });
            } catch (Throwable failure) {
                ctx.failNow(failure);
            }
        });
    }
}
