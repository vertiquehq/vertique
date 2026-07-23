// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ContextSnapshot} and the snapshot/bindSnapshot round-trip in
 * {@link ContextValues}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link ContextValues#snapshot()} is lenient outside Vert.x (returns the empty singleton).
 *   <li>Snapshots capture the current bindings and {@link ContextValues#bindSnapshot} installs
 *       them on a different duplicated context with prior-value restoration on close.
 *   <li>{@link ContextSnapshot} is opaque: no public method returns the underlying {@link Map}.
 *   <li>{@link ContextValues#bindSnapshot(ContextSnapshot)} with the empty snapshot is a no-op
 *       that does not require a duplicated context.
 * </ul>
 *
 * <p>MDC deep-copy isolation tests live in {@code vertique-logging} where {@code MDCContexts}
 * is defined — see {@code MdcContextSnapshotTest}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextSnapshotTest {

    // --- lenient snapshot outside Vert.x ---

    @Test
    @DisplayName("snapshot() outside any Vert.x context returns the empty snapshot")
    void snapshotOutsideContextReturnsEmpty() {
        ContextSnapshot snap = ContextValues.snapshot();
        assertTrue(snap.isEmpty());
        // Must be the canonical empty singleton returned by ContextSnapshot.empty().
        assertTrue(snap.isEmpty());
    }

    @Test
    @DisplayName("bindSnapshot(empty) outside a duplicated context is a no-op and does not throw")
    void bindSnapshotEmptyNoOp() {
        // No active Vert.x context here — must not throw.
        ContextHolder.Scope scope = ContextValues.bindSnapshot(ContextSnapshot.empty());
        scope.close(); // must be idempotent and not throw
    }

    // --- snapshot on a duplicated context with no bindings ---

    @Test
    @DisplayName("snapshot() on a duplicated context with no bindings returns an empty snapshot")
    void snapshotWithNoBindingsIsEmpty(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                ContextSnapshot snap = ContextValues.snapshot();
                assertTrue(snap.isEmpty());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- snapshot captures bindings; bindSnapshot installs them ---

    @Test
    @DisplayName(
            "bindSnapshot installs captured bindings on a different duplicated context and restores prior values on close")
    void bindSnapshotInstallsAndRestoresPriorValues(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal source = root.duplicate();
        ContextInternal target = root.duplicate();

        source.runOnContext(v -> {
            try {
                // Bind a value and take a snapshot.
                try (ContextHolder.Scope ignored = ContextValues.bind(StringCtx.class, new StringCtx("captured"))) {
                    ContextSnapshot snap = ContextValues.snapshot();
                    assertFalse(snap.isEmpty());

                    // Install on target context.
                    target.runOnContext(vt -> {
                        try {
                            assertFalse(ContextValues.current(StringCtx.class).isPresent(), "target must start empty");
                            ContextHolder.Scope scope = ContextValues.bindSnapshot(snap);
                            assertEquals(
                                    new StringCtx("captured"),
                                    ContextValues.current(StringCtx.class).orElseThrow());
                            scope.close();
                            assertFalse(
                                    ContextValues.current(StringCtx.class).isPresent(),
                                    "prior absence restored after close");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    });
                }
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- ContextSnapshot is opaque ---

    @Test
    @DisplayName("ContextSnapshot has no public method returning Map")
    void contextSnapshotIsOpaque() {
        for (Method m : ContextSnapshot.class.getMethods()) {
            assertFalse(
                    Map.class.isAssignableFrom(m.getReturnType()),
                    "ContextSnapshot must not expose a public Map-returning method; found: " + m.getName());
        }
    }
}
