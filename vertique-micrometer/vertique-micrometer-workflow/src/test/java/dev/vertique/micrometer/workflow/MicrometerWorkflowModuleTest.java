// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.micrometer.MicrometerModule;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MicrometerWorkflowModuleTest {

    @Test
    void contributesTheMetricsObserverToTheExecutorObserverSet() {
        // Given a component over the Micrometer modules and an empty observer multibinding
        TestComponent component = DaggerMicrometerWorkflowModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(new JsonObject()))
                .build();

        // When the observer set is requested
        Set<WorkflowExecutorObserver> observers = component.executorObservers();

        // Then it holds exactly the metrics observer
        assertEquals(1, observers.size());
        assertInstanceOf(
                WorkflowExecutorMetricsObserver.class, observers.iterator().next());
    }

    @Singleton
    @Component(
            modules = {
                MicrometerModule.class,
                MicrometerWorkflowModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class,
                ObserverSetModule.class
            })
    interface TestComponent {
        Set<WorkflowExecutorObserver> executorObservers();
    }

    @Module
    abstract static class ObserverSetModule {
        @Multibinds
        abstract Set<WorkflowExecutorObserver> executorObservers();
    }

    @Module
    static final class TestConfigModule {
        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
