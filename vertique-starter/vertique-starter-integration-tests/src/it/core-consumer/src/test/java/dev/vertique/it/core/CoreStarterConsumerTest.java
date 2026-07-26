// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.starter.core.CoreApplicationModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Consumer contract tests for {@code vertique-starter-core}, executed from an isolated Maven
 * repository against the published starter artifact.
 */
class CoreStarterConsumerTest {

    /** Timeout applied when closing the Vert.x instance a graph test owns. */
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    // --- Aggregate shape ---

    @Test
    void aggregateMembershipIsFrozen() {
        Module module = CoreApplicationModule.class.getAnnotation(Module.class);
        assertNotNull(module, "CoreApplicationModule must be annotated with @dagger.Module");

        Class<?>[] includes = module.includes();
        assertEquals(4, includes.length, "Core aggregate membership must be exactly four modules: " + Arrays.toString(includes));

        Set<Class<?>> actual = Arrays.stream(includes).collect(Collectors.toSet());
        Set<Class<?>> expected = Set.of(
                VertxModule.class, ConfigParsingModule.class, DeployerModule.class, CoreLifecycleStepsModule.class);
        assertEquals(expected, actual, "Core aggregate membership drifted from the frozen contract");
    }

    @Test
    void aggregateDeclaresNoBindingsOrState() {
        int modifiers = CoreApplicationModule.class.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), "Aggregate must be public");
        assertTrue(Modifier.isAbstract(modifiers), "Aggregate must be abstract");

        assertEquals(
                0,
                CoreApplicationModule.class.getDeclaredFields().length,
                "Aggregate must declare no state: " + Arrays.toString(CoreApplicationModule.class.getDeclaredFields()));
        assertEquals(
                0,
                CoreApplicationModule.class.getDeclaredMethods().length,
                "Aggregate must declare no bindings: "
                        + Arrays.toString(CoreApplicationModule.class.getDeclaredMethods()));
        assertEquals(
                0,
                CoreApplicationModule.class.getDeclaredClasses().length,
                "Aggregate must declare no nested types: "
                        + Arrays.toString(CoreApplicationModule.class.getDeclaredClasses()));

        Annotation[] annotations = CoreApplicationModule.class.getDeclaredAnnotations();
        assertEquals(1, annotations.length, "Aggregate must carry only @dagger.Module: " + Arrays.toString(annotations));
        assertEquals(
                Module.class,
                annotations[0].annotationType(),
                "Aggregate must carry no scope or qualifier annotation beyond @dagger.Module");
    }

    // --- Generated factory and graph ---

    @Test
    void generatedFactoryBuildsWithTransitivelyIncludedVertxModule() throws Exception {
        withComponent(component -> assertNotNull(
                component, "Generated factory must build a component from the transitively included VertxModule"));
    }

    @Test
    void componentExposesCoreLifecycleBindings() throws Exception {
        withComponent(component -> {
            Set<ApplicationStartupStep> startupSteps = component.startupSteps();
            assertNotNull(startupSteps, "startupSteps() must resolve");
            assertFalse(startupSteps.isEmpty(), "Core starter must contribute the framework startup steps");

            Set<LifecyclePhase> phases =
                    startupSteps.stream().map(ApplicationStartupStep::phase).collect(Collectors.toSet());
            assertTrue(
                    phases.containsAll(Set.of(LifecyclePhase.CONFIGURE, LifecyclePhase.VALIDATE)),
                    "Core starter must wire the CONFIGURE and VALIDATE steps, found phases " + phases);

            assertNotNull(component.shutdownSteps(), "shutdownSteps() must resolve");
            assertNotNull(component.verticleDeploymentManager(), "verticleDeploymentManager() must resolve");
        });
    }

    // --- Internal helpers ---

    /**
     * Builds the component from a real {@link Vertx} instance through the generated factory, runs the
     * given assertions against it, and always closes Vert.x afterwards.
     *
     * @param assertions the assertions to run against the built component; must not be {@code null}
     * @throws Exception when closing the Vert.x instance fails or times out
     */
    private static void withComponent(Consumer<CoreStarterConsumer> assertions) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());
            assertions.accept(new CoreStarterConsumerVertiqueComponentFactory().build(runtime));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
