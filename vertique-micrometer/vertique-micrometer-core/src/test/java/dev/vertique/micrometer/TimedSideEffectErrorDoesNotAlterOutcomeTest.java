// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the error-isolation guarantee of {@code @Timed}: an {@link Error} thrown by the
 * {@link MeterRegistry} during timer recording must not alter the intercepted call's outcome.
 *
 * <p>RED: with {@code catch (Exception)} in {@link TimedAspect#record}, a registry that throws an
 * {@link Error} during recording escapes the catch block and propagates to the caller — altering
 * the outcome of a call that should have succeeded. GREEN: once widened to {@code catch (Throwable)}
 * the {@code Error} is swallowed and the call's original success is returned unchanged.
 */
class TimedSideEffectErrorDoesNotAlterOutcomeTest {

    // --- test double ---

    /**
     * Builds a {@link MeterRegistry} whose {@link MeterFilter#accept} throws an
     * {@link OutOfMemoryError} for every meter, simulating a pathological registry failure with a
     * non-{@link Exception} {@link Throwable} during the timer-recording side-effect.
     *
     * @return a registry that throws {@link OutOfMemoryError} on any meter registration attempt
     */
    private static MeterRegistry errorThrowingRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(io.micrometer.core.instrument.Meter.Id id) {
                throw new OutOfMemoryError("simulated registry OOM during meter registration");
            }
        });
        return registry;
    }

    // --- tests ---

    private final MeterRegistry errorRegistry = errorThrowingRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(errorRegistry, Optional.empty());

    /**
     * Given a registry that throws an {@link OutOfMemoryError} during timer recording, when a
     * {@code @Timed} method returns successfully, then the returned future must still succeed with the
     * original value — the recording {@code Error} must not escape.
     */
    @Test
    @DisplayName("successOutcomeUnchangedWhenRegistryThrowsError: a successful call remains successful"
            + " even when the registry throws an Error during recording")
    void successOutcomeUnchangedWhenRegistryThrowsError() {
        GreeterService bean = new GreeterService$AopProxy("hello", provider);

        Future<String> result = bean.greet("world");

        assertTrue(result.succeeded(), "the Error thrown by the registry must not alter the successful outcome");
        assertEquals("hello world", result.result(), "the original value must be returned unchanged");
    }
}
