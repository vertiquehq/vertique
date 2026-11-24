// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @Timed} lets a fatal error from the meter registry surface in the returned future while it
 * still swallows every other failure.
 */
class TimedFatalErrorTest {

    private static AspectProvider<Timed> providerOver(Throwable failure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                sneakyThrow(failure);
                return MeterFilterReply.NEUTRAL;
            }
        });
        MeterRegistry meterRegistry = registry;
        return new TimedAspect(meterRegistry, Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    @DisplayName("a LinkageError thrown by the registry during recording is not swallowed")
    void linkageErrorIsNotSwallowed() {
        GreeterService bean = new GreeterService$AopProxy("hello", providerOver(new NoClassDefFoundError("x")));

        // Vert.x's andThen settles the returned future with an error thrown by the recording callback
        Future<String> result = bean.greet("world");

        assertTrue(result.failed(), "the fatal error must not be swallowed");
        assertInstanceOf(NoClassDefFoundError.class, result.cause());
    }

    @Test
    @DisplayName("a RuntimeException thrown by the registry during recording is swallowed")
    void runtimeExceptionIsSwallowed() {
        GreeterService bean = new GreeterService$AopProxy("hello", providerOver(new IllegalStateException("x")));

        Future<String> result = bean.greet("world");

        assertTrue(result.succeeded(), "the registry failure must not alter the outcome");
        assertEquals("hello world", result.result());
    }
}
