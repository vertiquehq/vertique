// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandDeferred;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowExecutorMetricsObserver} swallows an ordinary failure of the meter registry but lets a fatal
 * error ({@link LinkageError} here) reach the caller.
 */
class WorkflowExecutorMetricsObserverFatalErrorTest {

    private static MeterRegistry registryThrowing(Throwable failure) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                sneakyThrow(failure);
                return MeterFilterReply.NEUTRAL;
            }
        });
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    @DisplayName("a fatal error from the registry reaches the caller")
    void fatalErrorPropagates() {
        var observer = new WorkflowExecutorMetricsObserver(
                registryThrowing(new NoClassDefFoundError("simulated")),
                MetricsConfig.builder().build());
        assertThrows(NoClassDefFoundError.class, () -> observer.onEvent(new CommandDeferred("START", 1)));
    }

    @Test
    @DisplayName("an ordinary failure from the registry is swallowed")
    void ordinaryFailureIsSwallowed() {
        var observer = new WorkflowExecutorMetricsObserver(
                registryThrowing(new IllegalStateException("simulated")),
                MetricsConfig.builder().build());
        assertDoesNotThrow(() -> observer.onEvent(new CommandDeferred("START", 1)));
    }
}
