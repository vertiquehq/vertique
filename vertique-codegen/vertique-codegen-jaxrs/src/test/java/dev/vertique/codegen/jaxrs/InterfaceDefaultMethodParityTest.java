// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.PermitAll;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity proof for interface {@code default} resource methods (issue #630): the generated
 * {@code _JaxRsDescriptor} and execution plans describe and dispatch exactly the routes the
 * reflective {@code ResourceScanner} discovers.
 *
 * <p>Each shape is represented twice, following {@link GeneratedArrayParamParityTest}: as a plain
 * compiled nested fixture scanned through {@link JaxRsRouteRegistrar#scanResource(Object)} (no
 * annotation processor runs on this module's test sources, so that side is reflective), and as
 * source compiled through {@link JaxRsPipelineProcessor}, whose generated {@code describe()} is
 * called directly. Both sides must yield the same normalized routes and the same invocation
 * result, and each case also pins its expected route count and backing type so that two empty
 * results cannot agree vacuously.
 */
class InterfaceDefaultMethodParityTest {

    private static final String PKG = "dev.vertique.test.idm";

    // --- Reflective fixtures (plain compiled; mirror the generated sources below) ---

    interface Crud {
        @DELETE
        @Path("/{id}")
        @Operation(operationId = "deleteById")
        default String delete(@PathParam("id") String id) {
            return "deleted " + id;
        }

        default String describe() {
            return "crud";
        }
    }

    @Path("/users")
    static class UserResource implements Crud {
        @GET
        public String list() {
            return "[]";
        }
    }

    @Path("/accounts")
    static class AccountResource implements Crud {
        @Override
        public String delete(String id) {
            return "account " + id;
        }
    }

    static class BaseResource implements Crud {}

    @Path("/derived")
    static class DerivedResource extends BaseResource {}

    interface SoftCrud extends Crud {
        @Override
        default String delete(String id) {
            return "soft " + id;
        }
    }

    @Path("/documents")
    static class DocumentResource implements SoftCrud {}

    interface GenericCrud<I> {
        @DELETE
        @Path("/{id}")
        default String remove(@PathParam("id") I id) {
            return "generic " + id;
        }
    }

    @Path("/orders")
    static class OrderResource implements GenericCrud<String> {
        @Override
        public String remove(String id) {
            return "order " + id;
        }
    }

    interface StringCrud extends GenericCrud<String> {
        @Override
        default String remove(String id) {
            return "string " + id;
        }
    }

    @Path("/invoices")
    static class InvoiceResource implements StringCrud {}

    interface VaultApi {
        @DELETE
        @Path("/{id}")
        @RolesAllowed("admin")
        default String purge(@PathParam("id") String id) {
            return "purged " + id;
        }

        @GET
        @Path("/{id}")
        default String read(@PathParam("id") String id) {
            return "read " + id;
        }
    }

    @Path("/vault")
    @RolesAllowed("auditor")
    static class VaultResource implements VaultApi {}

    interface ConflictingApi {
        @GET
        @Path("/{id}")
        @PermitAll
        @RolesAllowed("admin")
        default String peek(@PathParam("id") String id) {
            return id;
        }
    }

    @Path("/conflict")
    static class ConflictResource implements ConflictingApi {}

    // --- Generated-side sources ---

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import io.swagger.v3.oas.annotations.Operation;
                import jakarta.annotation.security.PermitAll;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.DELETE;
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
                    default String delete(@PathParam("id") String id) {
                        return "deleted " + id;
                    }

                    default String describe() {
                        return "crud";
                    }
                }
                """);
    }

    private static JavaFileObject genericCrudSource() {
        return src("GenericCrud", """
                public interface GenericCrud<I> {
                    @DELETE
                    @Path("/{id}")
                    default String remove(@PathParam("id") I id) {
                        return "generic " + id;
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
     * @param expectedRoutes    route count both sides must report
     * @param expectedBacking   simple name of the type declaring each route's method, in route order
     */
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
                                "non-overridden default next to a class method",
                                new UserResource(),
                                "UserResource",
                                List.of(crudSource(), src("UserResource", """
                                @Path("/users")
                                public class UserResource implements Crud {
                                    @GET
                                    public String list() {
                                        return "[]";
                                    }
                                }
                                """)),
                                2,
                                List.of("Crud", "UserResource")),
                        new ParityCase(
                                "class override wins",
                                new AccountResource(),
                                "AccountResource",
                                List.of(crudSource(), src("AccountResource", """
                                @Path("/accounts")
                                public class AccountResource implements Crud {
                                    @Override
                                    public String delete(String id) {
                                        return "account " + id;
                                    }
                                }
                                """)),
                                1,
                                List.of("AccountResource")),
                        new ParityCase(
                                "default through a superclass's interface",
                                new DerivedResource(),
                                "DerivedResource",
                                List.of(crudSource(), src("BaseResource", """
                                        public class BaseResource implements Crud {}
                                        """), src("DerivedResource", """
                                        @Path("/derived")
                                        public class DerivedResource extends BaseResource {}
                                        """)),
                                1,
                                List.of("Crud")),
                        new ParityCase(
                                "most specific interface default wins",
                                new DocumentResource(),
                                "DocumentResource",
                                List.of(crudSource(), src("SoftCrud", """
                                        public interface SoftCrud extends Crud {
                                            @Override
                                            default String delete(String id) {
                                                return "soft " + id;
                                            }
                                        }
                                        """), src("DocumentResource", """
                                        @Path("/documents")
                                        public class DocumentResource implements SoftCrud {}
                                        """)),
                                1,
                                List.of("SoftCrud")),
                        new ParityCase(
                                "generic default overridden by a class adds no default route",
                                new OrderResource(),
                                "OrderResource",
                                List.of(genericCrudSource(), src("OrderResource", """
                                @Path("/orders")
                                public class OrderResource implements GenericCrud<String> {
                                    @Override
                                    public String remove(String id) {
                                        return "order " + id;
                                    }
                                }
                                """)),
                                0,
                                List.of()),
                        new ParityCase(
                                "generic default overridden by a sub-interface default adds no default route",
                                new InvoiceResource(),
                                "InvoiceResource",
                                List.of(genericCrudSource(), src("StringCrud", """
                                        public interface StringCrud extends GenericCrud<String> {
                                            @Override
                                            default String remove(String id) {
                                                return "string " + id;
                                            }
                                        }
                                        """), src("InvoiceResource", """
                                        @Path("/invoices")
                                        public class InvoiceResource implements StringCrud {}
                                        """)),
                                0,
                                List.of()),
                        new ParityCase(
                                "method and resource class-level security on defaults",
                                new VaultResource(),
                                "VaultResource",
                                List.of(src("VaultApi", """
                                        public interface VaultApi {
                                            @DELETE
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            default String purge(@PathParam("id") String id) {
                                                return "purged " + id;
                                            }

                                            @GET
                                            @Path("/{id}")
                                            default String read(@PathParam("id") String id) {
                                                return "read " + id;
                                            }
                                        }
                                        """), src("VaultResource", """
                                        @Path("/vault")
                                        @RolesAllowed("auditor")
                                        public class VaultResource implements VaultApi {}
                                        """)),
                                2,
                                List.of("VaultApi", "VaultApi")))
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

    @Test
    @DisplayName("conflicting security on a default: runtime rejects and the processor reports an error")
    void conflictingSecurityOnDefault_rejectedByBoth() {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(new ConflictResource()));

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), src("ConflictingApi", """
                        public interface ConflictingApi {
                            @GET
                            @Path("/{id}")
                            @PermitAll
                            @RolesAllowed("admin")
                            default String peek(@PathParam("id") String id) {
                                return id;
                            }
                        }
                        """), src("ConflictResource", """
                        @Path("/conflict")
                        public class ConflictResource implements ConflictingApi {}
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    // --- Normalization ---

    /**
     * Engine-independent view of one route, including the result of invoking it with {@code "7"}
     * for every parameter.
     */
    record Route(
            String httpMethod,
            String path,
            String operationId,
            String backingType,
            String methodName,
            List<String> params,
            String security,
            Object invocationResult) {}

    private static List<Route> routes(List<ResourceMethodMeta> metas, boolean generated) throws Throwable {
        List<Route> routes = new ArrayList<>();
        for (ResourceMethodMeta meta : metas) {
            List<String> params = meta.params().stream()
                    .map(p -> p.name() + "|" + p.source() + "|" + p.type().getName())
                    .toList();
            Object[] args =
                    Collections.nCopies(meta.params().size(), (Object) "7").toArray();
            Object invocationResult;
            if (generated) {
                assertNotNull(meta.executionPlan(), "generated route must carry an execution plan: " + meta);
                invocationResult = meta.executionPlan().invoke(meta.resourceInstance(), args);
            } else {
                invocationResult = meta.method().invoke(meta.resourceInstance(), args);
            }
            routes.add(new Route(
                    meta.httpMethod(),
                    meta.path(),
                    meta.operationId(),
                    meta.method().getDeclaringClass().getSimpleName(),
                    meta.method().getName(),
                    params,
                    meta.securityPolicy().toString(),
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
