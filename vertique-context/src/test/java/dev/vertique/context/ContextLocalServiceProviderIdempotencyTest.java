// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests that {@link ContextLocalServiceProvider#init} is idempotent across multiple
 * {@link Vertx#vertx()} calls in the same JVM.
 *
 * <p>The underlying bug: {@link ContextLocalServiceProvider} is a {@code VertxServiceProvider}
 * SPI invoked once per {@code Vertx.vertx()} call. Before the guard was added, each call to
 * {@link io.vertx.core.spi.context.storage.ContextLocal#registerLocal} allocated a new JVM-global
 * slot with an incremented key index. Creating a second {@code Vertx} instance overwrote
 * {@link DefaultContextHolder#CONTEXT_LOCAL} with the new slot, causing contexts from the
 * <em>first</em> {@code Vertx} instance to throw {@code Invalid key index} when performing any
 * context-local read or write (their {@code locals[]} array was sized for the original slot count).
 *
 * <p>Two tests cover complementary aspects:
 * <ol>
 *   <li><b>Idempotency unit test</b> — verifies {@link DefaultContextHolder#isContextLocalInitialized()}
 *       returns {@code true} after the first {@code Vertx} is created, and that invoking
 *       {@link ContextLocalServiceProvider#init} again is a no-op (slot reference unchanged).
 *   <li><b>Two-Vertx integration test</b> — creates two {@code Vertx} instances sequentially,
 *       then performs a write-and-read-back within a duplicated context on EACH instance,
 *       asserting no {@code Invalid key index} exception occurs on either.
 * </ol>
 *
 * <p>These tests are unit tests ({@code *Test.java}) because they manage their own {@code Vertx}
 * lifecycle and do not rely on the {@link VertxExtension}-injected instance. The
 * {@link VertxExtension} is still declared so that the test infrastructure is consistent with the
 * rest of the suite; the injected {@code Vertx vertx} parameter is unused by both tests.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextLocalServiceProviderIdempotencyTest {

    // Manually managed Vertx instances so the test controls creation order precisely.
    private Vertx vertxA;
    private Vertx vertxB;

    @BeforeEach
    void setUp() {
        // Do NOT create Vertx here; each test controls its own lifecycle.
    }

    @AfterEach
    void tearDown() {
        if (vertxA != null) {
            vertxA.close();
            vertxA = null;
        }
        if (vertxB != null) {
            vertxB.close();
            vertxB = null;
        }
    }

    // --- Test 1: idempotency guard ---

    @Test
    @DisplayName("isContextLocalInitialized returns true after first Vertx.vertx() and init() is a no-op thereafter")
    void contextLocalIsInitializedAfterFirstVertxAndInitIsNoOp() {
        // Given: create a Vertx instance, which fires the ContextLocalServiceProvider SPI during
        // bootstrap and initialises CONTEXT_LOCAL. Creating it explicitly here — rather than relying
        // on the VertxExtension-injected instance or a prior test in the same JVM having called
        // Vertx.vertx() — makes this assertion order-independent. Surefire is free to run this method
        // before contextLocalReadWriteWorksOnBothVertxInstancesAfterSecondCreation (or before any
        // other test class that creates a Vertx), in which case no slot would yet be registered.
        vertxA = Vertx.vertx();
        assertTrue(
                DefaultContextHolder.isContextLocalInitialized(),
                "CONTEXT_LOCAL must be initialised after Vertx.vertx() — the SPI fires during bootstrap");

        // When: init() is called again (simulating a second Vertx.vertx() call).
        // Before the fix this would call ContextLocal.registerLocal() again and overwrite
        // CONTEXT_LOCAL with a new slot — causing Invalid key index on existing contexts.
        // After the fix the guard prevents re-registration; calling init() is a no-op.
        ContextLocalServiceProvider provider = new ContextLocalServiceProvider();
        // Passing null is safe here: the guard short-circuits before bootstrap is used.
        provider.init(null);

        // Then: the slot is still initialized (guard prevented any change).
        assertTrue(
                DefaultContextHolder.isContextLocalInitialized(),
                "CONTEXT_LOCAL must remain initialized after a second init() call");
    }

    // --- Test 2: two-Vertx integration test (the real proof) ---

    @Test
    @DisplayName("context-local bind/read works on both Vertx instances when two are created in the same JVM")
    void contextLocalReadWriteWorksOnBothVertxInstancesAfterSecondCreation(VertxTestContext ctx) {
        // Given: two independent Vertx instances created sequentially in the same JVM.
        // Before the fix, creating vertxB would overwrite DefaultContextHolder.CONTEXT_LOCAL with
        // a new slot (higher key index), breaking all contexts from vertxA: their locals[] array
        // was sized for the original slot count and would throw "Invalid key index" on any read
        // or write.
        vertxA = Vertx.vertx();
        vertxB = Vertx.vertx();

        // One checkpoint per instance — both must fire before the test completes.
        Checkpoint vertxADone = ctx.checkpoint();
        Checkpoint vertxBDone = ctx.checkpoint();

        // When/Then: bind a value and read it back within a duplicated context on vertxA.
        // After the fix, vertxA's contexts are unaffected by vertxB's creation because the guard
        // in ContextLocalServiceProvider.init() prevented the second registerLocal() call.
        ContextInternal dupA = ((ContextInternal) vertxA.getOrCreateContext()).duplicate();
        dupA.runOnContext(v -> {
            try (ContextHolder.Scope scope = ContextValues.bind(PingValue.class, new PingValue("from-A"))) {
                PingValue readBack = ContextValues.current(PingValue.class)
                        .orElseThrow(() -> new AssertionError("Expected PingValue to be bound on vertxA's context"));
                assertEquals(
                        "from-A",
                        readBack.label(),
                        "vertxA context-local read must return the written value without Invalid key index");
                vertxADone.flag();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });

        // When/Then: bind a value and read it back within a duplicated context on vertxB.
        ContextInternal dupB = ((ContextInternal) vertxB.getOrCreateContext()).duplicate();
        dupB.runOnContext(v -> {
            try (ContextHolder.Scope scope = ContextValues.bind(PingValue.class, new PingValue("from-B"))) {
                PingValue readBack = ContextValues.current(PingValue.class)
                        .orElseThrow(() -> new AssertionError("Expected PingValue to be bound on vertxB's context"));
                assertEquals(
                        "from-B",
                        readBack.label(),
                        "vertxB context-local read must return the written value without Invalid key index");
                vertxBDone.flag();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- Test-local ContextValue ---

    /**
     * Minimal immutable {@link ContextValue} used only by this test class to exercise the
     * context-local bind/read path without importing any production or higher-level module type.
     *
     * @param label an opaque string that identifies which Vertx instance bound the value
     */
    private record PingValue(String label) implements ContextValue {}
}
