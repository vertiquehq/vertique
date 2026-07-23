// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.context.ContextSnapshot;
import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the MDC deep-copy contract for {@link ContextSnapshot} and the
 * snapshot/bindSnapshot round-trip in {@link ContextValues}.
 *
 * <p>Verifies that mutating or clearing the source MDC after taking a snapshot does not affect
 * the snapshot, and that installing the snapshot produces an independent {@link MDCContext}
 * that can be mutated without affecting the snapshot or other bindings.
 *
 * <p>These tests live in {@code vertique-logging} (rather than {@code vertique-context}) because
 * {@link MDCContexts} was moved to this module as part of the context-substrate split.
 * The tests remain in package {@code dev.vertique.core.context} to retain access to the
 * package-private substrate types.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MdcContextSnapshotTest {

    @Test
    @DisplayName("MDC deep-copy: mutating source MDC after snapshot does not affect the snapshot")
    void mdcDeepCopyIsolatesSnapshotFromSourceMutation(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal source = root.duplicate();
        ContextInternal target = root.duplicate();

        source.runOnContext(vs -> {
            try {
                // Put a value, take a snapshot, then mutate the source MDC.
                MDCContexts.put("key", "original");
                ContextSnapshot snap = ContextValues.snapshot();

                // Mutate source MDC after snapshot.
                MDCContexts.put("key", "mutated");
                MDCContexts.put("extra", "added");

                // Install snapshot on target — must see original value, not mutated.
                target.runOnContext(vt -> {
                    try {
                        ContextHolder.Scope scope = ContextValues.bindSnapshot(snap);
                        assertEquals("original", MDCContexts.get("key"), "snapshot must capture original value");
                        assertNull(MDCContexts.get("extra"), "snapshot must not include keys added after snapshot");

                        // Mutate MDC in target; must not affect the snapshot.
                        MDCContexts.put("key", "target-mutated");

                        // Install the same snapshot on yet another context and verify independence.
                        ContextInternal target2 = root.duplicate();
                        target2.runOnContext(vt2 -> {
                            try {
                                ContextHolder.Scope scope2 = ContextValues.bindSnapshot(snap);
                                assertEquals(
                                        "original",
                                        MDCContexts.get("key"),
                                        "second bindSnapshot must still see original");
                                scope2.close();
                                scope.close();
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

    @Test
    @DisplayName("MDC deep-copy: clearing source MDC after snapshot does not affect the snapshot")
    void mdcDeepCopyIsolatesSnapshotFromSourceClear(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal source = root.duplicate();
        ContextInternal target = root.duplicate();

        source.runOnContext(vs -> {
            try {
                MDCContexts.put("k", "v");
                ContextSnapshot snap = ContextValues.snapshot();

                // Clear the source MDC entirely.
                MDCContexts.clear();

                target.runOnContext(vt -> {
                    try {
                        ContextHolder.Scope scope = ContextValues.bindSnapshot(snap);
                        assertEquals(
                                "v",
                                MDCContexts.get("k"),
                                "snapshot must still carry original value after source clear");
                        scope.close();
                        ctx.completeNow();
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
