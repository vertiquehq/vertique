// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
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
 * Parity proof for annotation inheritance through bound type variables and through superclass
 * overrides: the generated descriptor and the reflective scanner must describe the same routes, and
 * each row pins the absolute expected route (verb, path, backing type, parameter binding, roles and
 * invocation result) so that two engines that are both wrong cannot agree.
 *
 * <p>Each shape is a plain compiled nested fixture, scanned through
 * {@link JaxRsRouteRegistrar#scanResource(Object)}, and the same shape as source compiled through
 * {@link JaxRsPipelineProcessor}, whose generated {@code describe()} is called directly.
 */
class GenericInheritanceParityTest {

    private static final String PKG = "dev.vertique.test.gip";

    // --- Reflective fixtures ---

    interface SecuredCrud<I> {
        @DELETE
        @Path("/{id}")
        @RolesAllowed("admin")
        default String purge(@PathParam("id") I id) {
            return "generic " + id;
        }
    }

    @Path("/secured")
    static class SecuredOverride implements SecuredCrud<String> {
        @Override
        @DELETE
        public String purge(String id) {
            return "secured " + id;
        }
    }

    interface ReadApi<I> {
        @GET
        @Path("/{id}")
        @RolesAllowed("reader")
        String read(@PathParam("id") I id);
    }

    @Path("/reads")
    static class ReadResource implements ReadApi<String> {
        @Override
        public String read(String id) {
            return "read " + id;
        }
    }

    interface StringReadApi extends ReadApi<String> {}

    @Path("/forwarded")
    static class ForwardedResource implements StringReadApi {
        @Override
        public String read(String id) {
            return "forwarded " + id;
        }
    }

    interface MidApi<T extends CharSequence> extends ReadApi<T> {}

    @Path("/bounded")
    static class BoundedResource implements MidApi<String> {
        @Override
        public String read(String id) {
            return "bounded " + id;
        }
    }

    static class GenericBase<T> {
        @GET
        @Path("/{id}")
        @RolesAllowed("editor")
        public String get(@PathParam("id") T id) {
            return "base " + id;
        }
    }

    @Path("/derived")
    static class GenericDerived extends GenericBase<String> {
        @Override
        public String get(String id) {
            return "derived " + id;
        }
    }

    static class PlainBase {
        @GET
        @Path("/ping")
        @RolesAllowed("ops")
        public String ping() {
            return "base";
        }
    }

    @Path("/plain")
    static class PlainDerived extends PlainBase {
        @Override
        public String ping() {
            return "derived";
        }
    }

    static class Implementor {
        public String read(String id) {
            return "inherited " + id;
        }
    }

    @Path("/via-super")
    static class ViaSuperclass extends Implementor implements ReadApi<String> {}

    @Path("/overloads")
    static class Overloads implements ReadApi<String> {
        @Override
        public String read(String id) {
            return "read " + id;
        }

        public String read(String id, int times) {
            return "overload " + id;
        }
    }

    // --- Generated-side sources ---

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.DELETE;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                """ + body);
    }

    private static JavaFileObject readApi() {
        return src("ReadApi", """
                public interface ReadApi<I> {
                    @GET
                    @Path("/{id}")
                    @RolesAllowed("reader")
                    String read(@PathParam("id") I id);
                }
                """);
    }

    private static JavaFileObject genericBase() {
        return src("GenericBase", """
                public class GenericBase<T> {
                    @GET
                    @Path("/{id}")
                    @RolesAllowed("editor")
                    public String get(@PathParam("id") T id) {
                        return "base " + id;
                    }
                }
                """);
    }

    /**
     * One parity shape.
     *
     * @param label             display label
     * @param reflective        the plain compiled fixture instance
     * @param generatedResource simple name of the generated-side resource class
     * @param sources           the generated-side sources
     * @param expected          the absolute routes both sides must produce
     */
    record Shape(
            String label,
            Object reflective,
            String generatedResource,
            List<JavaFileObject> sources,
            List<String> expected) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                        new Shape(
                                "an override with its own verb inherits the interface's security (the fail-open case)",
                                new SecuredOverride(),
                                "SecuredOverride",
                                List.of(src("SecuredCrud", """
                                        public interface SecuredCrud<I> {
                                            @DELETE
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            default String purge(@PathParam("id") I id) {
                                                return "generic " + id;
                                            }
                                        }
                                        """), src("SecuredOverride", """
                                        @Path("/secured")
                                        public class SecuredOverride implements SecuredCrud<String> {
                                            @Override
                                            @DELETE
                                            public String purge(String id) {
                                                return "secured " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "DELETE /secured/{id} SecuredOverride id|PATH|java.lang.String roles=[admin] -> secured 7")),
                        new Shape(
                                "an implementation without a verb inherits verb, path, parameter and roles",
                                new ReadResource(),
                                "ReadResource",
                                List.of(readApi(), src("ReadResource", """
                                        @Path("/reads")
                                        public class ReadResource implements ReadApi<String> {
                                            @Override
                                            public String read(String id) {
                                                return "read " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /reads/{id} ReadResource id|PATH|java.lang.String roles=[reader] -> read 7")),
                        new Shape(
                                "a forwarding sub-interface binds the variable",
                                new ForwardedResource(),
                                "ForwardedResource",
                                List.of(readApi(), src("StringReadApi", """
                                        public interface StringReadApi extends ReadApi<String> {}
                                        """), src("ForwardedResource", """
                                        @Path("/forwarded")
                                        public class ForwardedResource implements StringReadApi {
                                            @Override
                                            public String read(String id) {
                                                return "forwarded " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /forwarded/{id} ForwardedResource id|PATH|java.lang.String roles=[reader] -> forwarded 7")),
                        new Shape(
                                "a bounded variable bound two interfaces up",
                                new BoundedResource(),
                                "BoundedResource",
                                List.of(readApi(), src("MidApi", """
                                        public interface MidApi<T extends CharSequence> extends ReadApi<T> {}
                                        """), src("BoundedResource", """
                                        @Path("/bounded")
                                        public class BoundedResource implements MidApi<String> {
                                            @Override
                                            public String read(String id) {
                                                return "bounded " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /bounded/{id} BoundedResource id|PATH|java.lang.String roles=[reader] -> bounded 7")),
                        new Shape(
                                "a generic superclass method overridden with a concrete type is one route",
                                new GenericDerived(),
                                "GenericDerived",
                                List.of(genericBase(), src("GenericDerived", """
                                        @Path("/derived")
                                        public class GenericDerived extends GenericBase<String> {
                                            @Override
                                            public String get(String id) {
                                                return "derived " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /derived/{id} GenericDerived id|PATH|java.lang.String roles=[editor] -> derived 7")),
                        new Shape(
                                "a non-generic annotated superclass method overridden without annotations",
                                new PlainDerived(),
                                "PlainDerived",
                                List.of(src("PlainBase", """
                                        public class PlainBase {
                                            @GET
                                            @Path("/ping")
                                            @RolesAllowed("ops")
                                            public String ping() {
                                                return "base";
                                            }
                                        }
                                        """), src("PlainDerived", """
                                        @Path("/plain")
                                        public class PlainDerived extends PlainBase {
                                            @Override
                                            public String ping() {
                                                return "derived";
                                            }
                                        }
                                        """)),
                                List.of("GET /plain/ping PlainDerived  roles=[ops] -> derived")),
                        new Shape(
                                "an interface method implemented by an inherited superclass method",
                                new ViaSuperclass(),
                                "ViaSuperclass",
                                List.of(readApi(), src("Implementor", """
                                        public class Implementor {
                                            public String read(String id) {
                                                return "inherited " + id;
                                            }
                                        }
                                        """), src("ViaSuperclass", """
                                        @Path("/via-super")
                                        public class ViaSuperclass extends Implementor implements ReadApi<String> {}
                                        """)),
                                List.of(
                                        "GET /via-super/{id} Implementor id|PATH|java.lang.String roles=[reader] -> inherited 7")),
                        new Shape(
                                "an unrelated overload is not a route and takes nothing from the interface",
                                new Overloads(),
                                "Overloads",
                                List.of(readApi(), src("Overloads", """
                                        @Path("/overloads")
                                        public class Overloads implements ReadApi<String> {
                                            @Override
                                            public String read(String id) {
                                                return "read " + id;
                                            }

                                            public String read(String id, int times) {
                                                return "overload " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /overloads/{id} Overloads id|PATH|java.lang.String roles=[reader] -> read 7")))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    @DisplayName("both engines produce exactly the expected routes")
    void bothEnginesProduceTheExpectedRoutes(Shape shape) throws Throwable {
        List<ResourceMethodMeta> reflective = new JaxRsRouteRegistrar().scanResource(shape.reflective());

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), shape.sources().toArray(JavaFileObject[]::new));
        result.assertSuccess();
        String resourceFqn = PKG + "." + shape.generatedResource();
        Object generatedInstance = result.generatedClassLoader()
                .loadClass(resourceFqn)
                .getDeclaredConstructor()
                .newInstance();
        List<ResourceMethodMeta> generated = callDescribe(result, generatedInstance, resourceFqn + "_JaxRsDescriptor");

        assertEquals(shape.expected(), render(reflective), "reflective routes");
        assertEquals(shape.expected(), render(generated), "generated routes");
    }

    // --- Normalization ---

    private static List<String> render(List<ResourceMethodMeta> metas) throws Throwable {
        List<String> routes = new ArrayList<>();
        for (ResourceMethodMeta meta : metas) {
            String params = String.join(
                    ",",
                    meta.params().stream()
                            .map(p ->
                                    p.name() + "|" + p.source() + "|" + p.type().getName())
                            .toList());
            Object[] args =
                    Collections.nCopies(meta.params().size(), (Object) "7").toArray();
            Object invoked = meta.executionPlan() != null
                    ? meta.executionPlan().invoke(meta.resourceInstance(), args)
                    : meta.method().invoke(meta.resourceInstance(), args);
            assertNotNull(invoked, "the route must be invocable: " + meta);
            String security = meta.securityPolicy() instanceof SecurityPolicy.Constrained constrained
                    ? "roles=" + constrained.requiredRoles()
                    : meta.securityPolicy().getClass().getSimpleName();
            routes.add(meta.httpMethod() + " " + meta.path() + " "
                    + meta.method().getDeclaringClass().getSimpleName() + " " + params + " " + security + " -> "
                    + invoked);
        }
        routes.sort(Comparator.naturalOrder());
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
