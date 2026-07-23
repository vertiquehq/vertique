// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApplicationStartupStep} and {@link ApplicationShutdownStep}: the non-verticle
 * lifecycle step SPIs. Verifies that an anonymous implementation exposes its own {@link
 * LifecycleOrdered#phase() phase} and {@link ApplicationStartupStep#start() start} /
 * {@link ApplicationShutdownStep#stop() stop} work, while inheriting the {@link
 * LifecycleOrdered#priority() priority} and {@link LifecycleOrdered#orderKey() orderKey} defaults from
 * {@link LifecycleOrdered}.
 */
class ApplicationLifecycleStepTest {

    @Test
    @DisplayName("startup step exposes phase + start() and inherits the priority/orderKey defaults")
    void startupStep_exposesPhaseAndStart_inheritsDefaults() {
        Future<Void> done = Future.succeededFuture();
        ApplicationStartupStep step = new ApplicationStartupStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.CONFIGURE;
            }

            @Override
            public Future<Void> start() {
                return done;
            }
        };

        assertEquals(LifecyclePhase.CONFIGURE, step.phase());
        assertSame(done, step.start());
        assertEquals(0, step.priority());
        assertEquals(step.getClass().getName(), step.orderKey());
        assertTrue(step.orderKey().contains("ApplicationLifecycleStepTest"));
    }

    @Test
    @DisplayName("shutdown step exposes phase + stop() and inherits the priority/orderKey defaults")
    void shutdownStep_exposesPhaseAndStop_inheritsDefaults() {
        Future<Void> done = Future.succeededFuture();
        ApplicationShutdownStep step = new ApplicationShutdownStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public Future<Void> stop() {
                return done;
            }
        };

        assertEquals(LifecyclePhase.AFTER_START, step.phase());
        assertSame(done, step.stop());
        assertEquals(0, step.priority());
        assertEquals(step.getClass().getName(), step.orderKey());
        assertTrue(step.orderKey().contains("ApplicationLifecycleStepTest"));
    }
}
