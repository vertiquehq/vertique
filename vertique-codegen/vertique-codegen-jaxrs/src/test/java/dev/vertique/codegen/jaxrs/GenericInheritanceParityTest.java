// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.codegen.jaxrs.gip.chain.ChainMiddle;
import dev.vertique.codegen.jaxrs.gip.chain.RelayLeaf;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.RequiresActionResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.RequiresAction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    interface BoundedFutureApi<T> {
        @GET
        default io.vertx.core.Future<? extends T> bounded() {
            return io.vertx.core.Future.succeededFuture();
        }
    }

    @Path("/bounded-future")
    static class BoundedFutureLeaf implements BoundedFutureApi<String> {}

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

    @Path("/inherited")
    static class InheritedGeneric extends GenericBase<String> {}

    interface DefaultApi<T> {
        @GET
        @Path("/{id}")
        default String find(@PathParam("id") T id) {
            return "default " + id;
        }
    }

    interface DefaultMiddle<T> extends DefaultApi<T> {}

    @Path("/default-chain")
    static class DefaultLeaf implements DefaultMiddle<String> {}

    static class BaseMiddle<T> extends GenericBase<T> {}

    @Path("/base-chain")
    static class BaseLeaf extends BaseMiddle<String> {}

    interface BodyApi<T> {
        @jakarta.ws.rs.POST
        default io.vertx.core.Future<T> submit(List<List<T>> body) {
            return io.vertx.core.Future.succeededFuture(body.getFirst().getFirst());
        }
    }

    public static class Payload {
        public String value;
    }

    @Path("/body-chain")
    static class BodyLeaf implements BodyApi<Payload> {}

    interface NestedReturnApi<T> {
        @GET
        default io.vertx.core.Future<List<List<T>>> nested() {
            return io.vertx.core.Future.succeededFuture(List.of());
        }
    }

    @Path("/nested-return")
    static class NestedReturnLeaf implements NestedReturnApi<Payload> {}

    interface ScalarApi<T> {
        @jakarta.ws.rs.POST
        default String values(
                @PathParam("p") T p,
                @QueryParam("q") T q,
                @jakarta.ws.rs.HeaderParam("h") T h,
                @jakarta.ws.rs.CookieParam("c") T c,
                @jakarta.ws.rs.FormParam("f") T f) {
            return "ok";
        }
    }

    @Path("/scalars/{p}")
    static class ScalarLeaf implements ScalarApi<Integer> {}

    public static class InputBean {
        @QueryParam("q")
        public Integer q;
    }

    interface BeanApi<T> {
        @jakarta.ws.rs.POST
        default String bean(@jakarta.ws.rs.BeanParam T value) {
            return "ok";
        }
    }

    @Path("/bean")
    static class BeanLeaf implements BeanApi<InputBean> {}

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

    interface GenericMethodApi<T> {
        @DELETE
        @Path("/{id}")
        @RolesAllowed("ops")
        <X> String purge(@PathParam("id") T id);
    }

    @Path("/erased")
    static class ErasedOverride implements GenericMethodApi<String> {
        @Override
        @DELETE
        public String purge(String id) {
            return "erased " + id;
        }
    }

    static class VerbBase {
        @jakarta.ws.rs.POST
        @Path("/v")
        public String m() {
            return "base";
        }
    }

    interface VerbApi {
        @GET
        @Path("/v")
        String m();
    }

    @Path("/verbs")
    static class VerbConflict extends VerbBase implements VerbApi {
        @Override
        public String m() {
            return "verbs";
        }
    }

    static class OverloadBase<T> {
        @GET
        @Path("/{id}")
        public String get(@PathParam("id") T id) {
            return "a " + id;
        }

        @GET
        @Path("/{id}/times")
        public String get(@PathParam("id") T id, @jakarta.ws.rs.QueryParam("times") String times) {
            return "b " + id;
        }
    }

    @Path("/overload-base")
    static class OverloadDerived extends OverloadBase<String> {
        @Override
        public String get(String id) {
            return "derived " + id;
        }
    }

    static class PurgeBase<T> {
        @GET
        @Path("/{id}")
        @RolesAllowed("admin")
        public String purge(@PathParam("id") T id) {
            return "base " + id;
        }
    }

    /** A same-erasure overload with another type argument: it overrides nothing. */
    @Path("/purge-overload")
    static class PurgeOverload extends PurgeBase<List<String>> {
        public String purge(List<Integer> ids) {
            return "overload " + ids;
        }
    }

    /** A private helper with the same erasure: it overrides nothing and is not a route. */
    @Path("/purge-helper")
    static class PurgeHelper extends PurgeBase<List<String>> {
        @SuppressWarnings("unused")
        private String purge(List<Integer> ids) {
            return "helper " + ids;
        }
    }

    static class ProtectedBase<T> {
        @GET
        @Path("/{id}")
        @RolesAllowed("admin")
        protected String find(@PathParam("id") T id) {
            return "base " + id;
        }
    }

    @Path("/prot")
    static class ProtectedDerived extends ProtectedBase<String> {
        @Override
        protected String find(String id) {
            return "prot " + id;
        }
    }

    /** A resource that only inherits a protected secured route from its generic superclass. */
    @Path("/prot-inherited")
    static class ProtectedInherited extends ProtectedBase<String> {}

    interface AttributeApi<T> {
        @GET
        @Path("/{id}")
        @Produces("text/plain")
        @Operation(operationId = "findAttribute")
        String find(@PathParam("id") T id, @QueryParam("n") @DefaultValue("5") String n);
    }

    @Path("/attr")
    static class AttributeResource implements AttributeApi<String> {
        @Override
        public String find(String id, String n) {
            return "attr " + id + n;
        }
    }

    static class PrecedenceBase {
        @GET
        @Path("/from-super")
        public String m() {
            return "m";
        }
    }

    interface PrecedenceApi {
        @GET
        @Path("/from-interface")
        String m();
    }

    @Path("/prec")
    static class PrecedenceResource extends PrecedenceBase implements PrecedenceApi {
        @Override
        public String m() {
            return "prec";
        }
    }

    static class SourceBase {
        @GET
        @Path("/{id}")
        public String get(@PathParam("id") String id) {
            return "src " + id;
        }
    }

    interface SourceApi {
        @GET
        @Path("/{id}")
        String get(@QueryParam("id") String id);
    }

    @Path("/src")
    static class SourceResource extends SourceBase implements SourceApi {
        @Override
        public String get(String id) {
            return "src " + id;
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

    /** Overrides {@code ChainMiddle.find}, which overrides the secured package-private route. */
    @Path("/chain")
    static class ChainLeaf extends ChainMiddle {
        @Override
        public String find(String id) {
            return "leaf " + id;
        }
    }

    static class PrivateKeyBase {
        @SuppressWarnings("unused")
        private String x(Object value) {
            return "private " + value;
        }
    }

    interface PrivateKeyApi<I> {
        @GET
        @Path("/{id}")
        String x(@PathParam("id") I id);
    }

    /** A private base method with the erased signature of an interface route overrides nothing. */
    @Path("/priv-key")
    static class PrivateKeyResource extends PrivateKeyBase implements PrivateKeyApi<String> {
        @Override
        public String x(String id) {
            return "resource " + id;
        }
    }

    interface RoleApi<I> {
        @GET
        @Path("/{id}")
        @RolesAllowed("admin")
        String read(@PathParam("id") I id);
    }

    @Path("/roles")
    static class RoleOverride implements RoleApi<String> {
        @Override
        @RolesAllowed("user")
        public String read(String id) {
            return "roles " + id;
        }
    }

    interface ActionApi<I> {
        @DELETE
        @Path("/{id}")
        @RequiresAction("shop.order.delete")
        String remove(@PathParam("id") I id);
    }

    @Path("/actions")
    static class ActionOverride implements ActionApi<String> {
        @Override
        @RequiresAction("shop.order.read")
        public String remove(String id) {
            return "override " + id;
        }
    }

    @Path("/actions-inherited")
    static class ActionInherited implements ActionApi<String> {
        @Override
        public String remove(String id) {
            return "inherited " + id;
        }
    }

    // --- Generated-side sources ---

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import io.swagger.v3.oas.annotations.Operation;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.DELETE;
                import jakarta.ws.rs.DefaultValue;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.Produces;
                import jakarta.ws.rs.QueryParam;

                """ + body);
    }

    private static JavaFileObject srcIn(String pkg, String simpleName, String body) {
        return SourceFiles.inline(pkg + "." + simpleName, "package " + pkg + ";\n\n" + """
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                """ + body);
    }

    private static String fqn(String generatedResource) {
        return generatedResource.contains(".") ? generatedResource : PKG + "." + generatedResource;
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
                                "an inherited default method resolves through multiple interfaces",
                                new DefaultLeaf(),
                                "DefaultLeaf",
                                List.of(src("DefaultApi", """
                                        public interface DefaultApi<T> {
                                            @GET @Path("/{id}")
                                            default String find(@PathParam("id") T id) { return "default " + id; }
                                        }
                                        """), src("DefaultMiddle", """
                                        public interface DefaultMiddle<T> extends DefaultApi<T> {}
                                        """), src("DefaultLeaf", """
                                        @Path("/default-chain")
                                        public class DefaultLeaf implements DefaultMiddle<String> {}
                                        """)),
                                List.of(
                                        "GET /default-chain/{id} DefaultApi id|PATH|java.lang.String None -> default 7")),
                        new Shape(
                                "an inherited superclass method resolves through multiple classes",
                                new BaseLeaf(),
                                "BaseLeaf",
                                List.of(genericBase(), src("BaseMiddle", """
                                        public class BaseMiddle<T> extends GenericBase<T> {}
                                        """), src("BaseLeaf", """
                                        @Path("/base-chain")
                                        public class BaseLeaf extends BaseMiddle<String> {}
                                        """)),
                                List.of(
                                        "GET /base-chain/{id} GenericBase id|PATH|java.lang.String roles=[editor] -> base 7")),
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
                                "an inherited generic superclass method binds its parameter",
                                new InheritedGeneric(),
                                "InheritedGeneric",
                                List.of(genericBase(), src("InheritedGeneric", """
                                        @Path("/inherited")
                                        public class InheritedGeneric extends GenericBase<String> {}
                                        """)),
                                List.of(
                                        "GET /inherited/{id} GenericBase id|PATH|java.lang.String roles=[editor] -> base 7")),
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
                                "a non-generic override of a generic method overrides it by erasure",
                                new ErasedOverride(),
                                "ErasedOverride",
                                List.of(src("GenericMethodApi", """
                                        public interface GenericMethodApi<T> {
                                            @DELETE
                                            @Path("/{id}")
                                            @RolesAllowed("ops")
                                            <X> String purge(@PathParam("id") T id);
                                        }
                                        """), src("ErasedOverride", """
                                        @Path("/erased")
                                        public class ErasedOverride implements GenericMethodApi<String> {
                                            @Override
                                            @DELETE
                                            public String purge(String id) {
                                                return "erased " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "DELETE /erased/{id} ErasedOverride id|PATH|java.lang.String roles=[ops] -> erased 7")),
                        new Shape(
                                "a verb declared on a superclass and another on an interface follow the fixed priority",
                                new VerbConflict(),
                                "VerbConflict",
                                List.of(src("VerbBase", """
                                        public class VerbBase {
                                            @jakarta.ws.rs.POST
                                            @Path("/v")
                                            public String m() {
                                                return "base";
                                            }
                                        }
                                        """), src("VerbApi", """
                                        public interface VerbApi {
                                            @GET
                                            @Path("/v")
                                            String m();
                                        }
                                        """), src("VerbConflict", """
                                        @Path("/verbs")
                                        public class VerbConflict extends VerbBase implements VerbApi {
                                            @Override
                                            public String m() {
                                                return "verbs";
                                            }
                                        }
                                        """)),
                                List.of("GET /verbs/v VerbConflict  None -> verbs")),
                        new Shape(
                                "a same-name overload of another arity stays a route of its own",
                                new OverloadDerived(),
                                "OverloadDerived",
                                List.of(src("OverloadBase", """
                                        public class OverloadBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            public String get(@PathParam("id") T id) {
                                                return "a " + id;
                                            }

                                            @GET
                                            @Path("/{id}/times")
                                            public String get(@PathParam("id") T id, @jakarta.ws.rs.QueryParam("times") String times) {
                                                return "b " + id;
                                            }
                                        }
                                        """), src("OverloadDerived", """
                                        @Path("/overload-base")
                                        public class OverloadDerived extends OverloadBase<String> {
                                            @Override
                                            public String get(String id) {
                                                return "derived " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /overload-base/{id} OverloadDerived id|PATH|java.lang.String None -> derived 7",
                                        "GET /overload-base/{id}/times OverloadBase id|PATH|java.lang.String,times|QUERY|java.lang.String None -> b 7")),
                        new Shape(
                                "a same-erasure overload with another type argument displaces nothing",
                                new PurgeOverload(),
                                "PurgeOverload",
                                List.of(src("PurgeBase", """
                                        public class PurgeBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            public String purge(@PathParam("id") T id) {
                                                return "base " + id;
                                            }
                                        }
                                        """), src("PurgeOverload", """
                                        @Path("/purge-overload")
                                        public class PurgeOverload extends PurgeBase<java.util.List<String>> {
                                            public String purge(java.util.List<Integer> ids) {
                                                return "overload " + ids;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /purge-overload/{id} PurgeBase id|PATH|java.util.List roles=[admin] -> base [7]")),
                        new Shape(
                                "a private helper with the same erasure displaces nothing",
                                new PurgeHelper(),
                                "PurgeHelper",
                                List.of(src("PurgeBase", """
                                        public class PurgeBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            public String purge(@PathParam("id") T id) {
                                                return "base " + id;
                                            }
                                        }
                                        """), src("PurgeHelper", """
                                        @Path("/purge-helper")
                                        public class PurgeHelper extends PurgeBase<java.util.List<String>> {
                                            @SuppressWarnings("unused")
                                            private String purge(java.util.List<Integer> ids) {
                                                return "helper " + ids;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /purge-helper/{id} PurgeBase id|PATH|java.util.List roles=[admin] -> base [7]")),
                        new Shape(
                                "a protected generic override inherits the declaration's route and security",
                                new ProtectedDerived(),
                                "ProtectedDerived",
                                List.of(src("ProtectedBase", """
                                        public class ProtectedBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            protected String find(@PathParam("id") T id) {
                                                return "base " + id;
                                            }
                                        }
                                        """), src("ProtectedDerived", """
                                        @Path("/prot")
                                        public class ProtectedDerived extends ProtectedBase<String> {
                                            @Override
                                            protected String find(String id) {
                                                return "prot " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /prot/{id} ProtectedDerived id|PATH|java.lang.String roles=[admin] -> prot 7")),
                        new Shape(
                                "a protected route declared only on a superclass is resolved along the class chain",
                                new ProtectedInherited(),
                                "ProtectedInherited",
                                List.of(src("ProtectedBase", """
                                        public class ProtectedBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            protected String find(@PathParam("id") T id) {
                                                return "base " + id;
                                            }
                                        }
                                        """), src("ProtectedInherited", """
                                        @Path("/prot-inherited")
                                        public class ProtectedInherited extends ProtectedBase<String> {}
                                        """)),
                                List.of(
                                        "GET /prot-inherited/{id} ProtectedBase id|PATH|java.lang.String roles=[admin] -> base 7")),
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
                                        "GET /overloads/{id} Overloads id|PATH|java.lang.String roles=[reader] -> read 7")),
                        new Shape(
                                "a package-private route is overridden from another package through a public override of its package",
                                new ChainLeaf(),
                                "ChainLeaf",
                                List.of(
                                        srcIn(PKG + ".chain", "ChainBase", """
                                        public class ChainBase {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            String find(@PathParam("id") String id) {
                                                return "base " + id;
                                            }
                                        }
                                        """),
                                        srcIn(PKG + ".chain", "ChainMiddle", """
                                        public class ChainMiddle extends ChainBase {
                                            @Override
                                            public String find(String id) {
                                                return "middle " + id;
                                            }
                                        }
                                        """),
                                        src("ChainLeaf", """
                                        @Path("/chain")
                                        public class ChainLeaf extends dev.vertique.test.gip.chain.ChainMiddle {
                                            @Override
                                            public String find(String id) {
                                                return "leaf " + id;
                                            }
                                        }
                                        """)),
                                List.of("GET /chain/{id} ChainLeaf id|PATH|java.lang.String roles=[admin] -> leaf 7")),
                        new Shape(
                                "a package-private route is not overridden in its package across a class of another package",
                                new RelayLeaf(),
                                PKG + ".chain.RelayLeaf",
                                List.of(
                                        srcIn(PKG + ".chain", "RelayBase", """
                                        public class RelayBase<T> {
                                            @GET
                                            @Path("/{id}")
                                            @RolesAllowed("admin")
                                            String get(@PathParam("id") T id) {
                                                return "base " + id;
                                            }
                                        }
                                        """),
                                        srcIn(PKG + ".relay", "Relay", """
                                        public class Relay<T> extends dev.vertique.test.gip.chain.RelayBase<T> {}
                                        """),
                                        srcIn(PKG + ".chain", "RelayLeaf", """
                                        @Path("/relay")
                                        public class RelayLeaf extends dev.vertique.test.gip.relay.Relay<String> {
                                            String get(String id) {
                                                return "relay " + id;
                                            }
                                        }
                                        """)),
                                List.of("GET /relay/{id} RelayBase id|PATH|java.lang.String roles=[admin] -> base 7")),
                        new Shape(
                                "a private method with the erased signature of an interface route overrides nothing",
                                new PrivateKeyResource(),
                                "PrivateKeyResource",
                                List.of(
                                        src("PrivateKeyBase", """
                                        public class PrivateKeyBase {
                                            @SuppressWarnings("unused")
                                            private String x(Object value) {
                                                return "private " + value;
                                            }
                                        }
                                        """),
                                        src("PrivateKeyApi", """
                                        public interface PrivateKeyApi<I> {
                                            @GET
                                            @Path("/{id}")
                                            String x(@PathParam("id") I id);
                                        }
                                        """),
                                        src("PrivateKeyResource", """
                                        @Path("/priv-key")
                                        public class PrivateKeyResource extends PrivateKeyBase implements PrivateKeyApi<String> {
                                            @Override
                                            public String x(String id) {
                                                return "resource " + id;
                                            }
                                        }
                                        """)),
                                List.of(
                                        "GET /priv-key/{id} PrivateKeyResource id|PATH|java.lang.String None -> resource 7")))
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
        String resourceFqn = fqn(shape.generatedResource());
        Object generatedInstance = result.generatedClassLoader()
                .loadClass(resourceFqn)
                .getDeclaredConstructor()
                .newInstance();
        List<ResourceMethodMeta> generated = callDescribe(result, generatedInstance, resourceFqn + "_JaxRsDescriptor");

        assertEquals(shape.expected(), render(reflective), "reflective routes");
        assertEquals(shape.expected(), render(generated), "generated routes");
    }

    // --- Attributes beyond the verb: operation id, media types, defaults, precedence ---

    /** One shape whose expected value is the rendered attribute tuple of its single route. */
    record Attributes(
            String label, Object reflective, String generatedResource, List<JavaFileObject> sources, String expected) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> attributeShapes() {
        return Stream.of(
                        new Attributes(
                                "operation id, produces, parameter names and default value come through a bound type variable",
                                new AttributeResource(),
                                "AttributeResource",
                                List.of(src("AttributeApi", """
                                        public interface AttributeApi<T> {
                                            @GET
                                            @Path("/{id}")
                                            @Produces("text/plain")
                                            @Operation(operationId = "findAttribute")
                                            String find(@PathParam("id") T id, @QueryParam("n") @DefaultValue("5") String n);
                                        }
                                        """), src("AttributeResource", """
                                        @Path("/attr")
                                        public class AttributeResource implements AttributeApi<String> {
                                            @Override
                                            public String find(String id, String n) {
                                                return "attr " + id + n;
                                            }
                                        }
                                        """)),
                                "GET /attr/{id} op=findAttribute produces=[text/plain] params=[id|PATH|null, n|QUERY|5]"),
                        new Attributes(
                                "a path on a superclass declaration wins over one on an interface",
                                new PrecedenceResource(),
                                "PrecedenceResource",
                                List.of(
                                        src("PrecedenceBase", """
                                        public class PrecedenceBase {
                                            @GET
                                            @Path("/from-super")
                                            public String m() {
                                                return "m";
                                            }
                                        }
                                        """),
                                        src("PrecedenceApi", """
                                        public interface PrecedenceApi {
                                            @GET
                                            @Path("/from-interface")
                                            String m();
                                        }
                                        """),
                                        src("PrecedenceResource", """
                                        @Path("/prec")
                                        public class PrecedenceResource extends PrecedenceBase implements PrecedenceApi {
                                            @Override
                                            public String m() {
                                                return "prec";
                                            }
                                        }
                                        """)),
                                "GET /prec/from-super op=m produces=[] params=[]"),
                        new Attributes(
                                "the parameter source follows the runtime priority across declarations",
                                new SourceResource(),
                                "SourceResource",
                                List.of(src("SourceBase", """
                                        public class SourceBase {
                                            @GET
                                            @Path("/{id}")
                                            public String get(@PathParam("id") String id) {
                                                return "src " + id;
                                            }
                                        }
                                        """), src("SourceApi", """
                                        public interface SourceApi {
                                            @GET
                                            @Path("/{id}")
                                            String get(@QueryParam("id") String id);
                                        }
                                        """), src("SourceResource", """
                                        @Path("/src")
                                        public class SourceResource extends SourceBase implements SourceApi {
                                            @Override
                                            public String get(String id) {
                                                return "src " + id;
                                            }
                                        }
                                        """)),
                                "GET /src/{id} op=get produces=[] params=[id|PATH|null]"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeShapes")
    @DisplayName("both engines resolve the same attributes from the overridden declarations")
    void bothEnginesResolveTheSameAttributes(Attributes shape) throws Throwable {
        List<ResourceMethodMeta> reflective = new JaxRsRouteRegistrar().scanResource(shape.reflective());

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), shape.sources().toArray(JavaFileObject[]::new));
        result.assertSuccess();
        String resourceFqn = fqn(shape.generatedResource());
        Object generatedInstance = result.generatedClassLoader()
                .loadClass(resourceFqn)
                .getDeclaredConstructor()
                .newInstance();
        List<ResourceMethodMeta> generated = callDescribe(result, generatedInstance, resourceFqn + "_JaxRsDescriptor");

        assertEquals(List.of(shape.expected()), attributes(reflective), "reflective attributes");
        assertEquals(List.of(shape.expected()), attributes(generated), "generated attributes");
    }

    private static List<String> attributes(List<ResourceMethodMeta> metas) {
        return metas.stream()
                .map(m -> m.httpMethod() + " " + m.path() + " op=" + m.operationId() + " produces="
                        + m.mediaTypes().produces() + " params="
                        + m.params().stream()
                                .map(p -> p.name() + "|" + p.source() + "|" + p.defaultValue())
                                .toList())
                .sorted()
                .toList();
    }

    // --- Security precedence across declarations ---

    @org.junit.jupiter.api.Test
    @DisplayName("differing @RolesAllowed on an override and its generic declaration is rejected by both engines")
    void differingRolesAreRejectedByBothEngines() {
        assertThrows(SecurityPolicyViolationException.class, () -> new JaxRsRouteRegistrar()
                .scanResource(new RoleOverride()));

        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), src("RoleApi", """
                        public interface RoleApi<I> {
                            @GET
                            @Path("/{id}")
                            @RolesAllowed("admin")
                            String read(@PathParam("id") I id);
                        }
                        """), src("RoleOverride", """
                        @Path("/roles")
                        public class RoleOverride implements RoleApi<String> {
                            @Override
                            @RolesAllowed("user")
                            public String read(String id) {
                                return "roles " + id;
                            }
                        }
                        """));
        result.assertFailed();
        result.assertErrorMessage("Conflicting security annotations");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the nearest @RequiresAction wins on both engines, and an override without one inherits it")
    void nearestRequiresActionWinsOnBothEngines() throws Throwable {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                src("ActionApi", """
                        public interface ActionApi<I> {
                            @DELETE
                            @Path("/{id}")
                            @dev.vertique.security.authz.RequiresAction("shop.order.delete")
                            String remove(@PathParam("id") I id);
                        }
                        """),
                src("ActionOverride", """
                        @Path("/actions")
                        public class ActionOverride implements ActionApi<String> {
                            @Override
                            @dev.vertique.security.authz.RequiresAction("shop.order.read")
                            public String remove(String id) {
                                return "override " + id;
                            }
                        }
                        """),
                src("ActionInherited", """
                        @Path("/actions-inherited")
                        public class ActionInherited implements ActionApi<String> {
                            @Override
                            public String remove(String id) {
                                return "inherited " + id;
                            }
                        }
                        """));
        result.assertSuccess();
        RequiresActionResolver actions = new RequiresActionResolver();

        for (String name : List.of("ActionOverride", "ActionInherited")) {
            Object reflectiveResource = name.equals("ActionOverride") ? new ActionOverride() : new ActionInherited();
            List<ResourceMethodMeta> reflective = new JaxRsRouteRegistrar().scanResource(reflectiveResource);
            String resourceFqn = fqn(name);
            Object instance = result.generatedClassLoader()
                    .loadClass(resourceFqn)
                    .getDeclaredConstructor()
                    .newInstance();
            List<ResourceMethodMeta> generated = callDescribe(result, instance, resourceFqn + "_JaxRsDescriptor");
            Optional<ActionRef> expected = Optional.of(
                    ActionRef.parse(name.equals("ActionOverride") ? "shop.order.read" : "shop.order.delete"));

            assertEquals(1, reflective.size(), "reflective routes of " + name);
            assertEquals(1, generated.size(), "generated routes of " + name);
            assertEquals(
                    expected,
                    actions.resolve(
                            reflective.get(0).methodAnnotations(),
                            reflective.get(0).classAnnotations()),
                    "reflective action of " + name);
            assertEquals(
                    expected,
                    actions.resolve(
                            generated.get(0).methodAnnotations(),
                            generated.get(0).classAnnotations()),
                    "generated action of " + name);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Generated and reflective inherited nested bodies decode concrete values")
    void nestedBodyAndFutureReturnMatch() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), src("Payload", """
                        public class Payload { public String value; }
                        """), src("BodyApi", """
                        public interface BodyApi<T> {
                            @jakarta.ws.rs.POST
                            default io.vertx.core.Future<T> submit(java.util.List<java.util.List<T>> body) {
                                return io.vertx.core.Future.succeededFuture(body.getFirst().getFirst());
                            }
                        }
                        """), src("BodyLeaf", """
                        @Path("/body-chain")
                        public class BodyLeaf implements BodyApi<Payload> {}
                        """));
        result.assertSuccess();
        String name = fqn("BodyLeaf");
        Object instance = result.generatedClassLoader()
                .loadClass(name)
                .getDeclaredConstructor()
                .newInstance();
        var generated =
                callDescribe(result, instance, name + "_JaxRsDescriptor").getFirst();
        var reflective = new JaxRsRouteRegistrar().scanResource(new BodyLeaf()).getFirst();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var meta : List.of(reflective, generated)) {
            List<?> decoded = mapper.readValue(
                    "[[{\"value\":\"ok\"}]]",
                    mapper.constructType(meta.params().getFirst().genericType()));
            Object payload = ((List<?>) decoded.getFirst()).getFirst();
            assertEquals(meta.responseBodyType(), payload.getClass());
            assertEquals("ok", payload.getClass().getField("value").get(payload));
            assertEquals(true, meta.returnsFuture());
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("inherited bounded future return metadata agrees")
    void boundedFutureReturnMetadataParity() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), src("BoundedFutureApi", """
                        public interface BoundedFutureApi<T> {
                            @GET
                            default io.vertx.core.Future<? extends T> bounded() {
                                return io.vertx.core.Future.succeededFuture();
                            }
                        }
                        """), src("BoundedFutureLeaf", """
                        @Path("/bounded-future")
                        public class BoundedFutureLeaf implements BoundedFutureApi<String> {}
                        """));
        result.assertSuccess();
        String name = fqn("BoundedFutureLeaf");
        Object instance = result.generatedClassLoader()
                .loadClass(name)
                .getDeclaredConstructor()
                .newInstance();
        var generated =
                callDescribe(result, instance, name + "_JaxRsDescriptor").getFirst();
        var reflective =
                new JaxRsRouteRegistrar().scanResource(new BoundedFutureLeaf()).getFirst();
        for (var meta : List.of(reflective, generated)) {
            assertEquals(String.class, meta.responseBodyType());
            assertEquals(true, meta.returnsFuture());
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("inherited nested future return metadata agrees at raw body type precision")
    void nestedReturnMetadataParity() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                src("Payload", "public class Payload {}"),
                src("NestedReturnApi", """
                        public interface NestedReturnApi<T> {
                            @GET
                            default io.vertx.core.Future<java.util.List<java.util.List<T>>> nested() {
                                return io.vertx.core.Future.succeededFuture(java.util.List.of());
                            }
                        }
                        """),
                src("NestedReturnLeaf", """
                        @Path("/nested-return")
                        public class NestedReturnLeaf implements NestedReturnApi<Payload> {}
                        """));
        result.assertSuccess();
        String name = fqn("NestedReturnLeaf");
        Object instance = result.generatedClassLoader()
                .loadClass(name)
                .getDeclaredConstructor()
                .newInstance();
        var generated =
                callDescribe(result, instance, name + "_JaxRsDescriptor").getFirst();
        var reflective =
                new JaxRsRouteRegistrar().scanResource(new NestedReturnLeaf()).getFirst();
        for (var meta : List.of(reflective, generated)) {
            assertEquals(List.class, meta.responseBodyType());
            assertEquals(true, meta.returnsFuture());
            assertEquals(false, meta.returnsVoid());
        }
    }

    @org.junit.jupiter.api.Test
    void inheritedScalarAndBeanExtractionParity() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                src("ScalarApi", """
                        public interface ScalarApi<T> {
                            @jakarta.ws.rs.POST
                            default String values(@PathParam("p") T p, @QueryParam("q") T q,
                                @jakarta.ws.rs.HeaderParam("h") T h, @jakarta.ws.rs.CookieParam("c") T c,
                                @jakarta.ws.rs.FormParam("f") T f) { return "ok"; }
                        }
                        """),
                src("ScalarLeaf", """
                        @Path("/scalars/{p}") public class ScalarLeaf implements ScalarApi<Integer> {}
                        """),
                src("InputBean", """
                        public class InputBean { @QueryParam("q") public Integer q; }
                        """),
                src("BeanApi", """
                        public interface BeanApi<T> {
                            @jakarta.ws.rs.POST default String bean(@jakarta.ws.rs.BeanParam T value) { return "ok"; }
                        }
                        """),
                src("BeanLeaf", """
                        @Path("/bean") public class BeanLeaf implements BeanApi<InputBean> {}
                        """));
        result.assertSuccess();
        var request = new dev.vertique.rest.jaxrs.request.BoundRequest() {
            public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> pathParameters() {
                return java.util.Map.of("p", dev.vertique.rest.core.request.RequestValue.of("11"));
            }

            public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> query() {
                return java.util.Map.of("q", dev.vertique.rest.core.request.RequestValue.of("12"));
            }

            public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> headers() {
                return java.util.Map.of("h", dev.vertique.rest.core.request.RequestValue.of("13"));
            }

            public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> cookies() {
                return java.util.Map.of("c", dev.vertique.rest.core.request.RequestValue.of("14"));
            }

            public dev.vertique.rest.core.request.RequestValue body() {
                return dev.vertique.rest.core.request.RequestValue.of(null);
            }

            public io.vertx.core.http.HttpServerRequest raw() {
                return null;
            }
        };
        var http = (io.vertx.core.http.HttpServerRequest) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vertx.core.http.HttpServerRequest.class},
                (proxy, method, args) -> method.getName().equals("getFormAttribute") ? "15" : null);
        var ctx = (io.vertx.ext.web.RoutingContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vertx.ext.web.RoutingContext.class},
                (proxy, method, args) -> method.getName().equals("request") ? http : null);
        for (String leaf : List.of("ScalarLeaf", "BeanLeaf")) {
            Object local = leaf.equals("ScalarLeaf") ? new ScalarLeaf() : new BeanLeaf();
            var reflective = new JaxRsRouteRegistrar().scanResource(local).getFirst();
            String name = fqn(leaf);
            Object instance = result.generatedClassLoader()
                    .loadClass(name)
                    .getDeclaredConstructor()
                    .newInstance();
            var generated =
                    callDescribe(result, instance, name + "_JaxRsDescriptor").getFirst();
            for (var meta : List.of(reflective, generated)) {
                Object[] args = extract(meta, ctx, request, meta == generated);
                if (leaf.equals("ScalarLeaf")) {
                    org.junit.jupiter.api.Assertions.assertArrayEquals(new Object[] {11, 12, 13, 14, 15}, args);
                } else {
                    assertEquals(meta.params().getFirst().type(), args[0].getClass());
                    assertEquals(12, args[0].getClass().getField("q").get(args[0]));
                }
            }
        }
    }

    private static Object[] extract(
            ResourceMethodMeta meta,
            io.vertx.ext.web.RoutingContext ctx,
            dev.vertique.rest.jaxrs.request.BoundRequest request,
            boolean generated)
            throws Exception {
        Class<?> extractorType = Class.forName("dev.vertique.rest.jaxrs.ParameterExtractor");
        var constructor = extractorType.getDeclaredConstructor(
                ResourceMethodMeta.class, List.class, dev.vertique.rest.core.context.RestContextResolution.class);
        constructor.setAccessible(true);
        Object extractor = constructor.newInstance(
                meta, List.of(), new dev.vertique.rest.core.context.RestContextResolution(java.util.Set.of()));
        if (!generated) {
            var method = extractorType.getDeclaredMethod(
                    "extractArguments",
                    io.vertx.ext.web.RoutingContext.class,
                    dev.vertique.rest.jaxrs.request.BoundRequest.class);
            method.setAccessible(true);
            return (Object[]) method.invoke(extractor, ctx, request);
        }
        Class<?> supportType = Class.forName("dev.vertique.rest.jaxrs.ParameterExtractorBackedSupport");
        var supportConstructor = supportType.getDeclaredConstructor(extractorType);
        supportConstructor.setAccessible(true);
        var support = (dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport) supportConstructor.newInstance(extractor);
        return meta.executionPlan().extractArguments(ctx, request, support);
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
            Object[] args = meta.params().stream()
                    .map(p -> p.type() == List.class ? List.of("7") : "7")
                    .toArray();
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
