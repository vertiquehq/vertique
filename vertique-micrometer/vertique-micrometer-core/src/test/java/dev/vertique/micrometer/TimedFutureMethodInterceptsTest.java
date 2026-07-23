// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Centerpiece runtime proof for the slice-1.3 {@code @Timed} aspect: a {@code @Timed}-annotated
 * {@code Future<String>} bean method, exercised through a {@code $AopProxy} at RUNTIME, records a
 * {@link Timer} on a real {@link SimpleMeterRegistry} and passes the value through.
 *
 * <p><strong>Runtime-proof approach:</strong> direct-instantiation fallback — see {@link TimedFixtures}
 * for why a full APT+Dagger pipeline is not stood up in this module and how the hand-written proxy
 * mirrors {@code AopProxyEmitter}'s output. The {@code injectedInstanceIsTheProxy} test still asserts
 * the property Dagger substitution would establish: the resolved instance is a {@code <Bean>$AopProxy}.
 *
 * <p><strong>RED:</strong> {@link TimedAspect} is a stub whose interceptor throws
 * {@link UnsupportedOperationException}, so the proxy call fails and no {@code Timer} is recorded.
 */
class TimedFutureMethodInterceptsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(registry, Optional.empty());

    @Test
    @DisplayName("injectedBeanIsTheProxy: resolved instance is a <Bean>$AopProxy and the call records a Timer")
    void injectedBeanIsTheProxy() {
        GreeterService bean = new GreeterService$AopProxy("hi", provider);

        // The resolved instance is the generated proxy subclass (what Dagger substitution establishes).
        assertTrue(
                bean.getClass().getSimpleName().endsWith("$AopProxy"),
                "injected instance must be the generated proxy, not the raw bean");
        assertTrue(bean instanceof GreeterService, "the proxy is-a GreeterService");

        Future<String> result = bean.greet("world");

        assertTrue(result.succeeded(), "the proxied Future-returning call completes successfully");
        assertEquals("hi world", result.result());

        Timer timer = registry.find("myOp").timer();
        assertNotNull(timer, "the @Timed method invocation records a Timer");
        assertEquals(1, timer.count(), "exactly one timing sample is recorded");
    }
}
