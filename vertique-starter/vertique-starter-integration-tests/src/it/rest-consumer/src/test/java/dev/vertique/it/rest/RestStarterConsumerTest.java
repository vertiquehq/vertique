// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.SecurityModule;
import dev.vertique.rest.validation.RestValidationModule;
import dev.vertique.starter.core.CoreApplicationModule;
import dev.vertique.starter.rest.RestApplicationModule;
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
 * Consumer contract tests for {@code vertique-starter-rest}, executed from an isolated Maven
 * repository against the published starter artifact.
 */
class RestStarterConsumerTest {

    /** Timeout applied when closing the Vert.x instance a graph test owns. */
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    // --- Aggregate shape ---

    @Test
    void aggregateMembershipIsFrozen() {
        Module module = RestApplicationModule.class.getAnnotation(Module.class);
        assertNotNull(module, "RestApplicationModule must be annotated with @dagger.Module");

        Class<?>[] includes = module.includes();
        assertEquals(
                6, includes.length, "REST aggregate membership must be exactly six modules: " + Arrays.toString(includes));

        Set<Class<?>> actual = Arrays.stream(includes).collect(Collectors.toSet());
        Set<Class<?>> expected = Set.of(
                CoreApplicationModule.class,
                RestModule.class,
                RestValidationModule.class,
                AuthModule.class,
                SecurityModule.class,
                ManagementModule.class);
        assertEquals(expected, actual, "REST aggregate membership drifted from the frozen contract");
    }

    @Test
    void aggregateDeclaresNoBindingsOrState() {
        int modifiers = RestApplicationModule.class.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), "Aggregate must be public");
        assertTrue(Modifier.isAbstract(modifiers), "Aggregate must be abstract");

        assertEquals(
                0,
                RestApplicationModule.class.getDeclaredFields().length,
                "Aggregate must declare no state: " + Arrays.toString(RestApplicationModule.class.getDeclaredFields()));
        assertEquals(
                0,
                RestApplicationModule.class.getDeclaredMethods().length,
                "Aggregate must declare no bindings: "
                        + Arrays.toString(RestApplicationModule.class.getDeclaredMethods()));
        assertEquals(
                0,
                RestApplicationModule.class.getDeclaredClasses().length,
                "Aggregate must declare no nested types: "
                        + Arrays.toString(RestApplicationModule.class.getDeclaredClasses()));

        Annotation[] annotations = RestApplicationModule.class.getDeclaredAnnotations();
        assertEquals(1, annotations.length, "Aggregate must carry only @dagger.Module: " + Arrays.toString(annotations));
        assertEquals(
                Module.class,
                annotations[0].annotationType(),
                "Aggregate must carry no scope or qualifier annotation beyond @dagger.Module");
    }

    // --- Generated factory and graph ---

    @Test
    void generatedFactoryBuildsRestApplicationGraph() throws Exception {
        withComponent(component ->
                assertNotNull(component, "Generated factory must build the complete REST graph from the aggregate"));
    }

    @Test
    void componentExposesRestSecurityValidationAndManagementBindings() throws Exception {
        withComponent(component -> {
            assertNotNull(component.httpVerticle(), "Routing binding (HttpVerticle) must resolve");
            assertNotNull(
                    component.operationSchemaSource(), "Validation binding (OperationSchemaSource) must resolve");
            assertNotNull(
                    component.securityPolicyValidator(),
                    "Security-policy binding (SecurityPolicyValidator) must resolve");
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
    private static void withComponent(Consumer<RestStarterConsumer> assertions) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());
            assertions.accept(new RestStarterConsumerVertiqueComponentFactory().build(runtime));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
