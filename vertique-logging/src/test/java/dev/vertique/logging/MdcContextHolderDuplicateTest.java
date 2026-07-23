// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.context.ContextLocalServiceProvider;
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
 * Verifies the MDC deep-copy contract of {@link ContextInternal#duplicate(boolean) duplicate(true)}
 * against the substrate's {@link ContextLocalServiceProvider} duplicator.
 *
 * <p>These tests live in {@code vertique-logging} (rather than {@code vertique-context}) because
 * {@link MDCContexts} was moved to this module as part of the context-substrate split.
 * The tests remain in package {@code dev.vertique.core.context} to retain access to the
 * package-private duplicator and Vert.x context internals.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MdcContextHolderDuplicateTest {

    @Test
    @DisplayName("duplicate(true) deep-copies MDCContext so mutations on the duplicate do not leak to the source")
    void mdcContextIsDeepCopied(Vertx vertx, VertxTestContext ctx) {
        ContextInternal source = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        source.runOnContext(v -> {
            try {
                MDCContexts.put("requestId", "source-r1");
                ContextInternal duplicate = source.duplicate(true);
                duplicate.runOnContext(v2 -> {
                    try {
                        // Mutate the duplicate's MDC.
                        assertEquals("source-r1", MDCContexts.get("requestId"));
                        MDCContexts.put("requestId", "duplicate-r2");
                        MDCContexts.put("newKey", "duplicate-only");

                        // Hop back to the source; assertions run on its context so its holder map
                        // is observed.
                        source.runOnContext(v3 -> {
                            try {
                                assertEquals(
                                        "source-r1",
                                        MDCContexts.get("requestId"),
                                        "source MDC must not see duplicate's mutation");
                                assertNull(
                                        MDCContexts.get("newKey"),
                                        "source MDC must not see keys added on the duplicate");
                                ctx.completeNow();
                            } catch (Throwable t) {
                                ctx.failNow(t);
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
