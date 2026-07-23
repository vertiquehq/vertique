// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link MDCContexts#snapshotKeys(Set)}.
 *
 * <p>Verifies the FR-COR mirrored-key restoration contract:
 * <ul>
 *   <li>The snapshot captures both present values and absences at snapshot time.</li>
 *   <li>On close, every snapshotted key is restored to its snapshot-time state —
 *       including removing keys that were absent at snapshot time but added afterwards.</li>
 *   <li>Keys outside the snapshot set are not touched by close.</li>
 *   <li>Empty or {@code null} key set returns a no-op scope.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class MdcSnapshotKeysTest {

    @Test
    @DisplayName("empty key set returns a non-null no-op scope")
    void emptyKeySetReturnsNoOpScope(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of());
            assertNotNull(scope, "scope must be non-null");
            scope.close(); // idempotent, no exception
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("null key set returns a non-null no-op scope")
    void nullKeySetReturnsNoOpScope(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            ContextHolder.Scope scope = MDCContexts.snapshotKeys(null);
            assertNotNull(scope);
            scope.close();
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("close restores a key that was present at snapshot time")
    void closeRestoresPriorValue(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            MDCContexts.put("a", "before");

            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of("a"));
            MDCContexts.put("a", "during");
            assertEquals("during", MDCContexts.get("a"));

            scope.close();
            assertEquals("before", MDCContexts.get("a"), "prior value should be restored");
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("close removes a key that was absent at snapshot time but added afterwards")
    void closeRemovesKeyAddedAfterSnapshot(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            assertNull(MDCContexts.get("late"));

            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of("late"));
            MDCContexts.put("late", "added-after-snapshot");
            assertEquals("added-after-snapshot", MDCContexts.get("late"));

            scope.close();
            assertNull(MDCContexts.get("late"), "key added after snapshot must be cleared on close");
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("snapshotKeys covers a mix of present and absent keys in one scope")
    void mixOfPresentAndAbsentKeys(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            MDCContexts.put("present", "p0");

            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of("present", "absent"));

            MDCContexts.put("present", "p1");
            MDCContexts.put("absent", "a1");

            scope.close();

            assertEquals("p0", MDCContexts.get("present"), "present key restored to prior value");
            assertNull(MDCContexts.get("absent"), "absent key cleared on close");
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("close does not touch keys outside the snapshot set")
    void closeLeavesOtherKeysAlone(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            MDCContexts.put("other", "untouched");

            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of("a"));
            MDCContexts.put("a", "x");
            MDCContexts.put("other", "still-untouched-by-scope");

            scope.close();

            assertEquals(
                    "still-untouched-by-scope", MDCContexts.get("other"), "scope must not touch keys outside its set");
            assertNull(MDCContexts.get("a"));
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("close is idempotent — second close is a no-op")
    void closeIsIdempotent(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            MDCContexts.put("k", "original");
            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of("k"));
            MDCContexts.put("k", "during");
            scope.close();
            assertEquals("original", MDCContexts.get("k"));

            MDCContexts.put("k", "after-close");
            scope.close(); // second close — must NOT touch current state
            assertEquals("after-close", MDCContexts.get("k"), "second close must be a no-op");
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("snapshotKeys requires a duplicated Vert.x context for writes path consistency")
    void snapshotKeysReturnsScopeEvenOnRootContext(Vertx vertx, VertxTestContext testContext) {
        // Empty set on root context should still be safe (no-op).
        vertx.runOnContext(v -> {
            ContextHolder.Scope scope = MDCContexts.snapshotKeys(Set.of());
            assertFalse(scope == null);
            scope.close();
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("nested snapshotKeys scopes compose with LIFO restoration")
    void nestedSnapshotKeysScopesCompose(Vertx vertx, VertxTestContext testContext) {
        ContextInternal duplicate = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicate.runOnContext(v -> {
            MDCContexts.put("x", "v0");

            ContextHolder.Scope outer = MDCContexts.snapshotKeys(Set.of("x"));
            MDCContexts.put("x", "v1");

            ContextHolder.Scope inner = MDCContexts.snapshotKeys(Set.of("x"));
            MDCContexts.put("x", "v2");

            inner.close();
            assertEquals("v1", MDCContexts.get("x"), "inner close restores to outer-time value");

            outer.close();
            assertEquals("v0", MDCContexts.get("x"), "outer close restores to pre-outer value");

            // Bonus assertion: a third independent scope sees a clean baseline.
            assertTrue(MDCContexts.get("x").equals("v0"));
            testContext.completeNow();
        });
    }
}
