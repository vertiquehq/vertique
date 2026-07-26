// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.management.ManagementModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.services.ServiceDeploymentShutdownStep;
import dev.vertique.services.ServiceDeploymentStartupStep;
import dev.vertique.starter.core.CoreApplicationModule;
import dev.vertique.starter.services.ServicesApplicationModule;
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
 * Consumer contract tests for {@code vertique-starter-services}, executed from an isolated Maven
 * repository against the published starter artifact.
 */
class ServicesStarterConsumerTest {

    /** Timeout applied when closing the Vert.x instance a graph test owns. */
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    // --- Aggregate shape ---

    @Test
    void aggregateMembershipIsFrozen() {
        Module module = ServicesApplicationModule.class.getAnnotation(Module.class);
        assertNotNull(module, "ServicesApplicationModule must be annotated with @dagger.Module");

        Class<?>[] includes = module.includes();
        assertEquals(
                3,
                includes.length,
                "Services aggregate membership must be exactly three modules: " + Arrays.toString(includes));

        Set<Class<?>> actual = Arrays.stream(includes).collect(Collectors.toSet());
        Set<Class<?>> expected =
                Set.of(CoreApplicationModule.class, DispatchModule.class, ManagementModule.class);
        assertEquals(expected, actual, "Services aggregate membership drifted from the frozen contract");
    }

    @Test
    void aggregateDeclaresNoBindingsOrState() {
        int modifiers = ServicesApplicationModule.class.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), "Aggregate must be public");
        assertTrue(Modifier.isAbstract(modifiers), "Aggregate must be abstract");

        assertEquals(
                0,
                ServicesApplicationModule.class.getDeclaredFields().length,
                "Aggregate must declare no state: "
                        + Arrays.toString(ServicesApplicationModule.class.getDeclaredFields()));
        assertEquals(
                0,
                ServicesApplicationModule.class.getDeclaredMethods().length,
                "Aggregate must declare no bindings: "
                        + Arrays.toString(ServicesApplicationModule.class.getDeclaredMethods()));
        assertEquals(
                0,
                ServicesApplicationModule.class.getDeclaredClasses().length,
                "Aggregate must declare no nested types: "
                        + Arrays.toString(ServicesApplicationModule.class.getDeclaredClasses()));

        Annotation[] annotations = ServicesApplicationModule.class.getDeclaredAnnotations();
        assertEquals(1, annotations.length, "Aggregate must carry only @dagger.Module: " + Arrays.toString(annotations));
        assertEquals(
                Module.class,
                annotations[0].annotationType(),
                "Aggregate must carry no scope or qualifier annotation beyond @dagger.Module");
    }

    // --- Generated factory and graph ---

    @Test
    void generatedFactoryBuildsHeadlessServicesApplicationGraph() throws Exception {
        withComponent(component -> assertNotNull(
                component,
                "Generated factory must build the complete headless services graph from the aggregate, with no REST module present"));
    }

    @Test
    void componentExposesDispatchAndManagementBindings() throws Exception {
        withComponent(component -> {
            assertNotNull(component.serviceClientFactory(), "Dispatch binding (ServiceClientFactory) must resolve");
            assertNotNull(
                    component.serviceContractRegistry(), "Dispatch binding (ServiceContractRegistry) must resolve");

            Set<ApplicationStartupStep> startupSteps = component.startupSteps();
            assertNotNull(startupSteps, "startupSteps() must resolve");
            assertTrue(
                    startupSteps.stream().anyMatch(ServiceDeploymentStartupStep.class::isInstance),
                    "Services starter must contribute the SERVICES-phase deployment startup step, found "
                            + startupSteps);

            Set<ApplicationShutdownStep> shutdownSteps = component.shutdownSteps();
            assertNotNull(shutdownSteps, "shutdownSteps() must resolve");
            assertTrue(
                    shutdownSteps.stream().anyMatch(ServiceDeploymentShutdownStep.class::isInstance),
                    "Services starter must contribute the paired deployment shutdown step, found " + shutdownSteps);

            assertNotNull(component.managementVerticle(), "Management binding (ManagementVerticle) must resolve");
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
    private static void withComponent(Consumer<ServicesStarterConsumer> assertions) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());
            assertions.accept(new ServicesStarterConsumerVertiqueComponentFactory().build(runtime));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
