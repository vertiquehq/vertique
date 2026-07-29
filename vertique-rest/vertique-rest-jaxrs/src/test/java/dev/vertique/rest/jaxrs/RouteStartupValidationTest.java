// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.jaxrs.runtime.fixture.SortedSetBodyResource;
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
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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

    /**
     * An element type that is {@link Comparable} to a <em>different</em> type. Raw assignability to
     * {@link Comparable} holds, but {@code new TreeSet<>(elements)} invokes the synthetic
     * {@code compareTo(Object)} bridge, which casts to {@link String} and throws
     * {@code ClassCastException} — so this shape must be rejected at startup just like a type that does
     * not implement {@link Comparable} at all.
     */
    public static final class ForeignComparable implements Comparable<String> {
        @Override
        public int compareTo(String other) {
            return 0;
        }
    }

    /** Supplies the {@link Comparable} implementation to {@link InheritedComparable} from a superclass. */
    public abstract static class ComparableBase implements Comparable<ComparableBase> {
        @Override
        public int compareTo(ComparableBase other) {
            return 0;
        }
    }

    /**
     * An element type whose {@code Comparable<T>} declaration lives on a <em>superclass</em>, so the
     * guard must walk the superclass hierarchy — not just the type's own direct interfaces — to see it.
     * The type argument ({@link ComparableBase}) is assignable FROM this element type, so a
     * {@code TreeSet} compares its instances safely and the shape stays legal.
     */
    public static final class InheritedComparable extends ComparableBase {}

    /**
     * An element type implementing the <em>raw</em> {@link Comparable}, i.e. comparing against
     * {@link Object}. A raw declaration carries no type argument, so it is accepted.
     */
    @SuppressWarnings("rawtypes")
    public static final class RawComparable implements Comparable {
        @Override
        public int compareTo(Object other) {
            return 0;
        }
    }

    /**
     * An intermediate interface that <em>forwards</em> {@link Comparable}'s type argument through its
     * own type variable, so the concrete argument is bound one level below the {@code Comparable}
     * declaration.
     *
     * @param <T> the type this interface's implementations compare themselves against
     */
    public interface ForwardingComparable<T> extends Comparable<T> {}

    /**
     * An element type whose {@code Comparable} argument is bound on a <em>forwarding interface</em>
     * rather than on the {@code Comparable} declaration itself. The synthesized
     * {@code compareTo(Object)} bridge casts to {@link String}, so a {@code TreeSet} of these throws
     * {@code ClassCastException} — the guard must reject it rather than fail open just because the
     * declaration site carries a type variable.
     */
    public static final class ForwardedComparable implements ForwardingComparable<String> {
        @Override
        public int compareTo(String other) {
            return 0;
        }
    }

    /**
     * A generic superclass declaring {@code Comparable<T>} over its own type variable, so a subclass
     * binds the argument without restating the {@code Comparable} declaration.
     *
     * @param <T> the type this class's instances compare themselves against
     */
    public abstract static class GenericComparableBase<T> implements Comparable<T> {
        @Override
        public int compareTo(T other) {
            return 0;
        }
    }

    /**
     * An element type whose {@code Comparable} argument is bound on a <em>generic superclass</em>.
     * Nothing in the reachable declarations names a concrete cast target, so self-comparability cannot
     * be proven and the shape must be rejected: with a bounded type variable
     * ({@code <T extends CharSequence>}) the identical shape genuinely throws from the bridge cast.
     */
    public static final class SuperclassForwardedComparable extends GenericComparableBase<String> {}

    /**
     * Resolves a no-op converter for the sorted-shape element fixtures above (and only for the element
     * shape, {@code genericType == rawType}), so the only possible startup rejection for a parameter
     * declared over them is the sorted-shape guard itself.
     */
    public static final class SortedElementFixtureProvider implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (genericType != rawType) {
                return null;
            }
            if (rawType == ForeignComparable.class) {
                return (ParamConverter<T>) noOp(new ForeignComparable());
            }
            if (rawType == InheritedComparable.class) {
                return (ParamConverter<T>) noOp(new InheritedComparable());
            }
            if (rawType == RawComparable.class) {
                return (ParamConverter<T>) noOp(new RawComparable());
            }
            if (rawType == ForwardedComparable.class) {
                return (ParamConverter<T>) noOp(new ForwardedComparable());
            }
            if (rawType == SuperclassForwardedComparable.class) {
                return (ParamConverter<T>) noOp(new SuperclassForwardedComparable());
            }
            return null;
        }

        /**
         * Builds a converter that always yields {@code instance}.
         *
         * @param instance the fixed value every {@code fromString} call returns
         * @param <T>      the converted type
         * @return the no-op converter
         */
        private static <T> ParamConverter<T> noOp(T instance) {
            return new ParamConverter<T>() {
                @Override
                public T fromString(String value) {
                    return instance;
                }

                @Override
                public String toString(T value) {
                    return String.valueOf(value);
                }
            };
        }
    }

    /** Resource declaring a {@code SortedSet} whose element type is {@link Comparable} to another type. */
    @Path("/foreign-comparable-sorted-set")
    public static class ForeignComparableSortedSetResource {

        /**
         * Declares a {@code @QueryParam SortedSet<ForeignComparable>}, whose {@code TreeSet}
         * materialization throws {@code ClassCastException} on the bridge {@code compareTo(Object)}.
         *
         * @param values the repeated foreign-{@link Comparable} query values
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "foreignComparableSortedSet")
        public String get(@QueryParam("v") SortedSet<ForeignComparable> values) {
            return "unreachable";
        }
    }

    /** Resource declaring sorted shapes whose element types are self-comparable, one via a superclass. */
    @Path("/inherited-comparable-sorted-sets")
    public static class InheritedComparableSortedSetResource {

        /**
         * Declares a sorted shape over an element type whose {@code Comparable<ComparableBase>} comes
         * from a superclass, and one over a raw {@code Comparable} element type. Both compare safely, so
         * both must pass validation.
         *
         * @param inherited a {@code NavigableSet} whose element inherits its {@code Comparable}
         * @param raw       a {@code SortedSet} of a raw-{@link Comparable} element type
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "inheritedComparableSortedSets")
        public String get(
                @QueryParam("i") NavigableSet<InheritedComparable> inherited,
                @QueryParam("r") SortedSet<RawComparable> raw) {
            return "unreachable";
        }
    }

    /** Resource declaring a sorted shape whose element forwards its {@code Comparable} argument. */
    @Path("/forwarded-comparable-sorted-set")
    public static class ForwardedComparableSortedSetResource {

        /**
         * Declares a {@code @QueryParam NavigableSet<ForwardedComparable>}, whose {@code TreeSet}
         * materialization throws from the {@code compareTo(Object)} bridge's cast to {@link String}.
         *
         * @param values the repeated forwarded-{@link Comparable} query values
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "forwardedComparableSortedSet")
        public String get(@QueryParam("f") NavigableSet<ForwardedComparable> values) {
            return "unreachable";
        }
    }

    /** Resource declaring a sorted shape whose element binds {@code Comparable} on a superclass. */
    @Path("/superclass-forwarded-comparable-sorted-set")
    public static class SuperclassForwardedComparableSortedSetResource {

        /**
         * Declares a {@code @QueryParam SortedSet<SuperclassForwardedComparable>}, whose
         * self-comparability cannot be proven without substituting the superclass's type argument.
         *
         * @param values the repeated superclass-forwarded query values
         * @return never reached (the router build fails first)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "superclassForwardedComparableSortedSet")
        public String get(@QueryParam("g") SortedSet<SuperclassForwardedComparable> values) {
            return "unreachable";
        }
    }

    /**
     * Resource declaring sorted shapes whose element types are {@link Comparable} to a
     * <em>parameterized</em> supertype — the JDK's own temporal types.
     */
    @Path("/parameterized-comparable-sorted-sets")
    public static class ParameterizedComparableSortedSetResource {

        /**
         * Declares sorted shapes over {@code LocalDateTime} ({@code Comparable<ChronoLocalDateTime<?>>})
         * and {@code ZonedDateTime} ({@code Comparable<ChronoZonedDateTime<?>>}). The declared argument
         * is a parameterized type, so it is not a plain {@code Class}, yet its erasure is exactly the
         * bridge's cast target and is assignable from the element type — both are safe and must pass.
         *
         * @param dateTimes a {@code SortedSet<LocalDateTime>}
         * @param zoned     a {@code NavigableSet<ZonedDateTime>}
         * @return never reached in this test (only the router build is exercised)
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "parameterizedComparableSortedSets")
        public String get(
                @QueryParam("d") SortedSet<LocalDateTime> dateTimes,
                @QueryParam("z") NavigableSet<ZonedDateTime> zoned) {
            return "unreachable";
        }
    }

    @Test
    @DisplayName("A SortedSet whose element is Comparable to a DIFFERENT type fails router build")
    void foreignComparableSortedSetElementFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new SortedElementFixtureProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ForeignComparableSortedSetResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "Comparable<String> does not make the element comparable to ITSELF, so the TreeSet bridge "
                        + "compareTo(Object) casts to String and throws — startup must reject it");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("NON_COMPARABLE_SORTED_SET_ELEMENT"),
                "the violation must be the sorted-element one (was: " + message + ")");
        assertTrue(
                message.contains("'v'") && message.contains("SortedSet<ForeignComparable>"),
                "the diagnostic must name the parameter and its declared shape (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Sorted shapes whose element inherits Comparable from a superclass, or is raw, pass validation")
    void inheritedAndRawComparableSortedSetElementsPassValidation(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new SortedElementFixtureProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new InheritedComparableSortedSetResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "the guard must walk the superclass hierarchy for Comparable, and accept a raw declaration");
        ctx.completeNow();
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
    @DisplayName("A SortedSet whose element forwards Comparable's argument via an interface fails router build")
    void forwardedComparableSortedSetElementFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new SortedElementFixtureProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new ForwardedComparableSortedSetResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "Comparable's type argument is forwarded through Forwarding<T>, so the bridge casts to "
                        + "String and the TreeSet throws — a declaration whose self-comparability cannot be "
                        + "proven must be rejected, not admitted by the fail-open");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("NON_COMPARABLE_SORTED_SET_ELEMENT"),
                "the violation must be the sorted-element one (was: " + message + ")");
        assertTrue(
                message.contains("'f'") && message.contains("NavigableSet<ForwardedComparable>"),
                "the diagnostic must name the parameter and its declared shape (was: " + message + ")");
        assertTrue(
                !message.contains("UNRESOLVABLE_PARAM_CONVERTER"),
                "the element type has a converter, so the only violation must be the shape one (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("A SortedSet whose element binds Comparable's argument on a generic superclass fails router build")
    void superclassForwardedComparableSortedSetElementFailsRouterBuild(Vertx vertx, VertxTestContext ctx) {
        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new SortedElementFixtureProvider()));
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().paramConversionResolver(resolver).build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new SuperclassForwardedComparableSortedSetResource()));

        RouteRegistrationException thrown = assertThrows(
                RouteRegistrationException.class,
                () -> mount.createRouter(vertx),
                "the Comparable argument is a type variable bound only on the generic superclass, so no "
                        + "concrete cast target resolves and the shape must be rejected");
        String message = String.valueOf(thrown.getMessage());
        assertTrue(
                message.contains("NON_COMPARABLE_SORTED_SET_ELEMENT"),
                "the violation must be the sorted-element one (was: " + message + ")");
        assertTrue(
                message.contains("'g'") && message.contains("SortedSet<SuperclassForwardedComparable>"),
                "the diagnostic must name the parameter and its declared shape (was: " + message + ")");
        assertTrue(
                !message.contains("UNRESOLVABLE_PARAM_CONVERTER"),
                "the element type has a converter, so the only violation must be the shape one (was: " + message + ")");
        ctx.completeNow();
    }

    @Test
    @DisplayName("Sorted shapes over elements comparable to a parameterized supertype pass validation")
    void parameterizedArgumentComparableSortedSetElementsPassValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount =
                factory.create("/*", "openapi.json", Set.of(new ParameterizedComparableSortedSetResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "LocalDateTime/ZonedDateTime implement Comparable<ChronoXxx<?>>, whose ERASURE is the "
                        + "bridge's cast target and is assignable from the element type — these are provably "
                        + "safe and must stay accepted");
        ctx.completeNow();
    }

    @Test
    @DisplayName("A generated SortedSet<T> BODY param with a non-Comparable element registers cleanly")
    void generatedSortedSetBodyParamRegistersCleanly(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new SortedSetBodyResource()));

        assertDoesNotThrow(
                () -> mount.createRouter(vertx),
                "a BODY parameter is deserialized by the body decoders, never materialized as a TreeSet, so "
                        + "the sorted-shape guard must not reject it — the generated path resolves a BODY "
                        + "componentType, so an ungated guard fails startup for a legal declaration that the "
                        + "reflective path mounts fine");
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
