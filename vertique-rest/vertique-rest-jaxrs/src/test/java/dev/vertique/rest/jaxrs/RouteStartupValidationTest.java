// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the fail-fast startup/build validation introduced in PRD-REST-018 slice 1.3: registering a
 * resource that declares a conversion-applicable parameter (path/query/header/cookie/form) whose type
 * no converter or provider can satisfy MUST fail at router-build time, while a resource whose only
 * "exotic" param is a built-in (or provider-backed) must build cleanly.
 *
 * <p><b>RED rationale (behavior-RED, compiles against current code):</b> today {@code ResourceScanner}
 * accepts an arbitrary parameter type without checking that a converter exists — a
 * {@code @QueryParam CustomType} scans and builds a router fine, only failing (opaquely) at invoke
 * time. So {@link #unconvertibleTypeFailsRouterBuild()} asserts an exception that is NOT thrown today,
 * making it RED until the {@code resolver.canResolve(ctx)} build-time check is added. The
 * built-in/body-skip cases assert the current no-throw behavior is preserved.
 *
 * <p>This class mirrors {@link AnnotationDrivenRoutingIT}'s {@code unknownStrategyFailsFast}: the
 * config-error path of {@code createRouter} throws synchronously, so the assertion is a direct
 * {@code assertThrows} around {@code mount.createRouter(vertx)}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RouteStartupValidationTest {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    /** A custom domain type with no built-in converter and no app binding/provider. */
    public static final class UnconvertibleType {
        private UnconvertibleType() {}
    }

    /** A custom domain type resolvable ONLY by the strict {@link StrictMyTypeProvider}. */
    public static final class MyType {
        private final String value;

        MyType(String value) {
            this.value = value;
        }
    }

    /**
     * A {@code genericType}-strict JAX-RS provider: it returns a converter <em>only</em> when the
     * {@code genericType} is exactly {@link MyType}. A collection-shaped context (whose
     * {@code genericType} is {@code List<MyType>}) makes it return {@code null}, which is the latent
     * bug under test — startup must probe the TRUE element shape ({@code genericType == MyType}), not
     * the descriptor's collection {@code genericType}.
     */
    public static final class StrictMyTypeProvider implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == MyType.class && genericType == MyType.class) {
                return (ParamConverter<T>) new ParamConverter<MyType>() {
                    @Override
                    public MyType fromString(String value) {
                        return new MyType(value);
                    }

                    @Override
                    public String toString(MyType value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    /** Resource declaring a {@code @QueryParam List<MyType>} resolvable only via the strict provider. */
    @Path("/strict")
    public static class StrictCollectionResource {

        /**
         * Echoes the converted collection-of-{@link MyType} query param, proving each element is
         * converted to a real {@link MyType} (not a raw string) by the strict provider.
         *
         * @param values the repeated {@code MyType} query values
         * @return the element runtime class plus the count
         */
        @GET
        @Path("/values")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictValues")
        public String values(@QueryParam("v") List<MyType> values) {
            String elementType =
                    values.isEmpty() ? "empty" : values.get(0).getClass().getSimpleName();
            return elementType + ",size=" + values.size();
        }
    }

    /** Resource declaring a conversion-applicable param of a type that no converter can satisfy. */
    @Path("/unconvertible")
    public static class UnconvertibleResource {

        /**
         * Declares a {@code @QueryParam} of an unconvertible custom type, which startup validation must
         * reject.
         *
         * @param x the unconvertible query param
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unconvertible")
        public String get(@QueryParam("x") UnconvertibleType x) {
            return "unreachable";
        }
    }

    /** Resource whose only non-String param is a built-in convertible type ({@link UUID}). */
    @Path("/builtins")
    public static class BuiltinResource {

        /**
         * Declares a {@code @PathParam UUID} (a built-in convertible type), which must pass validation.
         *
         * @param id the UUID path param
         * @return the id echoed back
         */
        @GET
        @Path("/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "builtinUuid")
        public String get(@PathParam("id") UUID id) {
            return "id=" + id;
        }
    }

    /** Simple JSON body bean for the body-skip case. */
    public static class Payload {
        public String name;
    }

    /** Resource with a convertible path param plus an entity body param (which must be skipped). */
    @Path("/body")
    public static class BodyParamResource {

        /**
         * Declares a {@code @PathParam UUID} (validated) plus an entity body param (skipped by
         * validation — body params are not conversion-applicable).
         *
         * @param id      the UUID path param
         * @param payload the request entity body
         * @return the id echoed back
         */
        @jakarta.ws.rs.POST
        @Path("/{id}")
        @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bodyParam")
        public String post(@PathParam("id") UUID id, Payload payload) {
            return "id=" + id;
        }
    }

    /** Native multipart form targets that are materialized without string conversion. */
    @Path("/native-multipart")
    public static class NativeMultipartResource {

        @POST
        @Operation(operationId = "nativeMultipart")
        public String post(
                @FormParam("upload") FileUpload upload,
                @FormParam("uploads") List<FileUpload> uploads,
                @FormParam("part") EntityPart part,
                @FormParam("parts") List<EntityPart> parts) {
            return "unused";
        }
    }

    /** Bean-param form fields whose values are also materialized without string conversion. */
    public static class NativeMultipartBeanParams {
        @FormParam("upload")
        public FileUpload upload;

        @FormParam("part")
        public EntityPart part;
    }

    /** Resource carrying native multipart form targets through the bean-param field path. */
    @Path("/native-multipart-bean")
    public static class NativeMultipartBeanResource {

        @POST
        @Operation(operationId = "nativeMultipartBean")
        public String post(@BeanParam NativeMultipartBeanParams params) {
            return "unused";
        }
    }

    @Test
    @DisplayName("A declared param of a type with no converter fails router build, naming the param and type")
    void unconvertibleTypeFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UnconvertibleResource()));

        // Today this builds cleanly (no startup validation), so RED. The intended fix throws at build
        // time. Asserting Throwable keeps the test resilient to the precise exception type the impl
        // picks (the plan names IllegalStateException) while still pinning the fail-fast outcome.
        Throwable thrown = assertThrows(
                Throwable.class,
                () -> mount.createRouter(vertx),
                "a declared param with no converter must fail fast at router build");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("x") || message.contains(UnconvertibleType.class.getSimpleName()),
                "the failure must name the offending param or its type (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("A resource whose exotic param is a built-in convertible type builds without throwing")
    void builtinConvertibleTypePassesValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new BuiltinResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx), "a built-in convertible param must pass startup validation");
        ctx.completeNow();
    }

    @Test
    @DisplayName("An entity body param is skipped by validation; the convertible path param still validates")
    void bodyParamSkippedInValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new BodyParamResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the body param must be skipped (not conversion-applicable) while the UUID path param validates");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Native multipart form targets skip string-converter validation at top level and in bean fields")
    void nativeMultipartFormTargetsPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create(
                "/*", "openapi.json", Set.of(new NativeMultipartResource(), new NativeMultipartBeanResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "FileUpload and EntityPart form targets are materialized natively and need no string converter");
        ctx.completeNow();
    }

    @Test
    @DisplayName("A List<MyType> resolvable only by a genericType-strict provider passes startup validation")
    void strictProviderCollectionElementPassesValidation(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new StrictMyTypeProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new StrictCollectionResource()));

        // RED today: forDescriptorConvertibleType still passes the collection genericType (List<MyType>),
        // so the strict provider returns null at startup and the route is rejected with
        // UNRESOLVABLE_PARAM_CONVERTER — even though the same element converts at request time.
        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "a List<MyType> element resolvable by a genericType-strict provider must pass startup validation");
        ctx.completeNow();
    }

    /**
     * A {@code @BeanParam} bean whose {@code x} field declares the same unconvertible type as
     * {@link UnconvertibleType}. {@code descriptor.parameters()} never sees this field (it is not a
     * top-level path/query/header/cookie/form parameter — the bean itself is), so only the
     * bean-field-aware validation pass (fix H) can catch it.
     */
    public static class UnconvertibleBeanParams {
        @QueryParam("x")
        public UnconvertibleType x;
    }

    /** Resource declaring a {@code @BeanParam} whose field type no converter can satisfy. */
    @Path("/unconvertible-bean")
    public static class UnconvertibleBeanParamResource {

        /**
         * Declares a {@code @BeanParam} carrying an unconvertible field, which startup validation must
         * reject even though the bean parameter itself is not a top-level bindable parameter.
         *
         * @param params the bean param carrying the unconvertible field
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unconvertibleBean")
        public String get(@BeanParam UnconvertibleBeanParams params) {
            return "unreachable";
        }
    }

    @Test
    @DisplayName("A @BeanParam field of a type with no converter fails router build, naming the field and type")
    void unconvertibleBeanParamFieldFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UnconvertibleBeanParamResource()));

        // RED today: descriptor.parameters() only exposes TOP-LEVEL path/query/header/cookie/form
        // params — a @BeanParam's own fields are excluded (its source is BEAN_PARAM, not a bindable
        // location), so the bean's "x" field currently slips through startup validation and the route
        // mounts cleanly, only failing (opaquely) at first request. The intended fix walks the bean's
        // convertible fields too and rejects this at build time.
        Throwable thrown = assertThrows(
                Throwable.class,
                () -> mount.createRouter(vertx),
                "a @BeanParam field with no converter must fail fast at router build");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("x") || message.contains(UnconvertibleType.class.getSimpleName()),
                "the failure must name the offending bean field or its type (was: " + message + ")");
        ctx.completeNow();
    }

    /** A {@code @BeanParam} whose only field is a scalar {@code @FormParam} of an unconvertible type. */
    public static class UnconvertibleFormBeanParams {
        @FormParam("y")
        public UnconvertibleType y;
    }

    /** Resource declaring a {@code @BeanParam} whose FORM-sourced field type no converter can satisfy. */
    @Path("/unconvertible-form-bean")
    public static class UnconvertibleFormBeanParamResource {

        /**
         * Declares a {@code @BeanParam} carrying an unconvertible {@code @FormParam} field. A scalar
         * form field routes through {@code ParameterExtractor.extractFormParam}'s text-field branch,
         * which calls {@code coerceString} — the same resolver-backed path as path/query/header/cookie
         * — so this must be rejected at startup exactly like the other sources.
         *
         * @param params the bean param carrying the unconvertible form field
         * @return never reached (the router build fails first)
         */
        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unconvertibleFormBean")
        public String post(@BeanParam UnconvertibleFormBeanParams params) {
            return "unreachable";
        }
    }

    @Test
    @DisplayName("A @BeanParam @FormParam field of a type with no converter fails router build")
    void unconvertibleBeanParamFormFieldFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UnconvertibleFormBeanParamResource()));

        Throwable thrown = assertThrows(
                Throwable.class,
                () -> mount.createRouter(vertx),
                "a @BeanParam @FormParam field with no converter must fail fast at router build");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("y") || message.contains(UnconvertibleType.class.getSimpleName()),
                "the failure must name the offending bean field or its type (was: " + message + ")");
        ctx.completeNow();
    }

    /** An enum element type — an enum implements {@link Comparable}, so sorted shapes accept it. */
    public enum Season {
        SPRING,
        SUMMER
    }

    /**
     * Resource declaring a {@code SortedSet} whose element type does not implement {@link Comparable}.
     *
     * <p>{@link MyType} is deliberately the element type: {@link StrictMyTypeProvider} resolves a
     * converter for it, so the parameter passes {@code UNRESOLVABLE_PARAM_CONVERTER} and the rejection
     * can only come from the sorted-shape guard itself.
     */
    @Path("/non-comparable-sorted-set")
    public static class NonComparableSortedSetResource {

        /**
         * Declares a {@code @QueryParam SortedSet<MyType>}, which is materialized as a {@code TreeSet}
         * and would therefore throw {@code ClassCastException} on every request supplying a value.
         *
         * @param values the repeated non-{@link Comparable} query values
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "nonComparableSortedSet")
        public String get(@QueryParam("v") SortedSet<MyType> values) {
            return "unreachable";
        }
    }

    /** Resource declaring every sorted-shape element type that IS {@link Comparable}. */
    @Path("/comparable-sorted-sets")
    public static class ComparableSortedSetResource {

        /**
         * Declares sorted shapes over {@link String}, a boxed numeric, {@link Character},
         * {@link Boolean}, and an enum — every one {@link Comparable}, so all must pass validation.
         *
         * @param strings    a {@code SortedSet<String>}
         * @param integers   a {@code NavigableSet<Integer>}
         * @param characters a {@code SortedSet<Character>}
         * @param booleans   a {@code NavigableSet<Boolean>}
         * @param seasons    a {@code SortedSet} of an enum
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "comparableSortedSets")
        public String get(
                @QueryParam("s") SortedSet<String> strings,
                @QueryParam("i") NavigableSet<Integer> integers,
                @QueryParam("c") SortedSet<Character> characters,
                @QueryParam("b") NavigableSet<Boolean> booleans,
                @QueryParam("e") SortedSet<Season> seasons) {
            return "unreachable";
        }
    }

    /** Resource declaring a non-{@link Comparable} element in UNSORTED shapes, which stay legal. */
    @Path("/unsorted-non-comparable")
    public static class UnsortedNonComparableResource {

        /**
         * Declares {@code Set}/{@code List}/{@code Collection} of a non-{@link Comparable} element
         * type. None is materialized as a {@code TreeSet}, so the sorted-shape guard must not fire.
         *
         * @param set        a {@code Set<MyType>}
         * @param list       a {@code List<MyType>}
         * @param collection a {@code Collection<MyType>}
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unsortedNonComparable")
        public String get(
                @QueryParam("s") Set<MyType> set,
                @QueryParam("l") List<MyType> list,
                @QueryParam("c") Collection<MyType> collection) {
            return "unreachable";
        }
    }

    @Test
    @DisplayName("A SortedSet of a non-Comparable element type fails router build, naming the param and shape")
    void nonComparableSortedSetElementFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new StrictMyTypeProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new NonComparableSortedSetResource()));

        // RED today: nothing inspects the element type against Comparable, so this route mounts and
        // materializeCollection's `new TreeSet<>(elements)` throws ClassCastException per request.
        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "a SortedSet of a non-Comparable element type has no valid materialization and must fail startup");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("NON_COMPARABLE_SORTED_SET_ELEMENT"),
                "the violation must name the sorted-element type (was: " + message + ")");
        assertTrue(
                message.contains("'v'") && message.contains("SortedSet<MyType>"),
                "the diagnostic must name the parameter and its declared shape (was: " + message + ")");
        assertTrue(
                !message.contains("UNRESOLVABLE_PARAM_CONVERTER"),
                "the parameter must be reported once, not also as an unresolvable converter (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Sorted shapes over String/Integer/Character/Boolean/enum elements pass validation")
    void comparableSortedSetElementsPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ComparableSortedSetResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "every Comparable element type must remain accepted in a SortedSet/NavigableSet");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Set/List/Collection of a non-Comparable element type still pass validation")
    void unsortedNonComparableElementsPassValidation(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new StrictMyTypeProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UnsortedNonComparableResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the guard is scoped to TreeSet-materialized shapes; unsorted shapes impose no ordering");
        ctx.completeNow();
    }

    @Test
    @DisplayName("A List<MyType> element actually converts at request time via the strict provider (200)")
    void strictProviderCollectionElementConvertsAtRequestTime(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new StrictMyTypeProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new StrictCollectionResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/strict/values?v=a&v=b")
                            .compose(req -> req.send())
                            .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                            .onComplete(ctx.succeeding(statusAndBody -> {
                                ctx.verify(() -> assertEquals(
                                        "200|MyType,size=2",
                                        statusAndBody,
                                        "each element must convert to a real MyType via the strict provider"));
                                ctx.completeNow();
                            }));
                }));
    }
}
