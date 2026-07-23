// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.AspectProvider;
import dev.vertique.micrometer.TimedFixtures.AutoNamedService;
import dev.vertique.micrometer.TimedFixtures.AutoNamedService$AopProxy;
import dev.vertique.micrometer.TimedFixtures.GreeterService;
import dev.vertique.micrometer.TimedFixtures.GreeterService$AopProxy;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FROZEN §3b metric-name rule for {@code @Timed}: an empty {@code value()} auto-derives
 * {@code vertique.<simpleClassName>.<method>} (lower-dotted); an explicit {@code value()} is honored
 * verbatim.
 *
 * <p><strong>RED:</strong> {@link TimedAspect} is a failing stub, so no {@code Timer} is recorded
 * under any name.
 */
class TimedAutoDerivesMetricNameTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AspectProvider<Timed> provider = new TimedAspect(registry, Optional.empty());

    @Test
    @DisplayName(
            "emptyValueYieldsAutoDerivedName: @Timed(\"\") on AutoNamedService.greet → vertique.autonamedservice.greet")
    void emptyValueYieldsAutoDerivedName() {
        AutoNamedService bean = new AutoNamedService$AopProxy(provider);

        Future<String> result = bean.greet("world");
        assertTrue(result.succeeded());

        Timer timer = registry.find("vertique.autonamedservice.greet").timer();
        assertNotNull(timer, "empty value() auto-derives vertique.<simpleClassName>.<method> (lower-dotted)");
        assertEquals(1, timer.count());
    }

    @Test
    @DisplayName("explicitValueIsHonored: @Timed(\"myOp\") records under 'myOp', not the auto-derived name")
    void explicitValueIsHonored() {
        GreeterService bean = new GreeterService$AopProxy("hi", provider);

        Future<String> result = bean.greet("world");
        assertTrue(result.succeeded());

        assertNotNull(registry.find("myOp").timer(), "explicit value() is used verbatim");
        assertNull(
                registry.find("vertique.greeterservice.greet").timer(),
                "explicit value() suppresses the auto-derived name");
    }
}
