// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation and reflective invocation tests for T022's native registration emission:
 * {@link GeneratedJaxRsResourcesModuleEmitter} writes one {@code @Provides @IntoSet
 * GeneratedRestApplicationRegistration} method per registered {@code @RestApplication}
 * declaration (C-APP), with its evaluated {@code @ConditionalOnProperty} activation, no factory
 * and no {@code Provider}, and the module's package staying with the compilation unit's
 * DI-eligible resources when it has any (PF-27).
 *
 * <p>Each test compiles one or more small fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness}, then loads the generated {@code GeneratedJaxRsResourcesModule}
 * through the harness's generated class loader and invokes every registration method reflectively
 * with a {@code @VertxConfig JsonObject} argument, comparing the resulting registrations'
 * accessor tuples against the expected shape. It also reads rest-024's presence-gated resource
 * bindings' reflective parameter types, to prove they still gate on
 * {@code Set<GeneratedJaxRsApplicationRegistration>}, and checks that {@code Application} and
 * {@code @RestApplication} registration methods sharing a simple name share one name counter.
 */
class RestApplicationRegistrationEmitterTest {

    private static final JsonObject EMPTY_CONFIG = new JsonObject();
    private static final JsonObject MGMT_ENABLED_CONFIG =
            new JsonObject().put("tp007", new JsonObject().put("mgmt", new JsonObject().put("enabled", "true")));

    // -----------------------------------------------------------------------------------------
    // Fixture (1) — three declarations sharing a unit with two resources
    // -----------------------------------------------------------------------------------------

    private static final String MIXED_PKG = "dev.vertique.test.tp007.mixed";
    private static final String MIXED_MODULE = MIXED_PKG + ".GeneratedJaxRsResourcesModule";

    private static final JavaFileObject MIXED_PATH_RESOURCE =
            SourceFiles.inline(MIXED_PKG + ".PathResource", """
            package %s;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/p")
            public class PathResource {
                @Inject
                public PathResource() {}

                @GET
                public String get() { return ""; }
            }
            """.formatted(MIXED_PKG));

    private static final JavaFileObject MIXED_ORDER_RESOURCE =
            SourceFiles.inline(MIXED_PKG + ".OrderResource", """
            package %s;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/orders")
            public class OrderResource {
                @Inject
                public OrderResource() {}

                @GET
                public String list() { return ""; }
            }
            """.formatted(MIXED_PKG));

    private static final JavaFileObject MIXED_PUBLIC_API =
            SourceFiles.inline(MIXED_PKG + ".PublicApi", """
            package %s;

            import dev.vertique.rest.jaxrs.application.RestApplication;

            @RestApplication(
                    name = "public",
                    path = "/api/public/",
                    resources = {PathResource.class, OrderResource.class},
                    openapiPath = "openapi/public.yaml")
            interface PublicApi {}
            """.formatted(MIXED_PKG));

    private static final JavaFileObject MIXED_MGMT_API =
            SourceFiles.inline(MIXED_PKG + ".MgmtApi", """
            package %s;

            import dev.vertique.codegen.ConditionalOnProperty;
            import dev.vertique.rest.jaxrs.application.RestApplication;

            @ConditionalOnProperty(name = "tp007.mgmt.enabled")
            @RestApplication(name = "mgmt", path = "/api/mgmt", resources = OrderResource.class)
            interface MgmtApi {}
            """.formatted(MIXED_PKG));

    private static final JavaFileObject MIXED_OUTER =
            SourceFiles.inline(MIXED_PKG + ".Outer", """
            package %s;

            import dev.vertique.rest.jaxrs.application.RestApplication;

            public class Outer {

                @RestApplication(name = "inner", path = "inner", resources = PathResource.class)
                public interface Inner {}
            }
            """.formatted(MIXED_PKG));

    // -----------------------------------------------------------------------------------------
    // Fixture (2) — a unit holding only a discover = true declaration
    // -----------------------------------------------------------------------------------------

    private static final String ONLYAPP_PKG = "dev.vertique.test.tp007.onlyapp";
    private static final String ONLYAPP_MODULE = ONLYAPP_PKG + ".GeneratedJaxRsResourcesModule";

    private static final JavaFileObject ONLYAPP_ALL_API =
            SourceFiles.inline(ONLYAPP_PKG + ".AllApi", """
            package %s;

            import dev.vertique.rest.jaxrs.application.RestApplication;

            @RestApplication(name = "all", path = "/", discover = true)
            interface AllApi {}
            """.formatted(ONLYAPP_PKG));

    // -----------------------------------------------------------------------------------------
    // Fixture (3) — resources and a declaration in different sub-packages
    // -----------------------------------------------------------------------------------------

    private static final String ARES_MODULE = "a.res.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject ARES_ITEM_RESOURCE = SourceFiles.inline("a.res.ItemResource", """
            package a.res;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/items")
            public class ItemResource {
                @Inject
                public ItemResource() {}

                @GET
                public String list() { return ""; }
            }
            """);

    private static final JavaFileObject ARES_ITEMS_API = SourceFiles.inline("a.res.apps.ItemsApi", """
            package a.res.apps;

            import a.res.ItemResource;
            import dev.vertique.rest.jaxrs.application.RestApplication;

            @RestApplication(name = "items", path = "/items-app", resources = ItemResource.class)
            public interface ItemsApi {}
            """);

    // -----------------------------------------------------------------------------------------
    // Fixture (4) — two declarations and a rest-024 Application sharing the simple name Api
    // -----------------------------------------------------------------------------------------

    private static final String NAMES_PKG = "dev.vertique.test.tp007.names";
    private static final String NAMES_MODULE = NAMES_PKG + ".GeneratedJaxRsResourcesModule";

    private static final JavaFileObject NAMES_PATH_RESOURCE =
            SourceFiles.inline(NAMES_PKG + ".PathResource", """
            package %s;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/p")
            public class PathResource {
                @Inject
                public PathResource() {}

                @GET
                public String get() { return ""; }
            }
            """.formatted(NAMES_PKG));

    private static final JavaFileObject NAMES_A_API =
            SourceFiles.inline(NAMES_PKG + ".a.Api", """
            package %1$s.a;

            import %1$s.PathResource;
            import dev.vertique.rest.jaxrs.application.RestApplication;

            @RestApplication(name = "api-a", path = "/api/a", resources = PathResource.class)
            public interface Api {}
            """.formatted(NAMES_PKG));

    private static final JavaFileObject NAMES_B_API =
            SourceFiles.inline(NAMES_PKG + ".b.Api", """
            package %1$s.b;

            import %1$s.PathResource;
            import dev.vertique.rest.jaxrs.application.RestApplication;

            @RestApplication(name = "api-b", path = "/api/b", resources = PathResource.class)
            public interface Api {}
            """.formatted(NAMES_PKG));

    private static final JavaFileObject NAMES_C_API =
            SourceFiles.inline(NAMES_PKG + ".c.Api", """
            package %s.c;

            import jakarta.ws.rs.ApplicationPath;
            import jakarta.ws.rs.core.Application;

            @ApplicationPath("/api/c")
            public class Api extends Application {
                public Api() {}
            }
            """.formatted(NAMES_PKG));

    // -----------------------------------------------------------------------------------------
    // TP-007 — one native registration is emitted per declaration, with its evaluated activation
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("TP-007 — one native registration is emitted per declaration, with its evaluated activation")
    void emitsOneNativeRegistrationPerDeclaration() {
        assertAll(
                "TP-007 fixtures",
                () -> tp007MixedFixture(),
                () -> tp007DiscoveryOnlyFixture(),
                () -> tp007PackageStaysWithResourcesFixture(),
                () -> tp007SharedRegistrationNameCounterFixture());
    }

    private void tp007MixedFixture() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                MIXED_PATH_RESOURCE,
                MIXED_ORDER_RESOURCE,
                MIXED_PUBLIC_API,
                MIXED_MGMT_API,
                MIXED_OUTER);
        result.assertSuccess();
        logGeneratedSource("TP-007 mixed fixture module", sourceOf(result, MIXED_MODULE));

        Class<?> module = result.loadGeneratedClass(MIXED_MODULE);
        Class<?> publicApiType = result.loadGeneratedClass(MIXED_PKG + ".PublicApi");
        Class<?> pathResourceType = result.loadGeneratedClass(MIXED_PKG + ".PathResource");
        Class<?> orderResourceType = result.loadGeneratedClass(MIXED_PKG + ".OrderResource");
        Class<?> mgmtApiType = result.loadGeneratedClass(MIXED_PKG + ".MgmtApi");
        Class<?> innerType = result.loadGeneratedClass(MIXED_PKG + ".Outer$Inner");

        List<GeneratedRestApplicationRegistration> emptyConfigRegistrations = registrationsOf(module, EMPTY_CONFIG);
        assertEquals(
                3,
                emptyConfigRegistrations.size(),
                () -> "Expected exactly three registration methods" + describe(emptyConfigRegistrations));

        assertRegistrationEquals(
                findByName(emptyConfigRegistrations, "public"),
                publicApiType,
                "public",
                "/api/public",
                List.of(pathResourceType, orderResourceType),
                false,
                "openapi/public.yaml",
                true);
        assertRegistrationEquals(
                findByName(emptyConfigRegistrations, "inner"),
                innerType,
                "inner",
                "/inner",
                List.of(pathResourceType),
                false,
                "",
                true);
        assertRegistrationEquals(
                findByName(emptyConfigRegistrations, "mgmt"),
                mgmtApiType,
                "mgmt",
                "/api/mgmt",
                List.of(orderResourceType),
                false,
                "",
                false);

        List<GeneratedRestApplicationRegistration> mgmtEnabledRegistrations =
                registrationsOf(module, MGMT_ENABLED_CONFIG);
        assertTrue(
                findByName(mgmtEnabledRegistrations, "mgmt").active(),
                "Expected mgmt's registration to be active once tp007.mgmt.enabled=true");

        result.assertGeneratedSourceDoesNotContain(MIXED_MODULE, "PublicApi::new");
        result.assertGeneratedSourceDoesNotContain(MIXED_MODULE, "Provider<PublicApi>");

        // rest-024's presence-gated bindings are unchanged: each still gates on the
        // Set<GeneratedJaxRsApplicationRegistration>, not on the native registrations.
        assertResourceBindingGatesOnApplicationRegistrations(module, "pathResourceBinding");
        assertResourceBindingGatesOnApplicationRegistrations(module, "orderResourceBinding");
    }

    private void tp007DiscoveryOnlyFixture() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), ONLYAPP_ALL_API);
        result.assertSuccess();
        assertTrue(
                moduleWritten(result, ONLYAPP_MODULE),
                () -> "Expected the module at '" + ONLYAPP_MODULE + "'" + diagnosticsSummary(result));

        Class<?> module = result.loadGeneratedClass(ONLYAPP_MODULE);
        Class<?> allApiType = result.loadGeneratedClass(ONLYAPP_PKG + ".AllApi");
        List<GeneratedRestApplicationRegistration> registrations = registrationsOf(module, EMPTY_CONFIG);
        assertEquals(
                1, registrations.size(), () -> "Expected exactly one registration method" + describe(registrations));
        assertRegistrationEquals(registrations.get(0), allApiType, "all", "/", List.of(), true, "", true);
    }

    private void tp007PackageStaysWithResourcesFixture() {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), ARES_ITEM_RESOURCE, ARES_ITEMS_API);
        result.assertSuccess();
        assertTrue(
                moduleWritten(result, ARES_MODULE),
                () -> "Expected the module at '" + ARES_MODULE + "' (package resolved from resources)"
                        + diagnosticsSummary(result));
    }

    /**
     * One unit holds {@code a.Api} and {@code b.Api} declarations and a rest-024
     * {@code c.Api extends Application}: all three registration methods derive the base name
     * {@code apiRegistration}, and share one counter. The {@code Application} registration is
     * named first, then the declarations in fully-qualified-name order.
     */
    private void tp007SharedRegistrationNameCounterFixture() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), NAMES_PATH_RESOURCE, NAMES_A_API, NAMES_B_API, NAMES_C_API);
        result.assertSuccess();
        logGeneratedSource("TP-007 shared name counter module", sourceOf(result, NAMES_MODULE));

        Class<?> module = result.loadGeneratedClass(NAMES_MODULE);
        Class<?> aApiType = result.loadGeneratedClass(NAMES_PKG + ".a.Api");
        Class<?> bApiType = result.loadGeneratedClass(NAMES_PKG + ".b.Api");
        Class<?> cApiType = result.loadGeneratedClass(NAMES_PKG + ".c.Api");

        List<String> registrationMethodNames = Arrays.stream(module.getDeclaredMethods())
                .filter(m -> GeneratedJaxRsApplicationRegistration.class.equals(m.getReturnType())
                        || GeneratedRestApplicationRegistration.class.equals(m.getReturnType()))
                .map(Method::getName)
                .sorted()
                .toList();
        assertEquals(
                List.of("apiRegistration", "apiRegistration_2", "apiRegistration_3"),
                registrationMethodNames,
                "Expected three distinctly named registration methods sharing one counter");

        Object applicationRegistration = invokeRegistrationMethod(module, "apiRegistration", EMPTY_CONFIG);
        GeneratedJaxRsApplicationRegistration first = assertInstanceOf(
                GeneratedJaxRsApplicationRegistration.class,
                applicationRegistration,
                "Expected 'apiRegistration' to be rest-024's Application registration");
        assertEquals(cApiType, first.type(), "apiRegistration type()");

        GeneratedRestApplicationRegistration second = assertInstanceOf(
                GeneratedRestApplicationRegistration.class,
                invokeRegistrationMethod(module, "apiRegistration_2", EMPTY_CONFIG),
                "Expected 'apiRegistration_2' to be a native registration");
        assertEquals(aApiType, second.declaringType(), "apiRegistration_2 declaringType()");
        assertEquals("api-a", second.name(), "apiRegistration_2 name()");

        GeneratedRestApplicationRegistration third = assertInstanceOf(
                GeneratedRestApplicationRegistration.class,
                invokeRegistrationMethod(module, "apiRegistration_3", EMPTY_CONFIG),
                "Expected 'apiRegistration_3' to be a native registration");
        assertEquals(bApiType, third.declaringType(), "apiRegistration_3 declaringType()");
        assertEquals("api-b", third.name(), "apiRegistration_3 name()");
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Invokes every declared method of {@code moduleClass} returning
     * {@link GeneratedRestApplicationRegistration} with {@code config}, asserting along the way
     * that no such method takes more than the single {@code @VertxConfig JsonObject} parameter
     * (no {@code Provider}, since the declaring interface is never constructed).
     */
    private static List<GeneratedRestApplicationRegistration> registrationsOf(Class<?> moduleClass, JsonObject config) {
        List<GeneratedRestApplicationRegistration> registrations = new ArrayList<>();
        for (Method method : moduleClass.getDeclaredMethods()) {
            if (!GeneratedRestApplicationRegistration.class.equals(method.getReturnType())) {
                continue;
            }
            assertEquals(
                    1,
                    method.getParameterCount(),
                    () -> "Expected '" + method.getName() + "' to take exactly one parameter (no Provider)");
            assertEquals(
                    JsonObject.class,
                    method.getParameterTypes()[0],
                    () -> "Expected '" + method.getName() + "'s sole parameter to be a JsonObject (@VertxConfig)");
            method.setAccessible(true);
            try {
                registrations.add((GeneratedRestApplicationRegistration) method.invoke(null, config));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionFailedError(
                        "Failed to invoke registration method '" + method.getName() + "': " + e.getMessage(), e);
            }
        }
        return registrations;
    }

    /**
     * Invokes {@code moduleClass}'s declared method {@code methodName}, asserting it takes only
     * the {@code @VertxConfig JsonObject}, and returns what it returned.
     */
    private static Object invokeRegistrationMethod(Class<?> moduleClass, String methodName, JsonObject config) {
        Method method = Arrays.stream(moduleClass.getDeclaredMethods())
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionFailedError(
                        "Expected a method named '" + methodName + "' in " + moduleClass.getName()));
        assertEquals(
                List.of(JsonObject.class),
                List.of(method.getParameterTypes()),
                () -> "Expected '" + methodName + "' to take only a JsonObject (@VertxConfig)");
        method.setAccessible(true);
        try {
            return method.invoke(null, config);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionFailedError(
                    "Failed to invoke registration method '" + methodName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Asserts that rest-024's presence-gated resource binding {@code methodName} still takes a
     * {@code Set<GeneratedJaxRsApplicationRegistration>} parameter, read from its reflective
     * generic parameter types.
     */
    private static void assertResourceBindingGatesOnApplicationRegistrations(Class<?> moduleClass, String methodName) {
        Method binding = Arrays.stream(moduleClass.getDeclaredMethods())
                .filter(m -> methodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionFailedError(
                        "Expected rest-024's resource binding '" + methodName + "' in " + moduleClass.getName()));
        List<Type> setParameterArguments = Arrays.stream(binding.getGenericParameterTypes())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .filter(p -> Set.class.equals(p.getRawType()))
                .flatMap(p -> Arrays.stream(p.getActualTypeArguments()))
                .toList();
        assertEquals(
                List.of(GeneratedJaxRsApplicationRegistration.class),
                setParameterArguments,
                () -> "Expected '" + methodName + "' to take exactly one Set parameter, a"
                        + " Set<GeneratedJaxRsApplicationRegistration>; generic parameter types: "
                        + Arrays.toString(binding.getGenericParameterTypes()));
    }

    private static GeneratedRestApplicationRegistration findByName(
            List<GeneratedRestApplicationRegistration> registrations, String name) {
        return registrations.stream()
                .filter(r -> name.equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionFailedError(
                        "Expected a registration named '" + name + "' among " + describe(registrations)));
    }

    private static String describe(List<GeneratedRestApplicationRegistration> registrations) {
        StringBuilder sb = new StringBuilder("\nRegistrations:\n");
        for (GeneratedRestApplicationRegistration r : registrations) {
            sb.append("  ")
                    .append(r.declaringType())
                    .append(" name=")
                    .append(r.name())
                    .append(" path=")
                    .append(r.path())
                    .append(" resources=")
                    .append(r.resources())
                    .append(" discover=")
                    .append(r.discover())
                    .append(" openapiPath=")
                    .append(r.openapiPath())
                    .append(" active=")
                    .append(r.active())
                    .append('\n');
        }
        return sb.toString();
    }

    private static void assertRegistrationEquals(
            GeneratedRestApplicationRegistration registration,
            Class<?> declaringType,
            String name,
            String path,
            List<Class<?>> resources,
            boolean discover,
            String openapiPath,
            boolean active) {
        assertEquals(declaringType, registration.declaringType(), "declaringType()");
        assertEquals(name, registration.name(), "name()");
        assertEquals(path, registration.path(), "path()");
        assertEquals(resources, registration.resources(), "resources()");
        assertEquals(discover, registration.discover(), "discover()");
        assertEquals(openapiPath, registration.openapiPath(), "openapiPath()");
        assertEquals(active, registration.active(), "active()");
    }

    private static boolean moduleWritten(ProcessorTestHarness.Result result, String generatedFqn) {
        return result.compilation().generatedSourceFile(generatedFqn).isPresent();
    }

    private static String sourceOf(ProcessorTestHarness.Result result, String generatedFqn) {
        var generated = result.compilation().generatedSourceFile(generatedFqn);
        assertTrue(
                generated.isPresent(),
                () -> "Expected generated source file for '" + generatedFqn + "' but none was found."
                        + diagnosticsSummary(result));
        try {
            return generated.get().getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionFailedError(
                    "Failed to read generated source for '" + generatedFqn + "': " + e.getMessage());
        }
    }

    private static void logGeneratedSource(String label, String source) {
        System.out.println("=== " + label + " ===");
        System.out.println(source);
    }

    private static String diagnosticsSummary(ProcessorTestHarness.Result result) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        result.compilation().diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
