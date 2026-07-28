// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.request.EffectiveInputPolicies;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that generated JAX-RS dispatch treats {@code T[]} query/header/body params at parity
 * with the reflective path (legacy issue #153; plan findings F8, decision 8; S3).
 *
 * <p>{@code S1}/{@code S2} already made array-typed parameter FQNs resolve inside generated
 * {@code describe()} bodies (no more startup crash). What remains broken is that
 * {@code EffectiveJaxRsContractResolver.resolveComponentType} unconditionally returns {@code null}
 * for any {@link javax.lang.model.type.ArrayType}, so the generated {@code componentType()} never
 * gates the multiplicity path the way the reflective {@code ResourceScanner.resolveComponentType} /
 * {@code isScalarArrayComponent} policy does. This class pins that shared policy as a parity
 * contract between the two paths, using two independently-declared representations of each
 * parameter shape — one plain compiled fixture (scanned via the real, public
 * {@link JaxRsRouteRegistrar#scanResource(Object)} entry point) and one source-text fixture
 * compiled through the real {@link JaxRsPipelineProcessor} — mirroring the dual-representation
 * convention established by {@code RuntimeParityTest}. A single shared compiled class cannot be
 * used for both sides here: any resource type compiled via {@link ProcessorTestHarness} carries a
 * real {@code _JaxRsDescriptor} companion in its own classloader, so
 * {@code GeneratedJaxRsDescriptorRegistry}'s classloader-keyed lookup would transparently
 * short-circuit {@code scanResource} to the generated fast path and defeat the parity comparison.
 *
 * <p>{@code ParameterExtractor} and its {@code GeneratedJaxRsSupport} adapter
 * ({@code ParameterExtractorBackedSupport}) are package-private in {@code vertique-rest-jaxrs} and
 * unreachable from this module. Tests 2–4 below therefore drive the real generated
 * {@link ResourceExecutionPlan} bytecode with a minimal {@link GeneratedJaxRsSupport} stub that
 * mirrors {@code ParameterExtractor}'s documented {@code componentType()}-gated multiplicity rule
 * (decision 1) rather than calling it — the same pattern {@code ExecutionPlanEmitterTest}'s
 * {@code boundRequestReadingSupport()} already establishes. The rule itself (binding all values
 * when {@code componentType() != null}) is proven correct for the reflective path separately by
 * {@code SetAndArrayQueryParamBindTest}; what these tests add is proof that the REAL generated
 * pipeline (contract resolution + descriptor/plan emission) now populates and threads a non-null
 * {@code componentType()} for scalar array shapes.
 */
class GeneratedArrayParamParityTest {

    // --- Test 1: componentType()/source parity matrix (decision 8 / F8) ---

    /**
     * One matrix row: a parameter shape represented once as a plain compiled reflective fixture
     * instance and once as APT source text, plus the oracle expectations used to sanity-check the
     * reflective side before asserting parity against the generated side.
     *
     * @param label                       human-readable row label for {@code @ParameterizedTest} names
     * @param reflectiveResourceInstance  an instance of a plain compiled nested resource class
     * @param generatedSource             APT source text for an equivalent resource declaration
     * @param generatedResourceFqn        FQN of the resource class declared in {@code generatedSource}
     * @param expectedSource              the {@code ParamSource} both paths must resolve
     * @param expectedReflectiveComponentType the exact element type the reflective oracle must
     *                                    resolve, or {@code null} when it must resolve {@code null}
     *                                    (the primitive-array carve-out)
     * @param expectedPlatformComponentType a cross-compilation-safe exact element type
     *                                    ({@code java.lang.*}) asserted on BOTH sides once fixed;
     *                                    {@code null} for primitive and user-enum rows, where only
     *                                    the {@code componentType() != null} nullity is compared
     *                                    (a nested {@code enum} declared independently in each
     *                                    compilation unit is never the same {@code Class} object)
     */
    private record MatrixCase(
            String label,
            Object reflectiveResourceInstance,
            JavaFileObject generatedSource,
            String generatedResourceFqn,
            ResourceMethodMeta.ParamSource expectedSource,
            @Nullable Class<?> expectedReflectiveComponentType,
            @Nullable Class<?> expectedPlatformComponentType) {}

    // --- Test 1 reflective fixtures: plain compiled classes, no annotation processor involved ---

    @Path("/matrix-string")
    @PermitAll
    static class StringArrayReflective {
        @GET
        public String handle(@QueryParam("v") String[] v) {
            return "";
        }
    }

    @Path("/matrix-integer")
    @PermitAll
    static class IntegerArrayReflective {
        @GET
        public String handle(@QueryParam("v") Integer[] v) {
            return "";
        }
    }

    @Path("/matrix-long")
    @PermitAll
    static class LongArrayReflective {
        @GET
        public String handle(@QueryParam("v") Long[] v) {
            return "";
        }
    }

    @Path("/matrix-boolean")
    @PermitAll
    static class BooleanArrayReflective {
        @GET
        public String handle(@QueryParam("v") Boolean[] v) {
            return "";
        }
    }

    @Path("/matrix-character")
    @PermitAll
    static class CharacterArrayReflective {
        @GET
        public String handle(@QueryParam("v") Character[] v) {
            return "";
        }
    }

    @Path("/matrix-enum")
    @PermitAll
    static class EnumArrayReflective {
        enum Color {
            RED,
            GREEN,
            BLUE
        }

        @GET
        public String handle(@QueryParam("v") Color[] v) {
            return "";
        }
    }

    @Path("/matrix-int-body")
    @PermitAll
    static class IntArrayBodyReflective {
        @POST
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        public String handle(int[] body) {
            return "";
        }
    }

    @Path("/matrix-char-body")
    @PermitAll
    static class CharArrayBodyReflective {
        @POST
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        public String handle(char[] body) {
            return "";
        }
    }

    @Path("/matrix-byte-body")
    @PermitAll
    static class ByteArrayBodyReflective {
        @POST
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        public String handle(byte[] body) {
            return "";
        }
    }

    @Path("/matrix-short-body")
    @PermitAll
    static class ShortArrayBodyReflective {
        @POST
        @Consumes(MediaType.APPLICATION_OCTET_STREAM)
        public String handle(short[] body) {
            return "";
        }
    }

    /**
     * Builds the ten matrix rows: {@code String[]}, {@code Integer[]}, {@code Long[]},
     * {@code Boolean[]}, {@code Character[]}, an {@code enum[]} (all {@code @QueryParam}, expected
     * QUERY + non-null componentType — RED today), and {@code int[]}/{@code char[]}/{@code byte[]}/
     * {@code short[]} (all unannotated bodies, expected BODY + null componentType on both paths
     * already — the carve-out that must keep agreeing, decision 8).
     *
     * @return the matrix rows as JUnit 5 {@link Arguments}, named by their label
     */
    private static Stream<Arguments> matrixCases() {
        List<MatrixCase> cases = List.of(
                new MatrixCase(
                        "String[]",
                        new StringArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.StringArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-string")
                                public class StringArrayGenerated {
                                    public StringArrayGenerated() {}

                                    @GET
                                    public String handle(@QueryParam("v") String[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.StringArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        String.class,
                        String.class),
                new MatrixCase(
                        "Integer[]",
                        new IntegerArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.IntegerArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-integer")
                                public class IntegerArrayGenerated {
                                    public IntegerArrayGenerated() {}

                                    @GET
                                    public String handle(@QueryParam("v") Integer[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.IntegerArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        Integer.class,
                        Integer.class),
                new MatrixCase(
                        "Long[]",
                        new LongArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.LongArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-long")
                                public class LongArrayGenerated {
                                    public LongArrayGenerated() {}

                                    @GET
                                    public String handle(@QueryParam("v") Long[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.LongArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        Long.class,
                        Long.class),
                new MatrixCase(
                        "Boolean[]",
                        new BooleanArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.BooleanArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-boolean")
                                public class BooleanArrayGenerated {
                                    public BooleanArrayGenerated() {}

                                    @GET
                                    public String handle(@QueryParam("v") Boolean[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.BooleanArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        Boolean.class,
                        Boolean.class),
                new MatrixCase(
                        "Character[]",
                        new CharacterArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.CharacterArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-character")
                                public class CharacterArrayGenerated {
                                    public CharacterArrayGenerated() {}

                                    @GET
                                    public String handle(@QueryParam("v") Character[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.CharacterArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        Character.class,
                        Character.class),
                new MatrixCase(
                        "enum[]",
                        new EnumArrayReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.EnumArrayGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.GET;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.QueryParam;

                                @Path("/matrix-enum")
                                public class EnumArrayGenerated {
                                    public EnumArrayGenerated() {}

                                    public enum Color { RED, GREEN, BLUE }

                                    @GET
                                    public String handle(@QueryParam("v") Color[] v) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.EnumArrayGenerated",
                        ResourceMethodMeta.ParamSource.QUERY,
                        EnumArrayReflective.Color.class,
                        null),
                new MatrixCase(
                        "int[] (unannotated body)",
                        new IntArrayBodyReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.IntArrayBodyGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.Consumes;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.core.MediaType;

                                @Path("/matrix-int-body")
                                public class IntArrayBodyGenerated {
                                    public IntArrayBodyGenerated() {}

                                    @POST
                                    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                                    public String handle(int[] body) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.IntArrayBodyGenerated",
                        ResourceMethodMeta.ParamSource.BODY,
                        null,
                        null),
                new MatrixCase(
                        "char[] (unannotated body)",
                        new CharArrayBodyReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.CharArrayBodyGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.Consumes;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.core.MediaType;

                                @Path("/matrix-char-body")
                                public class CharArrayBodyGenerated {
                                    public CharArrayBodyGenerated() {}

                                    @POST
                                    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                                    public String handle(char[] body) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.CharArrayBodyGenerated",
                        ResourceMethodMeta.ParamSource.BODY,
                        null,
                        null),
                new MatrixCase(
                        "byte[] (unannotated body)",
                        new ByteArrayBodyReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.ByteArrayBodyGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.Consumes;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.core.MediaType;

                                @Path("/matrix-byte-body")
                                public class ByteArrayBodyGenerated {
                                    public ByteArrayBodyGenerated() {}

                                    @POST
                                    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                                    public String handle(byte[] body) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.ByteArrayBodyGenerated",
                        ResourceMethodMeta.ParamSource.BODY,
                        null,
                        null),
                new MatrixCase(
                        "short[] (unannotated body)",
                        new ShortArrayBodyReflective(),
                        SourceFiles.inline("dev.vertique.test.matrix.ShortArrayBodyGenerated", """
                                package dev.vertique.test.matrix;

                                import jakarta.ws.rs.Consumes;
                                import jakarta.ws.rs.POST;
                                import jakarta.ws.rs.Path;
                                import jakarta.ws.rs.core.MediaType;

                                @Path("/matrix-short-body")
                                public class ShortArrayBodyGenerated {
                                    public ShortArrayBodyGenerated() {}

                                    @POST
                                    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                                    public String handle(short[] body) { return ""; }
                                }
                                """),
                        "dev.vertique.test.matrix.ShortArrayBodyGenerated",
                        ResourceMethodMeta.ParamSource.BODY,
                        null,
                        null));
        return cases.stream().map(c -> Arguments.of(c.label(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrixCases")
    @DisplayName("generated componentType()/source matches reflective for each array param shape (decision 8)")
    void scalarArrayComponentPolicy_generatedMatchesReflective(String label, MatrixCase matrixCase) throws Exception {
        // Reflective side: the real, public entry point — exercises the real
        // ResourceScanner.resolveComponentType/isScalarArrayComponent policy, already proven
        // correct by ResolveComponentTypeRecognizesSetSortedSetAndArrayTest.
        List<ResourceMethodMeta> reflectiveMetas =
                new JaxRsRouteRegistrar().scanResource(matrixCase.reflectiveResourceInstance());
        ResourceMethodMeta.ParamMeta reflective =
                reflectiveMetas.get(0).params().get(0);

        // Oracle sanity check before comparing.
        assertEquals(matrixCase.expectedSource(), reflective.source(), label + ": reflective source sanity check");
        assertEquals(
                matrixCase.expectedReflectiveComponentType(),
                reflective.componentType(),
                label + ": reflective componentType() sanity check");

        // Generated side: real annotation processor + real describe().
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), matrixCase.generatedSource());
        result.assertSuccess();
        List<ResourceMethodMeta> generatedMetas = callDescribe(
                result, matrixCase.generatedResourceFqn(), matrixCase.generatedResourceFqn() + "_JaxRsDescriptor");
        ResourceMethodMeta.ParamMeta generated = generatedMetas.get(0).params().get(0);

        // Parity — the assertion under test. RED today for the boxed/String/enum shapes:
        // EffectiveJaxRsContractResolver.resolveComponentType returns null for every ArrayType, so
        // generated.componentType() is null where reflective.componentType() is not. The
        // int[]/char[]/byte[]/short[] rows must already agree (both null, both BODY) — that
        // agreement is the carve-out (decision 8) and must NOT regress.
        assertEquals(reflective.source(), generated.source(), label + ": parity breach on param source");
        assertEquals(
                reflective.componentType() != null,
                generated.componentType() != null,
                label + ": parity breach on componentType() nullity (the componentType != null multiplicity gate,"
                        + " decision 1)");
        if (matrixCase.expectedPlatformComponentType() != null) {
            assertEquals(
                    matrixCase.expectedPlatformComponentType(),
                    generated.componentType(),
                    label + ": parity breach on componentType() exact value");
        }
    }

    // --- Helper: load and call describe() (mirrors JaxRsDescriptorArrayParamTest#callDescribe) ---

    /**
     * Loads the generated {@code _JaxRsDescriptor} class, instantiates it, and calls
     * {@code describe(resourceInstance, support, emptyList)} to obtain the method meta list.
     *
     * @param result        the compilation result
     * @param resourceFqn   FQN of the generated resource class (compiled from fixture)
     * @param descriptorFqn FQN of the expected generated descriptor class
     * @return the list of {@link ResourceMethodMeta} returned by {@code describe()}
     * @throws Exception if any reflection step fails
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, String resourceFqn, String descriptorFqn) throws Exception {
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass(resourceFqn);
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();

        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();

        java.lang.reflect.Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());
    }

    // --- Tests 2 & 3: multi-value QUERY/HEADER binding under real generated dispatch ---

    @Path("/matrix-string-query-bind")
    @PermitAll
    static class StringArrayQueryBindReflective {
        @GET
        public String handle(@QueryParam("tags") String[] tags) {
            return "";
        }
    }

    @Path("/matrix-string-header-bind")
    @PermitAll
    static class StringArrayHeaderBindReflective {
        @GET
        public String handle(@HeaderParam("tags") String[] tags) {
            return "";
        }
    }

    @Test
    @DisplayName("?tags=a&tags=b — generated dispatch binds both values, at parity with reflective (F1/#153)")
    void arrayQueryParam_generatedAndReflective_bindAllValues() throws Exception {
        ResourceMethodMeta.ParamMeta reflectiveMeta = new JaxRsRouteRegistrar()
                .scanResource(new StringArrayQueryBindReflective())
                .get(0)
                .params()
                .get(0);

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.matrix.StringArrayQueryBindGenerated", """
                        package dev.vertique.test.matrix;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/matrix-string-query-bind")
                        public class StringArrayQueryBindGenerated {
                            public StringArrayQueryBindGenerated() {}

                            @GET
                            public String handle(@QueryParam("tags") String[] tags) { return ""; }
                        }
                        """));
        result.assertSuccess();

        Class<?> planClass = result.loadGeneratedClass(
                "dev.vertique.test.matrix.StringArrayQueryBindGenerated_handle_0_ExecutionPlan");
        ResourceExecutionPlan plan =
                (ResourceExecutionPlan) planClass.getDeclaredConstructor().newInstance();

        BoundRequest boundRequest = multiValueBoundRequest(ResourceMethodMeta.ParamSource.QUERY, "tags", "a", "b");

        Object[] generatedArgs = plan.extractArguments(null, boundRequest, multiValueMirroringSupport());
        Object reflectiveResult = extractMultiValueMirroringParameterExtractor(
                reflectiveMeta, boundRequest.query().get("tags"));

        assertContentEquals(Set.of("a", "b"), reflectiveResult, "reflective (oracle)");
        assertContentEquals(Set.of("a", "b"), generatedArgs[0], "generated");
    }

    @Test
    @DisplayName("Header: tags=a, tags=b — generated dispatch binds both values, at parity with reflective (F1/#153)")
    void arrayHeaderParam_generatedAndReflective_bindAllValues() throws Exception {
        ResourceMethodMeta.ParamMeta reflectiveMeta = new JaxRsRouteRegistrar()
                .scanResource(new StringArrayHeaderBindReflective())
                .get(0)
                .params()
                .get(0);

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.matrix.StringArrayHeaderBindGenerated", """
                        package dev.vertique.test.matrix;

                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.HeaderParam;
                        import jakarta.ws.rs.Path;

                        @Path("/matrix-string-header-bind")
                        public class StringArrayHeaderBindGenerated {
                            public StringArrayHeaderBindGenerated() {}

                            @GET
                            public String handle(@HeaderParam("tags") String[] tags) { return ""; }
                        }
                        """));
        result.assertSuccess();

        Class<?> planClass = result.loadGeneratedClass(
                "dev.vertique.test.matrix.StringArrayHeaderBindGenerated_handle_0_ExecutionPlan");
        ResourceExecutionPlan plan =
                (ResourceExecutionPlan) planClass.getDeclaredConstructor().newInstance();

        BoundRequest boundRequest = multiValueBoundRequest(ResourceMethodMeta.ParamSource.HEADER, "tags", "a", "b");

        Object[] generatedArgs = plan.extractArguments(null, boundRequest, multiValueMirroringSupport());
        Object reflectiveResult = extractMultiValueMirroringParameterExtractor(
                reflectiveMeta, boundRequest.headers().get("tags"));

        assertContentEquals(Set.of("a", "b"), reflectiveResult, "reflective (oracle)");
        assertContentEquals(Set.of("a", "b"), generatedArgs[0], "generated");
    }

    /**
     * Builds a {@link BoundRequest} exposing a two-element {@link JsonArray} under {@code name} on
     * the map matching {@code source} (QUERY or HEADER); all other maps are empty. Mirrors
     * {@code SetAndArrayQueryParamBindTest#boundQuery}'s representation of a repeated request value.
     *
     * @param source the source whose map should carry the values ({@code QUERY} or {@code HEADER})
     * @param name   the parameter name
     * @param values the submitted values, in submission order (no ordering guarantee is asserted, F8)
     * @return a {@link BoundRequest} stub exposing exactly one populated map
     */
    private static BoundRequest multiValueBoundRequest(
            ResourceMethodMeta.ParamSource source, String name, String... values) {
        Map<String, RequestValue> populated = Map.of(name, RequestValue.of(new JsonArray(List.of(values))));
        Map<String, RequestValue> empty = Map.of();
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return empty;
            }

            @Override
            public Map<String, RequestValue> query() {
                return source == ResourceMethodMeta.ParamSource.QUERY ? populated : empty;
            }

            @Override
            public Map<String, RequestValue> headers() {
                return source == ResourceMethodMeta.ParamSource.HEADER ? populated : empty;
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return empty;
            }

            @Override
            public RequestValue body() {
                return RequestValue.of(null);
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }

    /**
     * Mirrors {@code ParameterExtractor}'s {@code componentType()}-gated multiplicity rule (decision
     * 1) for a repeated string-valued source: when {@code meta.componentType() != null}, every
     * submitted value is read into a {@code String[]}; otherwise (today's generated behavior for
     * every scalar array shape) only the first submitted value is read — the precise pre-fix
     * defect the F1 Amendment describes ("exactly one value bound", never "the first value" as an
     * ordering claim, F8). {@code ParameterExtractor} itself is package-private in
     * {@code vertique-rest-jaxrs} and unreachable from this module; its actual binding behavior for
     * a non-null {@code componentType()} is separately proven correct by
     * {@code SetAndArrayQueryParamBindTest}.
     *
     * @param meta the parameter metadata whose {@code componentType()} gates the rule
     * @param rv   the bound request value for this parameter, or {@code null} if absent
     * @return a {@code String[]} of every submitted value when {@code componentType() != null}; a
     *         single-element {@code String[]} otherwise; {@code null} when {@code rv} is {@code null}
     */
    private static Object extractMultiValueMirroringParameterExtractor(
            ResourceMethodMeta.ParamMeta meta, RequestValue rv) {
        if (rv == null) {
            return null;
        }
        JsonArray values = rv.getJsonArray();
        if (meta.componentType() != null && values != null) {
            String[] result = new String[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.getString(i);
            }
            return result;
        }
        if (values != null && !values.isEmpty()) {
            return new String[] {values.getString(0)};
        }
        return rv.getString();
    }

    /**
     * A {@link GeneratedJaxRsSupport} stub whose {@code extractScalarParam} delegates to
     * {@link #extractMultiValueMirroringParameterExtractor}, reading the QUERY or HEADER map off the
     * {@link BoundRequest} passed to {@link ResourceExecutionPlan#extractArguments}. All other
     * methods are no-ops, mirroring {@code ExecutionPlanEmitterTest#noOpSupport}.
     *
     * @return the support stub
     */
    private static GeneratedJaxRsSupport multiValueMirroringSupport() {
        return new GeneratedJaxRsSupport() {
            @Override
            public Object extractScalarParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, BoundRequest r) {
                Map<String, RequestValue> map =
                        switch (m.source()) {
                            case QUERY -> r.query();
                            case HEADER -> r.headers();
                            default -> throw new IllegalStateException("Unexpected source: " + m.source());
                        };
                return extractMultiValueMirroringParameterExtractor(m, map.get(m.name()));
            }

            @Override
            public Object extractFormParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return null;
            }

            @Override
            public Object extractFileUploads(RoutingContext ctx) {
                return List.of();
            }

            @Override
            public Object extractEntityParts(ResourceMethodMeta.ParamMeta m, RoutingContext c) {
                return List.of();
            }

            @Override
            public Object deserializeBody(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return null;
            }

            @Override
            public Object resolveContext(Class<?> declaredType, RoutingContext c, String resourceClass, String method) {
                return null;
            }

            @Override
            public Object materializeBean(
                    BeanParamFieldMeta[] fields,
                    EffectiveInputPolicies routePolicies,
                    BoundRequest request,
                    RoutingContext ctx,
                    Class<?> beanType) {
                return null;
            }
        };
    }

    /**
     * Asserts that {@code actual} is a {@code String[]} whose CONTENT (never order — F8, decision 7)
     * equals {@code expected}.
     *
     * @param expected the expected element set
     * @param actual   the value under test; must be a {@code String[]}
     * @param label    a label identifying which side ("reflective"/"generated") is being asserted,
     *                 included in failure messages
     */
    private static void assertContentEquals(Set<String> expected, Object actual, String label) {
        String[] array = assertInstanceOf(String[].class, actual, label + ": expected a String[] result");
        assertEquals(expected.size(), array.length, label + ": element count (content-only, no ordering claim)");
        assertEquals(expected, Set.copyOf(List.of(array)), label + ": element content");
    }

    // --- Test 4: byte[] body under real generated dispatch (extraction + invocation) ---

    /**
     * Compiles a {@code byte[]} body resource, drives the real generated {@link ResourceExecutionPlan}
     * (extraction, then invocation) with a support stub that hands back a fixed byte array, and
     * asserts the resource method receives those exact bytes.
     *
     * <p><strong>Feasibility note (reported to the caller, not silently narrowed):</strong> a true
     * end-to-end proof — a real Vert.x {@code HttpServer}, a real binary POST, and a real
     * {@code JaxRsRouteRegistrar.registerAll(...)}-registered route — is NOT attempted here.
     * {@code registerAll} takes ~20 parameters (security handlers, decoders/encoders, validation
     * strategy, {@code ParamConversionResolver}, etc.) that exist nowhere else in this module's test
     * scope; no example module currently wires {@code vertique-codegen-jaxrs} into its
     * {@code annotationProcessorPaths} either, so there is no consumer app to reuse. The cheapest
     * *honest* proof achievable from this module is what this test does: load the real compiled
     * {@code _ExecutionPlan} class (not a hand-written stand-in) and call its real
     * {@code extractArguments}/{@code invoke}, which is the same level of proof
     * {@code ExecutionPlanEmitterTest.GeneratedDispatchParity} already uses for scalar params. A
     * genuine HTTP-transport e2e, if wanted, belongs in {@code vertique-codegen-integration-tests}
     * (which already runs real Maven builds against generated output) or in
     * {@code vertique-rest-jaxrs} (which already has {@code registerAll}-based IT infrastructure,
     * e.g. {@code AnnotationDrivenRoutingIT}).
     *
     * @throws Throwable if any reflection step fails, or if the generated {@code invoke} rethrows
     *                    (its contract declares {@code Throwable}, matching a direct resource-method
     *                    invocation with no {@code InvocationTargetException} wrapping)
     */
    @Test
    @DisplayName("byte[] body — generated ExecutionPlan extraction + invocation serves the exact posted bytes")
    void byteArrayBody_generatedDispatch_servesBinaryBody() throws Throwable {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.matrix.ByteBodyDispatchGenerated", """
                        package dev.vertique.test.matrix;

                        import jakarta.ws.rs.Consumes;
                        import jakarta.ws.rs.POST;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.core.MediaType;

                        @Path("/matrix-byte-body-dispatch")
                        public class ByteBodyDispatchGenerated {
                            public ByteBodyDispatchGenerated() {}

                            @POST
                            @Consumes(MediaType.APPLICATION_OCTET_STREAM)
                            public String upload(byte[] body) { return String.valueOf(body.length); }
                        }
                        """));
        result.assertSuccess();

        byte[] expectedBytes = {1, 2, 3, 4, 5};

        Class<?> planClass =
                result.loadGeneratedClass("dev.vertique.test.matrix.ByteBodyDispatchGenerated_upload_0_ExecutionPlan");
        ResourceExecutionPlan plan =
                (ResourceExecutionPlan) planClass.getDeclaredConstructor().newInstance();

        GeneratedJaxRsSupport support = new GeneratedJaxRsSupport() {
            @Override
            public Object extractScalarParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, BoundRequest r) {
                return null;
            }

            @Override
            public Object extractFormParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return null;
            }

            @Override
            public Object extractFileUploads(RoutingContext ctx) {
                return List.of();
            }

            @Override
            public Object extractEntityParts(ResourceMethodMeta.ParamMeta m, RoutingContext c) {
                return List.of();
            }

            @Override
            public Object deserializeBody(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return expectedBytes;
            }

            @Override
            public Object resolveContext(Class<?> declaredType, RoutingContext c, String resourceClass, String method) {
                return null;
            }

            @Override
            public Object materializeBean(
                    BeanParamFieldMeta[] fields,
                    EffectiveInputPolicies routePolicies,
                    BoundRequest request,
                    RoutingContext ctx,
                    Class<?> beanType) {
                return null;
            }
        };

        BoundRequest emptyBoundRequest = multiValueBoundRequest(ResourceMethodMeta.ParamSource.QUERY, "unused");
        Object[] args = plan.extractArguments(null, emptyBoundRequest, support);

        assertEquals(1, args.length, "exactly one argument for a single byte[] body param");
        byte[] extracted = assertInstanceOf(byte[].class, args[0], "extracted argument must be a byte[]");
        assertArrayEquals(expectedBytes, extracted, "generated extraction must hand back the EXACT posted bytes");

        Object resourceInstance = result.generatedClassLoader()
                .loadClass("dev.vertique.test.matrix.ByteBodyDispatchGenerated")
                .getDeclaredConstructor()
                .newInstance();
        Object invoked = plan.invoke(resourceInstance, args);

        assertEquals(
                String.valueOf(expectedBytes.length),
                invoked,
                "resource method must receive the exact byte[] (observed via its returned length)");
    }
}
