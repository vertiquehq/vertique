// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity proof for superclass-declared methods that inherit JAX-RS annotations from interfaces the
 * resource class implements (vertiquehq/vertique-dev#636).
 *
 * <p>Each shape is represented twice: as a plain compiled nested fixture scanned through
 * {@link JaxRsRouteRegistrar#scanResource(Object)}, and as source compiled through
 * {@link JaxRsPipelineProcessor}, whose generated {@code describe()} is called directly. Both sides
 * must yield the same normalized routes and the same invocation result.
 */
class SuperclassInterfaceAnnotationParityTest {

    private static final String PKG = "dev.vertique.test.sia";

    // --- Reflective fixtures ---

    interface Crud {
        @DELETE
        @Path("/{id}")
        @Operation(operationId = "deleteById")
        String delete(@PathParam("id") @DefaultValue("0") String id);
    }

    static class Base {
        public String delete(String id) {
            return "deleted " + id;
        }
    }

    @Path("/items")
    static class ItemResource extends Base implements Crud {}

    interface Readable {
        @GET
        @Path("/{id}")
        @Operation(operationId = "readById")
        String read(@PathParam("id") String id);
    }

    static class ReadableBase {
        public String read(String id) {
            return "read " + id;
        }
    }

    @Path("/docs")
    static class DocResource extends ReadableBase implements Readable {}

    // --- Generated-side sources ---

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import io.swagger.v3.oas.annotations.Operation;
                import jakarta.ws.rs.DELETE;
                import jakarta.ws.rs.DefaultValue;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                """ + body);
    }

    private static JavaFileObject crudSource() {
        return src("Crud", """
                public interface Crud {
                    @DELETE
                    @Path("/{id}")
                    @Operation(operationId = "deleteById")
                    String delete(@PathParam("id") @DefaultValue("0") String id);
                }
                """);
    }

    record ParityCase(
            String label,
            Object reflective,
            String generatedResource,
            List<JavaFileObject> sources,
            int expectedRoutes,
            List<String> expectedBacking) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                        new ParityCase(
                                "superclass method inherits DELETE + PathParam from resource interface",
                                new ItemResource(),
                                "ItemResource",
                                List.of(crudSource(), src("Base", """
                                        public class Base {
                                            public String delete(String id) {
                                                return "deleted " + id;
                                            }
                                        }
                                        """), src("ItemResource", """
                                        @Path("/items")
                                        public class ItemResource extends Base implements Crud {}
                                        """)),
                                1,
                                List.of("Base")),
                        new ParityCase(
                                "superclass method inherits GET from a second resource interface",
                                new DocResource(),
                                "DocResource",
                                List.of(src("Readable", """
                                        public interface Readable {
                                            @GET
                                            @Path("/{id}")
                                            @Operation(operationId = "readById")
                                            String read(@PathParam("id") String id);
                                        }
                                        """), src("ReadableBase", """
                                        public class ReadableBase {
                                            public String read(String id) {
                                                return "read " + id;
                                            }
                                        }
                                        """), src("DocResource", """
                                        @Path("/docs")
                                        public class DocResource extends ReadableBase implements Readable {}
                                        """)),
                                1,
                                List.of("ReadableBase")))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("generated describe() and dispatch match the reflective scanner")
    void generatedMatchesReflective(ParityCase parityCase) throws Throwable {
        List<ResourceMethodMeta> reflective = new JaxRsRouteRegistrar().scanResource(parityCase.reflective());
        for (ResourceMethodMeta meta : reflective) {
            assertNull(meta.executionPlan(), "the nested fixture must take the reflective path: " + meta);
        }

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), parityCase.sources().toArray(JavaFileObject[]::new));
        result.assertSuccess();
        String resourceFqn = PKG + "." + parityCase.generatedResource();
        Object generatedInstance = result.generatedClassLoader()
                .loadClass(resourceFqn)
                .getDeclaredConstructor()
                .newInstance();
        List<ResourceMethodMeta> generated = callDescribe(result, generatedInstance, resourceFqn + "_JaxRsDescriptor");

        List<Route> reflectiveRoutes = routes(reflective, false);
        List<Route> generatedRoutes = routes(generated, true);

        assertEquals(parityCase.expectedRoutes(), reflectiveRoutes.size(), "reflective: " + reflectiveRoutes);
        assertEquals(
                parityCase.expectedBacking(),
                reflectiveRoutes.stream().map(Route::backingType).toList(),
                "reflective backing types");
        assertEquals(reflectiveRoutes, generatedRoutes, "generated routes must equal the reflective routes");
    }

    record Route(
            String httpMethod,
            String path,
            String operationId,
            String backingType,
            String methodName,
            List<String> params,
            Object invocationResult) {}

    private static List<Route> routes(List<ResourceMethodMeta> metas, boolean expectPlans) throws Throwable {
        List<Route> routes = new ArrayList<>();
        for (ResourceMethodMeta meta : metas) {
            List<String> params = meta.params().stream()
                    .map(p -> p.name() + "|" + p.source() + "|" + p.type().getName() + "|" + p.defaultValue())
                    .toList();
            Object[] args =
                    Collections.nCopies(meta.params().size(), (Object) "7").toArray();
            Object invocationResult;
            if (expectPlans) {
                assertNotNull(meta.executionPlan(), "generated route must carry an execution plan: " + meta);
                invocationResult = meta.executionPlan().invoke(meta.resourceInstance(), args);
            } else {
                assertNull(meta.executionPlan(), "route must keep reflective dispatch: " + meta);
                invocationResult = meta.method().invoke(meta.resourceInstance(), args);
            }
            routes.add(new Route(
                    meta.httpMethod(),
                    meta.path(),
                    meta.operationId(),
                    meta.method().getDeclaringClass().getSimpleName(),
                    meta.method().getName(),
                    params,
                    invocationResult));
        }
        routes.sort(Comparator.comparing(Route::backingType)
                .thenComparing(Route::httpMethod)
                .thenComparing(Route::path));
        return routes;
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, Object resourceInstance, String descriptorFqn) throws Exception {
        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method describe =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describe.invoke(descriptor, resourceInstance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());
    }
}
