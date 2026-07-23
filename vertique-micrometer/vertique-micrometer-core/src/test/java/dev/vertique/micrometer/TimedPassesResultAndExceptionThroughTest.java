// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import dev.vertique.micrometer.TimedFixtures.ThrowingService;
import dev.vertique.micrometer.TimedFixtures.ThrowingService$AopProxy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FROZEN §3b pass-through guarantee for {@code @Timed}: the around-interceptor observes
 * but never alters the returned value, and never swallows or changes a thrown exception — the outcome
 * is identical with the aspect applied.
 *
 * <p><strong>RED:</strong> {@link TimedAspect} is a failing stub whose interceptor throws
 * {@link UnsupportedOperationException}, so neither the value nor the original exception reaches the
 * caller.
 */
class TimedPassesResultAndExceptionThroughTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(registry, Optional.empty());

    @Test
    @DisplayName("resultUnchanged: a @Timed method returning 'hi world' yields exactly that value")
    void resultUnchanged() {
        GreeterService bean = new GreeterService$AopProxy("hi", provider);

        Future<String> result = bean.greet("world");

        assertTrue(result.succeeded());
        assertEquals("hi world", result.result(), "the aspect does not alter the returned value");
    }

    @Test
    @DisplayName("exceptionUnchanged: a @Timed method throwing NPE propagates the same NPE to the caller")
    void exceptionUnchanged() {
        ThrowingService bean = new ThrowingService$AopProxy(provider);

        // A synchronous throw in the underlying method must propagate unchanged through the proxy.
        Future<String> result = bean.npe();

        assertTrue(result.failed(), "the synchronous throw surfaces as a failed outcome, not a swallowed one");
        Throwable cause = result.cause();
        assertInstanceOf(NullPointerException.class, cause, "the original NullPointerException propagates unchanged");
        assertEquals("npe", cause.getMessage());
    }

    @Test
    @DisplayName("outcomeIdenticalToUnproxiedBean: proxied value equals the raw bean's value")
    void outcomeIdenticalToUnproxiedBean() {
        GreeterService raw = new GreeterService("hi");
        GreeterService proxied = new GreeterService$AopProxy("hi", provider);

        Future<String> rawResult = raw.greet("world");
        Future<String> proxiedResult = proxied.greet("world");

        assertTrue(rawResult.succeeded() && proxiedResult.succeeded());
        assertEquals(
                rawResult.result(),
                proxiedResult.result(),
                "the @Timed proxy produces the identical outcome as the unproxied bean");
    }
}
