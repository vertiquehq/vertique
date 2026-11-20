// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxQueueProbe;
import dev.vertique.inboxoutbox.OutboxQueueSample;
import dev.vertique.micrometer.MicrometerModule;
import dev.vertique.micrometer.outbox.MicrometerOutboxModule;
import dev.vertique.micrometer.outbox.OutboxMetricsConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Wires the module from a package other than its own, as an application component does. */
class MicrometerOutboxModuleTest {

    private static TestComponent component(JsonObject config) {
        return DaggerMicrometerOutboxModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(config))
                .build();
    }

    @Test
    void contributesTheObserverAndTheSamplerSteps() {
        TestComponent component = component(new JsonObject());

        Set<OutboxPublishObserver> observers = component.observers();
        assertEquals(1, observers.size());
        assertTrue(observers.iterator().next().getClass().getSimpleName().contains("OutboxPublishMetricsObserver"));
        assertEquals(1, component.startupSteps().size());
        assertEquals(1, component.shutdownSteps().size());
        assertInstanceOf(
                ApplicationStartupStep.class,
                component.startupSteps().iterator().next());
        assertEquals(
                component.startupSteps().iterator().next(),
                component.shutdownSteps().iterator().next(),
                "start and stop are one object, so stop cancels the timer start created");
    }

    @Test
    void sampleIntervalDefaultsToFiveSeconds() {
        assertEquals(5_000L, component(new JsonObject()).config().queueSampleIntervalMs());
    }

    @Test
    void sampleIntervalIsReadFromMetricsOutbox() {
        JsonObject config = new JsonObject()
                .put("metrics", new JsonObject().put("outbox", new JsonObject().put("queueSampleIntervalMs", 2500)));

        assertEquals(2_500L, component(config).config().queueSampleIntervalMs());
    }

    @Test
    void sampleIntervalBelowTheMinimumFailsAndNamesTheKey() {
        JsonObject config = new JsonObject()
                .put("metrics", new JsonObject().put("outbox", new JsonObject().put("queueSampleIntervalMs", 999)));
        TestComponent component = component(config);

        ConfigurationException failure = assertThrows(ConfigurationException.class, component::config);

        assertTrue(failure.getMessage().contains("metrics.outbox.queueSampleIntervalMs"));
    }

    @Singleton
    @Component(
            modules = {
                MicrometerModule.class,
                MicrometerOutboxModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class,
                TestSetsModule.class
            })
    interface TestComponent {
        Set<OutboxPublishObserver> observers();

        Set<ApplicationStartupStep> startupSteps();

        Set<ApplicationShutdownStep> shutdownSteps();

        OutboxMetricsConfig config();
    }

    @Module
    abstract static class TestSetsModule {
        @Multibinds
        abstract Set<OutboxPublishObserver> observers();

        @Multibinds
        abstract Set<ApplicationStartupStep> startupSteps();

        @Multibinds
        abstract Set<ApplicationShutdownStep> shutdownSteps();
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

        @Provides
        @Singleton
        Vertx vertx() {
            return org.mockito.Mockito.mock(Vertx.class);
        }

        @Provides
        @Singleton
        OutboxQueueProbe probe() {
            return () -> Future.succeededFuture(new OutboxQueueSample(0, Duration.ZERO, 0));
        }
    }
}
