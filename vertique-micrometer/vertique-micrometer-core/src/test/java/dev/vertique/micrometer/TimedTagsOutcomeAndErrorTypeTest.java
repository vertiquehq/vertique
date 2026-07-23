// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.FailingFutureService;
import dev.vertique.micrometer.TimedFixtures.FailingFutureService$AopProxy;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FROZEN §3b always-applied {@code outcome} / {@code error.type} tags on the {@code @Timed}
 * timer: {@code SUCCESS}/{@code none} on success and {@code ERROR}/{@code <ExcSimpleName>} on a failing
 * {@code Future}.
 *
 * <p><strong>RED:</strong> {@link TimedAspect} is a failing stub, so no {@code Timer} is recorded.
 */
class TimedTagsOutcomeAndErrorTypeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(registry, Optional.empty());

    @Test
    @DisplayName("successTagsAreCorrect: success → Timer 'myOp' tagged outcome=SUCCESS, error.type=none")
    void successTagsAreCorrect() {
        GreeterService bean = new GreeterService$AopProxy("hi", provider);

        Future<String> result = bean.greet("world");
        assertTrue(result.succeeded());

        Timer timer = registry.find("myOp")
                .tag("outcome", "SUCCESS")
                .tag("error.type", "none")
                .timer();
        assertNotNull(timer, "success records outcome=SUCCESS, error.type=none");
        assertEquals(1, timer.count());
    }

    @Test
    @DisplayName("errorTagsAreCorrect: failing Future → Timer 'myOp' tagged outcome=ERROR, error.type=RuntimeException")
    void errorTagsAreCorrect() {
        FailingFutureService bean = new FailingFutureService$AopProxy(provider);

        Future<String> result = bean.boom();
        assertTrue(result.failed(), "the failure passes through unchanged");

        Timer timer = registry.find("myOp")
                .tag("outcome", "ERROR")
                .tag("error.type", "RuntimeException")
                .timer();
        assertNotNull(timer, "a failing Future records outcome=ERROR, error.type=<exception simple name>");
        assertEquals(1, timer.count());
    }
}
