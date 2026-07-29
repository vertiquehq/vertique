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

    // --- Duplicate parameter names with conflicting multiplicity ---

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
     * Resource declaring duplicated names whose multiplicities <em>agree</em> — the shape the
     * multiplicity guard deliberately does not inspect.
     *
     * <p>The two scalar {@code id} declarations use <em>different</em> types ({@link Integer} and
     * {@link UUID}) on purpose. Same-multiplicity duplicates still share one descriptor, and
     * {@code DefaultBoundRequest.wrapScalar} coerces the raw value once with the <em>first</em> matching
     * descriptor, so this declaration mounts and then fails in {@code Method.invoke} on every request
     * carrying {@code id}. Declaring both as {@code String} would be the one configuration that cannot
     * expose that — the mis-binding would be invisible, and the fixture would document a benign case
     * instead of the real, still-unvalidated one.
     */
    @Path("/duplicate-matching-multiplicity")
    public static class DuplicateMatchingMultiplicityResource {

        /**
         * Declares {@code id} twice as a scalar of two different types, and {@code tags} twice as a
         * collection (in two different collection shapes, which is still one multiplicity).
         *
         * @param first      the first scalar declaration of {@code id}, an {@link Integer}
         * @param second     the second scalar declaration of the same name, a {@link UUID}
         * @param tagList    the {@code List} declaration of {@code tags}
         * @param tagSet     the {@code Set} declaration of {@code tags}
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateMatchingMultiplicity")
        public String get(
                @QueryParam("id") Integer first,
                @QueryParam("id") UUID second,
                @QueryParam("tags") List<String> tagList,
                @QueryParam("tags") Set<String> tagSet) {
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

    /**
     * Pins the multiplicity guard's <b>scope</b>, not the safety of what it lets through. The fixture is a
     * genuinely mis-binding declaration — two scalar {@code @QueryParam("id")} parameters of different
     * types — which mounts because same-multiplicity duplicates are not validated anywhere today. It fails
     * at request time, in {@code Method.invoke}, since {@code wrapScalar} converts the value once using the
     * first matching descriptor.
     *
     * <p>So this test documents current scope: widening the guard to reject it would break declarations
     * that mount today and is a separate, consumer-visible decision. If that decision is ever taken, this
     * test is the one that must change.
     *
     * @param vertx the injected Vert.x instance
     * @param ctx   the test context
     */
    @Test
    @DisplayName("Same-multiplicity duplicate names are out of the guard's scope and still mount (unsafe or not)")
    void duplicateParamNamesWithMatchingMultiplicityAreNotValidatedHere(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new DuplicateMatchingMultiplicityResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the guard reports multiplicity conflicts only; a same-multiplicity pair whose declared "
                        + "types differ (Integer plus UUID here) is NOT validated — it mounts and then fails "
                        + "in Method.invoke per request, which is current, documented scope rather than a "
                        + "safety guarantee");
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
