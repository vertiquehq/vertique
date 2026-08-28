// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.resilience.dagger.ResilienceModule;
import dev.vertique.resilience.exception.ResilienceClosedException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-001 Dagger ownership proof for the canonical resilience module.
 *
 * <p>The component is intentionally minimal but real: Dagger constructs the application-scoped
 * {@link Resilience}, merges the module's {@link ApplicationShutdownStep} contribution into a
 * lifecycle set, and exposes that same runtime for the close-once assertion. The test does not
 * hand-construct a shutdown step or substitute a test runtime.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class CanonicalResilienceModuleTest {

    @Test
    @DisplayName("Dagger provides one Resilience singleton and one CONFIGURE shutdown step that closes it once")
    void daggerOwnsApplicationRuntimeAndClosesItOnce(Vertx vertx) throws Exception {
        TestComponent component =
                DaggerCanonicalResilienceModuleTest_TestComponent.factory().create(vertx);
        Resilience runtime = component.resilience();
        ResiliencePipeline pipeline = runtime.pipeline("dagger-owned-runtime")
                .timeout(timeout -> timeout.duration(Durations.ONE_HOUR))
                .build();
        Promise<String> supplier = Promise.promise();
        Future<String> active = pipeline.execute(supplier::future);

        assertSame(runtime, component.resilience(), "Resilience must be application-scoped in the Dagger graph");
        assertEquals(1, component.shutdownSteps().size(), "ResilienceModule must contribute one shutdown owner");

        ApplicationShutdownStep shutdownStep =
                component.shutdownSteps().iterator().next();
        assertEquals(LifecyclePhase.CONFIGURE, shutdownStep.phase());

        Future<Void> firstStop = shutdownStep.stop();
        Future<Void> secondStop = shutdownStep.stop();
        assertSame(firstStop, secondStop, "repeated shutdown-step calls must return the same close future");
        await(firstStop);
        await(secondStop);

        assertTrue(active.isComplete(), "Dagger shutdown must fence an active public execution");
        assertInstanceOf(ResilienceClosedException.class, active.cause());
        Future<Void> runtimeClose = runtime.close();
        assertSame(firstStop, runtimeClose, "the runtime facade must observe the shutdown step's close future");
        assertTrue(runtimeClose.succeeded(), "a later owning-runtime close must observe the closed state");

        supplier.complete("late");
        assertInstanceOf(ResilienceClosedException.class, active.cause());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Named duration fixture keeps the Dagger ownership scenario's active timer setup obvious. */
    private static final class Durations {
        private static final java.time.Duration ONE_HOUR = java.time.Duration.ofHours(1);

        private Durations() {}
    }

    /** Declares the host lifecycle set so the real resilience contribution can be composed in isolation. */
    @Module
    abstract static class TestLifecycleModule {

        @Multibinds
        abstract Set<ApplicationShutdownStep> shutdownSteps();
    }

    /** Minimal real Dagger graph for the module's runtime and shutdown ownership contract. */
    @Singleton
    @Component(modules = {ResilienceModule.class, TestLifecycleModule.class})
    interface TestComponent {

        Resilience resilience();

        Set<ApplicationShutdownStep> shutdownSteps();

        @Component.Factory
        interface Factory {

            TestComponent create(@BindsInstance Vertx vertx);
        }
    }
}
