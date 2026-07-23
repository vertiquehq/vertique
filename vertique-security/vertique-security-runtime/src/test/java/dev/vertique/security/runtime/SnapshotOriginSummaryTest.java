// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.InvocationOrigin;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link DefaultIdentitySnapshotFactory}'s {@code originSummary()} derivation —
 * identity-002 P2.S5b-i. Proves the transport-neutral fix: {@code originSummary()} is sourced from
 * the ambient {@link InvocationOrigin} bound on the injected {@link ContextHolder} at capture time,
 * not the hardcoded {@code "rest:authenticated"} literal the pre-fix implementation always produced
 * for any context with a present {@code SecurityContext#origin()} — regardless of the real ingress
 * kind (the "camel context mislabeled rest:authenticated" defect closed by this slice).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SnapshotOriginSummaryTest {

    /**
     * Builds a live, non-anonymous {@link SecurityContext} sufficient to exercise
     * {@link DefaultIdentitySnapshotFactory#capture(SecurityContext)} — the origin-summary
     * derivation does not depend on the identity dimension, only on the ambient
     * {@link InvocationOrigin}.
     *
     * @return a system-identity live security context
     */
    private static SecurityContext liveContext() {
        return SecurityContexts.system(SystemIdentities.scheduledJob("test-job"));
    }

    @Test
    @DisplayName("a camel-scoped ambient InvocationOrigin yields \"camel\", never the hardcoded \"rest:authenticated\"")
    void camelContextIsNotMislabeledRestAuthenticated(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();
            try (ContextHolder.Scope scope = holder.bind(InvocationOrigin.class, InvocationOrigin.of("camel"))) {
                DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(holder);
                IdentitySnapshotContent content = factory.capture(liveContext());

                assertEquals(
                        "camel",
                        content.originSummary(),
                        "a camel-scoped ambient InvocationOrigin must summarize as \"camel\"");
                assertNotEquals("rest:authenticated", content.originSummary());
            }
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("no ambient InvocationOrigin yields \"unknown\"")
    void noAmbientOriginYieldsUnknown(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();
            DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(holder);

            IdentitySnapshotContent content = factory.capture(liveContext());

            assertEquals(
                    "unknown", content.originSummary(), "no ambient InvocationOrigin must summarize as \"unknown\"");
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("a rest-scoped ambient InvocationOrigin yields \"rest\"")
    void restOriginYieldsRest(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> ctx.verify(() -> {
            ContextHolder holder = new DefaultContextHolder();
            try (ContextHolder.Scope scope = holder.bind(InvocationOrigin.class, InvocationOrigin.of("rest"))) {
                DefaultIdentitySnapshotFactory factory = new DefaultIdentitySnapshotFactory(holder);
                IdentitySnapshotContent content = factory.capture(liveContext());

                assertEquals(
                        "rest",
                        content.originSummary(),
                        "a rest-scoped ambient InvocationOrigin must summarize as" + " \"rest\"");
            }
            ctx.completeNow();
        }));
    }
}
