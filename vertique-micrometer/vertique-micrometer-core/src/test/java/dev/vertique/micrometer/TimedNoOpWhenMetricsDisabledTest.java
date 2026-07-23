// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FROZEN §3b "honors {@code MetricsConfig.enabled}" rule for {@code @Timed}: when metrics
 * are disabled the aspect is a no-op (records nothing) yet the underlying call still returns normally.
 *
 * <p><strong>RED:</strong> {@link TimedAspect} is a failing stub, so the call throws instead of
 * completing as the disabled-path contract requires.
 */
class TimedNoOpWhenMetricsDisabledTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("noTimerRecordedWhenDisabled: MetricsConfig.enabled=false → no meter, call returns normally")
    void noTimerRecordedWhenDisabled() {
        MetricsConfig disabled = MetricsConfig.builder().enabled(false).build();
        AspectProvider<Timed> provider = new TimedAspect(registry, Optional.of(disabled));

        GreeterService bean = new GreeterService$AopProxy("hi", provider);

        Future<String> result = bean.greet("world");

        assertTrue(result.succeeded(), "the call still completes normally when metrics are disabled");
        assertEquals("hi world", result.result());
        assertEquals(0, registry.getMeters().size(), "no meter is recorded when MetricsConfig.enabled() is false");
    }
}
