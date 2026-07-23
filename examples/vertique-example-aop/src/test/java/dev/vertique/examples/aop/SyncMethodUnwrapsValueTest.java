// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runtime proof for PRD-CODEGEN-013 slice 2.3 — sync-returning interception through the real
 * generated proxy and Dagger graph.
 *
 * <p>{@link Greeter#greetSync(String)} returns a plain {@link String} (not a {@code Future}) and
 * carries the built-in {@code @Timed("vertique.example.greet.sync")}. Exercising it through the
 * generated {@code Greeter$AopProxy} proves:
 *
 * <ol>
 *   <li><b>Sync unwrap</b> — the override runs the around-chain and unwraps the synchronously-settled
 *       result, returning the bean's original {@link String} value (not a {@code Future}, not
 *       {@code null}).</li>
 *   <li><b>Built-in never trips the guard</b> — the built-in {@code @Timed} completes synchronously,
 *       so the FR-013-05 sync guard is not tripped; no {@link IllegalStateException} is thrown
 *       ({@code BuiltInsNeverTripSyncGuard#timedAspectNeverDefers}).</li>
 *   <li><b>Timer recorded</b> — a {@code Timer} named {@code vertique.example.greet.sync} with
 *       {@code outcome=SUCCESS} is recorded, proving sync interception runs the real aspect.</li>
 * </ol>
 */
class SyncMethodUnwrapsValueTest {

    private static final String SYNC_TIMER = "vertique.example.greet.sync";

    /** Builds the component over a fresh, caller-owned registry. */
    private static GreeterComponent componentWith(SimpleMeterRegistry registry) {
        return DaggerGreeterComponent.builder()
                .aopRegistryModule(new AopRegistryModule(registry))
                .build();
    }

    @Test
    @DisplayName("greetSync() returns the unwrapped synchronous value through the generated proxy")
    void syncMethodReturnsCorrectValue() {
        Greeter greeter = componentWith(new SimpleMeterRegistry()).greeter();

        String result = greeter.greetSync("world");

        assertNotNull(result, "sync method must return the unwrapped value, not null");
        assertEquals(
                "Hello, world! (sync)",
                result,
                "the override must unwrap the synchronously-completed chain into the bean's String value");
    }

    @Test
    @DisplayName("the built-in @Timed aspect never trips the sync guard (no IllegalStateException)")
    void timedAspectNeverDefers() {
        Greeter greeter = componentWith(new SimpleMeterRegistry()).greeter();

        // The built-in @Timed completes synchronously, so the FR-013-05 guard must not fire.
        assertDoesNotThrow(
                () -> greeter.greetSync("world"),
                "the built-in @Timed completes synchronously and must not trip the sync-defer guard");
    }

    @Test
    @DisplayName("greetSync() records a SUCCESS timer named vertique.example.greet.sync with count >= 1")
    void syncMethodRecordsTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Greeter greeter = componentWith(registry).greeter();

        greeter.greetSync("x");

        Timer timer = registry.find(SYNC_TIMER).tag("outcome", "SUCCESS").timer();
        assertNotNull(
                timer,
                "TimedAspect must record a SUCCESS-tagged timer named " + SYNC_TIMER
                        + " for the sync method through the real generated proxy");
        assertTrue(timer.count() >= 1, "timer count must be >= 1, was " + timer.count());
    }
}
