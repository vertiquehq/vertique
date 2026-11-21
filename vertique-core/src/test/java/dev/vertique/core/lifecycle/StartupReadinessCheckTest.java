// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import dev.vertique.core.health.Readiness;
import jakarta.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the startup readiness gate: {@code DOWN} until its startup step has run, one singleton as both the
 * readiness check and the step, and ordered after every other {@code AFTER_START} step.
 */
class StartupReadinessCheckTest {

    @Singleton
    @Component(modules = CoreLifecycleStepsModule.class)
    interface GateComponent {

        @Readiness
        Set<HealthCheck> readiness();

        Set<ApplicationStartupStep> startupSteps();
    }

    private static HealthStatus status(HealthCheck check) {
        HealthCheckResult result = check.check().result();
        return result.status();
    }

    @Test
    @DisplayName("reports DOWN until the startup step has run, then UP")
    void downUntilStarted() {
        StartupReadinessCheck gate = new StartupReadinessCheck();

        assertEquals("startup", gate.name());
        assertEquals(HealthStatus.DOWN, status(gate));

        assertTrue(gate.start().succeeded());

        assertEquals(HealthStatus.UP, status(gate));
    }

    @Test
    @DisplayName("is one singleton, contributed as a readiness check and as an AFTER_START step")
    void contributedOnceToBothSets() {
        GateComponent component = DaggerStartupReadinessCheckTest_GateComponent.create();

        HealthCheck check = component.readiness().stream()
                .filter(StartupReadinessCheck.class::isInstance)
                .findFirst()
                .orElseThrow();
        ApplicationStartupStep step = component.startupSteps().stream()
                .filter(StartupReadinessCheck.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertSame(check, step);
        assertEquals(HealthStatus.DOWN, status(check));
        step.start();
        assertEquals(HealthStatus.UP, status(check));
    }

    @Test
    @DisplayName("runs after every other AFTER_START step")
    void runsLastInAfterStart() {
        ApplicationStartupStep other = new ApplicationStartupStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public io.vertx.core.Future<Void> start() {
                return io.vertx.core.Future.succeededFuture();
            }
        };
        List<ApplicationStartupStep> ordered = Set.of(new StartupReadinessCheck(), other).stream()
                .sorted(Comparator.comparing(s -> s, LifecycleOrdered.comparator()))
                .toList();

        assertSame(other, ordered.get(0));
        assertTrue(ordered.get(1) instanceof StartupReadinessCheck);
    }
}
