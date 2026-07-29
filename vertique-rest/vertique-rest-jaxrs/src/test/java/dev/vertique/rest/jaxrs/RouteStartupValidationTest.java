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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
import java.util.List;
import java.util.Set;
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

    // --- Duplicate parameter names at one descriptor-matched location ---

    /**
     * Resource declaring the same <em>query</em> parameter name twice with incompatible multiplicities —
     * one scalar, one collection-shaped. Legal Java that compiles, but request binding resolves a name to
     * a single declared parameter ({@code DefaultBoundRequest.findDescriptor} is first-match), so exactly
     * one of the two is always mis-bound.
     */
    @Path("/duplicate-query-multiplicity")
    public static class DuplicateQueryMultiplicityResource {

        /**
         * Declares {@code @QueryParam("id")} twice: once as a scalar, once as a {@code List}.
         *
         * @param scalar     the scalar declaration of {@code id}
         * @param collection the collection-shaped declaration of the same name
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateQueryMultiplicity")
        public String get(@QueryParam("id") String scalar, @QueryParam("id") List<String> collection) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring the same <em>header</em> name twice with incompatible multiplicities, the two
     * declarations differing only in case. Header (and cookie) descriptor matching is
     * case-<em>insensitive</em> on both halves of binding, so these two collide exactly as an identical
     * pair would.
     */
    @Path("/duplicate-header-multiplicity")
    public static class DuplicateHeaderCasingMultiplicityResource {

        /**
         * Declares {@code @HeaderParam("X-Id")} as a scalar and {@code @HeaderParam("x-id")} as a
         * {@code List}.
         *
         * @param scalar     the scalar declaration, in canonical header casing
         * @param collection the collection-shaped declaration, in lower case
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateHeaderCasingMultiplicity")
        public String get(@HeaderParam("X-Id") String scalar, @HeaderParam("x-id") List<String> collection) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring two <em>query</em> parameters whose names differ only in case. Query (and path)
     * descriptor matching is case-<em>sensitive</em> on both halves of binding, so {@code id} and
     * {@code Id} are two independent names that never collide — this declaration is legal and must keep
     * building.
     */
    @Path("/query-casing-distinct")
    public static class QueryCasingDistinctResource {

        /**
         * Declares {@code @QueryParam("id")} as a scalar and {@code @QueryParam("Id")} as a {@code List}.
         *
         * @param lower the lower-case scalar parameter
         * @param upper the capitalized collection-shaped parameter
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "queryCasingDistinct")
        public String get(@QueryParam("id") String lower, @QueryParam("Id") List<String> upper) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring one <em>scalar</em> name twice with two <em>different declared types</em>
     * ({@link Integer} and {@link UUID}). Multiplicity agrees, so the two declarations share one
     * descriptor — and {@code DefaultBoundRequest.wrapScalar} converts the raw value <em>once</em> with
     * that descriptor's context, so the second parameter receives an {@link Integer} its declared
     * {@link UUID} type cannot accept and every request carrying {@code id} fails in
     * {@code Method.invoke} (an opaque 500).
     */
    @Path("/duplicate-scalar-type")
    public static class DuplicateScalarTypeResource {

        /**
         * Declares {@code @QueryParam("id")} twice as a scalar of two different types.
         *
         * @param first  the first scalar declaration of {@code id}, an {@link Integer}
         * @param second the second scalar declaration of the same name, a {@link UUID}
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateScalarType")
        public String get(@QueryParam("id") Integer first, @QueryParam("id") UUID second) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring one scalar name twice with the <em>same</em> declared type but a
     * <em>different</em> {@code @DefaultValue}. The single descriptor for the name carries one
     * annotation array (fed to the {@code ParamConverterProvider} chain) and the single derived
     * per-name parameter schema carries one default, so one request name cannot honor two different
     * absent-value contracts.
     */
    @Path("/duplicate-scalar-default")
    public static class DuplicateScalarDefaultValueResource {

        /**
         * Declares {@code @QueryParam("id")} twice as a {@code String} with two different defaults.
         *
         * @param first  the declaration defaulting to {@code "1"}
         * @param second the declaration of the same name defaulting to {@code "2"}
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateScalarDefault")
        public String get(
                @QueryParam("id") @DefaultValue("1") String first, @QueryParam("id") @DefaultValue("2") String second) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring one name twice in two different <em>collection</em> shapes over the same
     * element type ({@code List<String>} plus {@code Set<String>}). Multiplicity agrees and the shared
     * descriptor decides nothing else for a collection: the binder wraps every raw value into one
     * {@code JsonArray} of strings, and {@code ParameterExtractor.coerceCollection} converts and
     * materializes per <em>parameter</em> ({@code paramMeta.type()} / {@code paramMeta.componentType()}),
     * so each parameter receives its own declared collection class. The derived per-name parameter
     * schema is identical too ({@code array} of {@code string}), so the pair is redundant but correct
     * and must keep building.
     */
    @Path("/duplicate-collection-shape")
    public static class DuplicateCollectionShapeResource {

        /**
         * Declares {@code @QueryParam("tags")} as a {@code List<String>} and as a {@code Set<String>}.
         *
         * @param tagList the {@code List} declaration of {@code tags}
         * @param tagSet  the {@code Set} declaration of the same name and element type
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateCollectionShape")
        public String get(@QueryParam("tags") List<String> tagList, @QueryParam("tags") Set<String> tagSet) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring one name twice as a collection over two <em>different element types</em>
     * ({@code List<String>} plus {@code List<UUID>}). The one request name would have to be
     * simultaneously any string and a UUID: the single derived per-name parameter schema can describe
     * only one of the two (last declaration wins), and a value valid for either declaration fails the
     * other's fail-closed element conversion — so the pair can never be honored.
     */
    @Path("/duplicate-collection-element")
    public static class DuplicateCollectionElementTypeResource {

        /**
         * Declares {@code @QueryParam("ids")} as a {@code List<String>} and as a {@code List<UUID>}.
         *
         * @param strings the {@code List<String>} declaration of {@code ids}
         * @param uuids   the {@code List<UUID>} declaration of the same name
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateCollectionElement")
        public String get(@QueryParam("ids") List<String> strings, @QueryParam("ids") List<UUID> uuids) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring one name twice with a genuinely <em>identical</em> declaration — same type,
     * same annotations. Sharing one descriptor is harmless here (both parameters receive the same
     * value converted the same way), so the redundant declaration must keep building.
     */
    @Path("/duplicate-identical")
    public static class DuplicateIdenticalDeclarationResource {

        /**
         * Declares {@code @QueryParam("id") String} twice, identically.
         *
         * @param first  the first declaration of {@code id}
         * @param second the identical second declaration
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateIdentical")
        public String get(@QueryParam("id") String first, @QueryParam("id") String second) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring the same <em>header</em> name twice, differing only in case, with an identical
     * shape. Header names are matched case-insensitively, so the two collide — but they convert
     * identically, so the pair is redundant rather than unbindable. It pins the annotation comparison's
     * exclusion of the JAX-RS source annotation itself: comparing {@code @HeaderParam} instances (whose
     * {@code value()} differs in case) would reject a declaration that binds correctly.
     */
    @Path("/duplicate-header-casing-identical")
    public static class DuplicateHeaderCasingIdenticalResource {

        /**
         * Declares {@code @HeaderParam("X-Id")} and {@code @HeaderParam("x-id")}, both {@code String}.
         *
         * @param canonical the declaration in canonical header casing
         * @param lower     the declaration in lower case
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateHeaderCasingIdentical")
        public String get(@HeaderParam("X-Id") String canonical, @HeaderParam("x-id") String lower) {
            return "unreachable";
        }
    }

    /**
     * Regression guard: a single collection parameter, distinct names in one location, and the same name
     * in two <em>different</em> locations must all stay accepted. The cross-location pair pins the
     * per-location scoping — query and header are separate maps matched separately, so they cannot
     * collide.
     */
    @Path("/distinct-names")
    public static class DistinctNameMultiplicityResource {

        /**
         * Declares one collection parameter, an unrelated scalar name, and a scalar {@code @HeaderParam}
         * sharing its name with a collection-shaped {@code @QueryParam}.
         *
         * @param tags        the only declaration of {@code tags}
         * @param id          an unrelated scalar name
         * @param headerToken the scalar header declaration of {@code token}
         * @param queryTokens the collection-shaped query declaration of the same name, a different source
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "distinctNameMultiplicity")
        public String get(
                @QueryParam("tags") List<String> tags,
                @QueryParam("id") String id,
                @HeaderParam("token") String headerToken,
                @QueryParam("token") List<String> queryTokens) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring the same <em>form</em> field name twice with different multiplicities. FORM is
     * deliberately outside the guard's scope: {@code ParameterExtractor.extractFormParam} reads
     * {@code formAttributes()} per parameter and never consults {@code findDescriptor}, so the scalar
     * declaration takes the first submitted value and the collection declaration takes all of them —
     * both well-defined.
     */
    @Path("/duplicate-form-multiplicity")
    public static class DuplicateFormMultiplicityResource {

        /**
         * Declares {@code @FormParam("f")} as a scalar and as a {@code List}.
         *
         * @param scalar     the scalar declaration of the form field
         * @param collection the collection-shaped declaration of the same field
         * @return never reached in this test (only the router build is exercised)
         */
        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateFormMultiplicity")
        public String post(@FormParam("f") String scalar, @FormParam("f") List<String> collection) {
            return "unreachable";
        }
    }

    @Test
    @DisplayName("Two same-name query params with conflicting multiplicity fail router build, naming both shapes")
    void duplicateQueryParamMultiplicityFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DuplicateQueryMultiplicityResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "findDescriptor resolves a request name to the FIRST matching declaration, so one of the two "
                        + "parameters can never be bound correctly — the declaration must fail startup");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT"),
                "the violation must be the duplicate-name multiplicity one (was: " + message + ")");
        assertTrue(
                message.contains("'id' (String)") && message.contains("'id' (List<String>)"),
                "the diagnostic must name the duplicated name and BOTH declared shapes (was: " + message + ")");
        assertTrue(
                message.contains(DuplicateQueryMultiplicityResource.class.getSimpleName()) && message.contains("get"),
                "the diagnostic must name the declaring class and method (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Same-name header params differing only in case with conflicting multiplicity fail router build")
    void duplicateHeaderParamCasingMultiplicityFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateHeaderCasingMultiplicityResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "header descriptor matching is case-insensitive on both halves of binding, so 'X-Id' and "
                        + "'x-id' collide and one of the two declarations is always mis-bound");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT"),
                "the violation must be the duplicate-name multiplicity one (was: " + message + ")");
        assertTrue(
                message.contains("'X-Id' (String)") && message.contains("'x-id' (List<String>)"),
                "the diagnostic must name both declarations verbatim, with their shapes (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Query params differing only in case do not collide and pass validation")
    void queryParamNamesDifferingOnlyInCasePassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new QueryCasingDistinctResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "query descriptor matching is case-SENSITIVE, so 'id' and 'Id' are independent names — "
                        + "rejecting them would break startup for a legal declaration");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Two same-name scalar query params of different declared types fail router build, naming both shapes")
    void duplicateScalarParamTypesFailRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DuplicateScalarTypeResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "the two declarations share one descriptor and wrapScalar converts the raw value once with "
                        + "it, so the second parameter receives the first's type and every request carrying "
                        + "'id' fails in Method.invoke");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT"),
                "the violation must be the duplicate-name one (was: " + message + ")");
        assertTrue(
                message.contains("'id' (Integer)") && message.contains("'id' (UUID)"),
                "the diagnostic must name the duplicated name and BOTH declared shapes (was: " + message + ")");
        assertTrue(
                message.contains(DuplicateScalarTypeResource.class.getSimpleName()) && message.contains("get"),
                "the diagnostic must name the declaring class and method (was: " + message + ")");
        assertTrue(
                message.contains("give them distinct names") && !message.contains("same multiplicity"),
                "the only advice must be to give the parameters distinct names (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Two same-name scalar query params with different @DefaultValue fail router build")
    void duplicateScalarParamDefaultValuesFailRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateScalarDefaultValueResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "one request name cannot carry two different absent-value contracts: the single descriptor "
                        + "for the name holds one annotation array and the derived per-name schema one default");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT"),
                "the violation must be the duplicate-name one (was: " + message + ")");
        assertTrue(
                message.contains("'id' (String)") && message.contains("annotations"),
                "the diagnostic must name the duplicated name, its shape, and the annotation disagreement " + "(was: "
                        + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Two same-name collection params over different element types fail router build")
    void duplicateCollectionElementTypesFailRouterBuild(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateCollectionElementTypeResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "one name cannot be both a list of arbitrary strings and a list of UUIDs: the single derived "
                        + "per-name schema describes one of them and each parameter's element conversion is "
                        + "fail-closed, so a value valid for one declaration fails the other");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT"),
                "the violation must be the duplicate-name one (was: " + message + ")");
        assertTrue(
                message.contains("'ids' (List<String>)") && message.contains("'ids' (List<UUID>)"),
                "the diagnostic must name the duplicated name and BOTH declared shapes (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Same-name collection params of different collection shapes over one element type pass validation")
    void duplicateCollectionShapesPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DuplicateCollectionShapeResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the shared descriptor decides only multiplicity for a collection; coerceCollection converts "
                        + "and materializes per parameter, so List<String> and Set<String> both bind correctly "
                        + "and rejecting them would break a declaration that works today");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Two identical same-name declarations are redundant but harmless and pass validation")
    void duplicateIdenticalDeclarationsPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateIdenticalDeclarationResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "two identical declarations share one descriptor with no disagreement to resolve — both "
                        + "parameters receive the same value converted the same way");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Same-name header params differing only in case with an identical shape pass validation")
    void duplicateHeaderCasingIdenticalShapePassesValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateHeaderCasingIdenticalResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the annotation comparison excludes the JAX-RS source annotation itself, whose value() is the "
                        + "already-compared name: comparing @HeaderParam instances would reject 'X-Id' plus "
                        + "'x-id' even though both bind identically");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Distinct names and a same name in two different locations pass validation")
    void distinctAndCrossLocationParamNamesPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DistinctNameMultiplicityResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "descriptor matching is per location, so a header and a query parameter sharing a name are "
                        + "never in conflict; unrelated names and single declarations are unaffected");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Same-name form fields with different multiplicity pass validation (FORM bypasses findDescriptor)")
    void duplicateFormParamMultiplicityPassesValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DuplicateFormMultiplicityResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "extractFormParam reads formAttributes() per parameter and never consults findDescriptor, so "
                        + "the scalar takes the first submitted value and the collection takes all of them — both "
                        + "declarations bind correctly and must stay accepted");
        ctx.completeNow();
    }
}
