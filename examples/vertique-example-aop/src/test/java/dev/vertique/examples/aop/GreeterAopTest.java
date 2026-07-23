// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end runtime-DI proof for PRD-CODEGEN-013 Phase 1.
 *
 * <p>This is the integration proof that the earlier characterization tests lacked: it compiles the
 * real {@code vertique-codegen-aop} processor over {@link Greeter} (in main sources), lets the real
 * Dagger processor wire the generated {@code GeneratedAopModule} into {@link GreeterComponent}, and
 * then exercises the assembled graph at runtime to prove:
 *
 * <ol>
 *   <li><b>Proxy substitution</b> — {@code GreeterComponent.greeter()} returns the generated
 *       {@code Greeter$AopProxy}, not a plain {@link Greeter}; the generated {@code @Binds}
 *       substituted the proxy in the real Dagger graph.</li>
 *   <li><b>Pass-through interception</b> — calling {@code greet("x")} returns the unchanged value,
 *       so the {@code TimedAspect} interceptor observes without altering the outcome.</li>
 *   <li><b>Timer recording (SUCCESS)</b> — a {@code Timer} named {@code vertique.example.greet} with
 *       {@code outcome=SUCCESS} is recorded, with count {@code >= 1}, on the caller-owned
 *       {@link SimpleMeterRegistry} (read deterministically, not via {@code MeterRegistryHolder}).</li>
 *   <li><b>Per-occurrence literal (F2)</b> — the second method's distinct {@code @Timed} value
 *       records a separate {@code vertique.example.farewell} timer, proving each occurrence's own
 *       annotation attributes reached the generated proxy.</li>
 *   <li><b>Timer recording (ERROR)</b> — a failing future records {@code outcome=ERROR}.</li>
 * </ol>
 */
class GreeterAopTest {

    private static final String GREET_TIMER = "vertique.example.greet";
    private static final String FAREWELL_TIMER = "vertique.example.farewell";
    private static final String BOOM_TIMER = "vertique.example.boom";

    /** Builds the component over a fresh, caller-owned registry. */
    private static GreeterComponent componentWith(SimpleMeterRegistry registry) {
        return DaggerGreeterComponent.builder()
                .aopRegistryModule(new AopRegistryModule(registry))
                .build();
    }

    /** Awaits a completed Vert.x {@link Future}, returning its value or throwing its cause. */
    private static <T> T await(Future<T> future) throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        future.onComplete(ar -> {
            if (ar.succeeded()) {
                value.set(ar.result());
            } else {
                error.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "future did not settle within 5s");
        if (error.get() != null) {
            throw error.get();
        }
        return value.get();
    }

    @Test
    @DisplayName("Dagger substitutes the generated Greeter$AopProxy at runtime")
    void daggerSubstitutesGeneratedProxy() {
        Greeter greeter = componentWith(new SimpleMeterRegistry()).greeter();
        assertEquals(
                "Greeter$AopProxy",
                greeter.getClass().getSimpleName(),
                "Dagger must inject the generated proxy, not a plain Greeter — generated subclass: "
                        + greeter.getClass().getName());
    }

    @Test
    @DisplayName("greet() passes the value through unchanged")
    void greetPassesValueThrough() throws Throwable {
        Greeter greeter = componentWith(new SimpleMeterRegistry()).greeter();
        assertEquals("Hello, world!", await(greeter.greet("world")));
    }

    @Test
    @DisplayName("greet() records a SUCCESS timer named vertique.example.greet with count >= 1")
    void greetRecordsSuccessTimer() throws Throwable {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Greeter greeter = componentWith(registry).greeter();

        await(greeter.greet("x"));

        Timer timer = registry.find(GREET_TIMER).tag("outcome", "SUCCESS").timer();
        assertNotNull(
                timer,
                "TimedAspect must record a SUCCESS-tagged timer named " + GREET_TIMER
                        + " through the real generated proxy");
        assertTrue(timer.count() >= 1, "timer count must be >= 1, was " + timer.count());
    }

    @Test
    @DisplayName("farewell() records its own distinct timer (per-occurrence literal, F2)")
    void farewellRecordsDistinctTimer() throws Throwable {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Greeter greeter = componentWith(registry).greeter();

        assertEquals("Goodbye, x.", await(greeter.farewell("x")));

        Timer timer = registry.find(FAREWELL_TIMER)
                .tag("outcome", "SUCCESS")
                .tag("tone", "formal")
                .timer();
        assertNotNull(
                timer,
                "farewell() must record its OWN " + FAREWELL_TIMER + " timer with its extraTags — "
                        + "proves each @Timed occurrence's attributes reached the generated proxy (F2)");
        assertTrue(timer.count() >= 1, "farewell timer count must be >= 1, was " + timer.count());
    }

    @Test
    @DisplayName("a failing future records an ERROR-tagged timer")
    void failingFutureRecordsErrorTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Greeter greeter = componentWith(registry).greeter();

        Throwable cause = null;
        try {
            await(greeter.boom("x"));
        } catch (Throwable t) {
            cause = t;
        }
        assertNotNull(cause, "boom() must fail");

        Timer timer = registry.find(BOOM_TIMER).tag("outcome", "ERROR").timer();
        assertNotNull(timer, "a failing future must record an ERROR-tagged timer named " + BOOM_TIMER);
        assertTrue(timer.count() >= 1, "error timer count must be >= 1, was " + timer.count());
    }
}
