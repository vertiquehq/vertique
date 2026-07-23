// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof for GitHub issue #162 (rest-jaxrs codegen literal-backed parameter annotations,
 * slice 6): a JAX-RS {@code ParamConverterProvider} that inspects <em>parameter annotations</em> to
 * decide whether it applies now works identically on codegen-generated routes — both the
 * per-request execution-plan path ({@link dev.vertique.codegen.jaxrs.emit.ExecutionPlanEmitter}) and
 * the startup-descriptor path ({@link dev.vertique.codegen.jaxrs.emit.JaxRsDescriptorEmitter}) — as
 * it already does on reflectively-scanned routes.
 *
 * <p>Coverage spans both annotation-member kinds: a fully-literalizable marker
 * ({@link TestParamMarker}, materialized as a compile-time literal) and an annotation with an
 * <em>unsupported</em> member kind ({@link TestUnsupportedMemberMarker}, whose {@code nested()}
 * member is a nested annotation {@code AnnotationLiteralEmitter} cannot render as a literal). The
 * latter is supplied on the codegen route only by the per-parameter reflective fallback
 * ({@code dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations}); the
 * unsupported-member tests prove a provider that reads its {@code value()} member behaves identically
 * to the reflective scan, so the fallback genuinely surfaces the full annotation (correct member
 * value) — not merely a bare-presence signal.
 *
 * <p>Before slices 1-5 of this issue, {@code ExecutionPlanEmitter} emitted a {@code null} parameter
 * annotations array (the provider would never even be consulted meaningfully) and
 * {@code JaxRsDescriptorEmitter} emitted a live reflective read (functionally present, but not
 * literal-backed). Both paths now emit a compile-time literal-backed {@code ParameterMetadata}, so
 * {@code ConversionContexts.forParamMeta(...).annotationsLazy()} — the exact bridge
 * {@code ParameterExtractor} and the descriptor-driven validation/security/OpenAPI paths consume —
 * supplies the provider with the real, materialized annotation.
 *
 * <p>The fixture's parameter type is {@link TestNoNativeConverterType} (not {@code String}): the
 * framework registers a built-in identity converter for {@code String}
 * ({@code BuiltinParamConverters}), and {@code ParamConversionResolver} always consults the native
 * registry before any JAX-RS provider — so a {@code String}-typed parameter would resolve via the
 * native converter without ever consulting the provider, making the proof vacuous. Using a type with
 * no native converter forces the JAX-RS provider to be the only possible source of a converter.
 *
 * <p>This test drives {@code ParamConversionResolver.fromString(...)} directly against
 * {@code ConversionContexts.forParamMeta(pm)} for a {@code ParamMeta} sourced from EACH codegen path
 * (the execution plan's {@code P0} constant, and the descriptor's {@code describe()} output),
 * exercising the exact production call {@code ParameterExtractor.extractScalarParam} makes
 * internally (that class itself is package-private in {@code vertique-rest-jaxrs} and unreachable
 * from this module's tests, so this test reproduces its conversion call verbatim rather than driving
 * it through the package-private class).
 */
class AnnotationSensitiveParamConverterCodegenTest {

    /**
     * A JAX-RS provider that returns a converter for {@link TestNoNativeConverterType} <em>only</em>
     * when the parameter carries {@link TestParamMarker}. This is the forcing case: a provider that
     * ignores annotations would pass trivially even with a {@code null}/absent annotations array (it
     * doesn't consult them), so this provider must genuinely inspect the {@code annotations} argument
     * to prove the fix.
     */
    public static final class MarkerSensitiveProvider implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != TestNoNativeConverterType.class) {
                return null;
            }
            boolean hasMarker = false;
            for (Annotation a : annotations) {
                if (a.annotationType().getSimpleName().equals("TestParamMarker")) {
                    hasMarker = true;
                    break;
                }
            }
            if (!hasMarker) {
                return null;
            }
            return (ParamConverter<T>) new ParamConverter<TestNoNativeConverterType>() {
                @Override
                public TestNoNativeConverterType fromString(String value) {
                    return new TestNoNativeConverterType("converted:" + value);
                }

                @Override
                public String toString(TestNoNativeConverterType value) {
                    return value.value();
                }
            };
        }
    }

    /**
     * A JAX-RS provider that keys its conversion off the <em>member value</em> of
     * {@link TestUnsupportedMemberMarker} — an annotation whose {@code nested()} member is a nested
     * annotation, an attribute kind {@code AnnotationLiteralEmitter} cannot render as a compile-time
     * literal. On a codegen route this annotation is therefore supplied purely by the per-parameter
     * reflective fallback ({@code GeneratedJaxRsReflectiveAnnotations}); a provider can only read its
     * {@code value()} if that fallback is wired and consulted.
     *
     * <p>Unlike {@link MarkerSensitiveProvider} (which merely checks the annotation is <em>present</em>),
     * this provider reads {@code marker.value()} and folds it into the converted result, so the test can
     * assert the provider received the real, correct member value — not just a bare presence signal.
     */
    public static final class UnsupportedMemberSensitiveProvider implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != TestNoNativeConverterType.class) {
                return null;
            }
            String memberValue = null;
            for (Annotation a : annotations) {
                if (a instanceof TestUnsupportedMemberMarker marker) {
                    memberValue = marker.value();
                    break;
                }
            }
            if (memberValue == null) {
                return null;
            }
            final String captured = memberValue;
            return (ParamConverter<T>) new ParamConverter<TestNoNativeConverterType>() {
                @Override
                public TestNoNativeConverterType fromString(String value) {
                    return new TestNoNativeConverterType(captured + ":" + value);
                }

                @Override
                public String toString(TestNoNativeConverterType value) {
                    return value.value();
                }
            };
        }
    }

    /**
     * Reflects the sole declared method of the harness-compiled resource and returns its single
     * parameter's annotation array — the exact array {@code ResourceScanner} (the reflective-scan path)
     * would read via {@code Parameter.getAnnotations()}. Used as the reflective baseline the codegen
     * path must match.
     *
     * @param resourceClass the harness-loaded resource class
     * @return the annotation array of the resource method's first (and only) parameter
     * @throws Exception if reflection fails
     */
    private static Annotation[] reflectiveParameterAnnotations(Class<?> resourceClass) throws Exception {
        Method endpoint = null;
        for (Method m : resourceClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 1) {
                endpoint = m;
                break;
            }
        }
        if (endpoint == null) {
            throw new IllegalStateException("No single-parameter endpoint method found on " + resourceClass);
        }
        return endpoint.getParameters()[0].getAnnotations();
    }

    @Test
    @DisplayName(
            "codegen ExecutionPlan route — annotation-sensitive ParamConverterProvider receives real parameter annotations")
    void codegenExecutionPlanRoute_annotationSensitiveParamConverterReceivesRealAnnotations() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.E2eMarkedResource", """
                        package dev.vertique.test;

                        import dev.vertique.codegen.jaxrs.TestNoNativeConverterType;
                        import dev.vertique.codegen.jaxrs.TestParamMarker;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/e2e-marked")
                        public class E2eMarkedResource {
                            public E2eMarkedResource() {}

                            @GET
                            public String getMarked(@QueryParam("id") @TestParamMarker("x") TestNoNativeConverterType id) {
                                return String.valueOf(id);
                            }
                        }
                        """));

        result.assertSuccess();

        Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.E2eMarkedResource_getMarked_0_ExecutionPlan");
        Field pField = planClass.getDeclaredField("P0");
        pField.setAccessible(true);
        ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);

        // Drive the exact production call: ParameterExtractor.extractScalarParam ultimately calls
        // paramConversionResolver.fromString(raw, ConversionContexts.forParamMeta(paramMeta)).
        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new MarkerSensitiveProvider()));
        ConversionContext ctx = ConversionContexts.forParamMeta(paramMeta);
        Object converted = resolver.fromString("42", ctx);

        TestNoNativeConverterType typed = assertInstanceOf(
                TestNoNativeConverterType.class,
                converted,
                "The annotation-sensitive provider must have matched via the real TestParamMarker "
                        + "annotation materialized on the codegen execution-plan path, proving "
                        + "ConversionContexts.forParamMeta(...).annotationsLazy() supplies the real "
                        + "annotation rather than an empty/absent array");
        assertEquals("converted:42", typed.value());
    }

    @Test
    @DisplayName(
            "codegen JaxRsDescriptor route — annotation-sensitive ParamConverterProvider receives real parameter annotations")
    void codegenDescriptorRoute_annotationSensitiveParamConverterReceivesRealAnnotations() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.E2eDescMarkedResource", """
                        package dev.vertique.test;

                        import dev.vertique.codegen.jaxrs.TestNoNativeConverterType;
                        import dev.vertique.codegen.jaxrs.TestParamMarker;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/e2e-desc-marked")
                        public class E2eDescMarkedResource {
                            public E2eDescMarkedResource() {}

                            @GET
                            public String getMarked(@QueryParam("id") @TestParamMarker("y") TestNoNativeConverterType id) {
                                return String.valueOf(id);
                            }
                        }
                        """));

        result.assertSuccess();

        // Drive the real describe() output, exactly as the descriptor-registration path
        // (JaxRsRouteRegistrar) would consume it.
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass("dev.vertique.test.E2eDescMarkedResource");
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();
        Class<?> descriptorClass = result.loadGeneratedClass("dev.vertique.test.E2eDescMarkedResource_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();
        Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        @SuppressWarnings("unchecked")
        List<ResourceMethodMeta> metas = (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());

        ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);

        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new MarkerSensitiveProvider()));
        ConversionContext ctx = ConversionContexts.forParamMeta(paramMeta);
        Object converted = resolver.fromString("99", ctx);

        TestNoNativeConverterType typed = assertInstanceOf(
                TestNoNativeConverterType.class,
                converted,
                "The annotation-sensitive provider must have matched via the real TestParamMarker "
                        + "annotation materialized on the codegen descriptor path");
        assertEquals("converted:99", typed.value());
    }

    @Test
    @DisplayName("codegen route without the marker annotation — provider correctly declines (negative-space control)")
    void codegenRoute_withoutMarker_providerDeclines() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.E2eUnmarkedResource", """
                        package dev.vertique.test;

                        import dev.vertique.codegen.jaxrs.TestNoNativeConverterType;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/e2e-unmarked")
                        public class E2eUnmarkedResource {
                            public E2eUnmarkedResource() {}

                            @GET
                            public String get(@QueryParam("id") TestNoNativeConverterType id) {
                                return String.valueOf(id);
                            }
                        }
                        """));

        result.assertSuccess();

        Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.E2eUnmarkedResource_get_0_ExecutionPlan");
        Field pField = planClass.getDeclaredField("P0");
        pField.setAccessible(true);
        ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);

        ParamConversionResolver resolver =
                ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of(new MarkerSensitiveProvider()));
        ConversionContext ctx = ConversionContexts.forParamMeta(paramMeta);

        // Negative-space control: MarkerSensitiveProvider.getConverter(...) returns null when the
        // parameter carries no TestParamMarker annotation, and no native converter exists for
        // TestNoNativeConverterType, so canResolve(ctx) must be false — proving the earlier
        // positive-case matches genuinely depend on the marker annotation being present, rather than
        // the provider matching unconditionally regardless of annotations.
        assertFalse(
                resolver.canResolve(ctx),
                "Without the marker annotation, the annotation-sensitive provider must not match, and no "
                        + "native converter is registered for TestNoNativeConverterType in this resolver");
    }

    @Test
    @DisplayName(
            "codegen ExecutionPlan route — provider sees UNSUPPORTED-member annotation via reflective fallback, identical to reflective scan")
    void codegenExecutionPlanRoute_unsupportedMemberAnnotation_matchesReflectiveScan() throws Exception {
        // @TestUnsupportedMemberMarker carries a nested-annotation member (nested()), which
        // AnnotationLiteralEmitter cannot render as a compile-time literal. It is therefore supplied
        // on the codegen route only by the per-parameter reflective fallback
        // (GeneratedJaxRsReflectiveAnnotations). This test proves a real ParamConverterProvider that
        // reads that annotation's value() member behaves IDENTICALLY on the codegen route as on the
        // reflective-scan path — same converted result — proving the fallback genuinely surfaces the
        // annotation (with the correct member value) to the provider.
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.E2eUnsupportedMarkedResource", """
                        package dev.vertique.test;

                        import dev.vertique.codegen.jaxrs.TestNoNativeConverterType;
                        import dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/e2e-unsupported-marked")
                        public class E2eUnsupportedMarkedResource {
                            public E2eUnsupportedMarkedResource() {}

                            @GET
                            public String getMarked(
                                    @QueryParam("id") @TestUnsupportedMemberMarker("plan") TestNoNativeConverterType id) {
                                return String.valueOf(id);
                            }
                        }
                        """));

        // The unsupported member must not fail the build (ADR-0146 parity-first policy).
        result.assertSuccess();

        Class<?> planClass =
                result.loadGeneratedClass("dev.vertique.test.E2eUnsupportedMarkedResource_getMarked_0_ExecutionPlan");
        Field pField = planClass.getDeclaredField("P0");
        pField.setAccessible(true);
        ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);

        // Fallback-exercise proof: the codegen ParamMeta's annotationsLazy() must include the
        // unsupported-member annotation. Since AnnotationLiteralEmitter cannot render it as a literal,
        // its presence here is proof the reflective fallback (GeneratedJaxRsReflectiveAnnotations)
        // supplied it. Against a hypothetical no-fallback state this array would omit the annotation
        // (RED), the provider below would decline, and the conversion would fail to resolve.
        boolean fallbackSuppliedAnnotation = false;
        for (Annotation a : paramMeta.annotationsLazy().get()) {
            if (a instanceof TestUnsupportedMemberMarker) {
                fallbackSuppliedAnnotation = true;
                break;
            }
        }
        assertTrue(
                fallbackSuppliedAnnotation,
                "The codegen ExecutionPlan ParamMeta must expose the unsupported-member annotation via "
                        + "annotationsLazy() — it is unliteralizable, so its presence proves the reflective "
                        + "fallback (GeneratedJaxRsReflectiveAnnotations) is wired and consulted");

        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new UnsupportedMemberSensitiveProvider()));

        // Codegen path: convert through ConversionContexts.forParamMeta(...) (the exact production bridge).
        ConversionContext codegenCtx = ConversionContexts.forParamMeta(paramMeta);
        Object codegenConverted = resolver.fromString("7", codegenCtx);

        // Reflective baseline: the same provider reading the annotation array the reflective scan would
        // read directly off the resource method's parameter.
        Class<?> resourceClass =
                result.generatedClassLoader().loadClass("dev.vertique.test.E2eUnsupportedMarkedResource");
        Annotation[] reflectiveAnnotations = reflectiveParameterAnnotations(resourceClass);
        ParamConverter<?> reflectiveConverter = new UnsupportedMemberSensitiveProvider()
                .getConverter(TestNoNativeConverterType.class, TestNoNativeConverterType.class, reflectiveAnnotations);
        assertTrue(
                reflectiveConverter != null,
                "Reflective-scan baseline: the provider must match on the reflectively-read annotation array");
        @SuppressWarnings("unchecked")
        Object reflectiveConverted = ((ParamConverter<TestNoNativeConverterType>) reflectiveConverter).fromString("7");

        TestNoNativeConverterType codegenTyped = assertInstanceOf(
                TestNoNativeConverterType.class,
                codegenConverted,
                "The provider must have matched via the unsupported-member annotation materialized on the "
                        + "codegen execution-plan path through the reflective fallback");
        // The provider keyed its output off the annotation's value() member ("plan"), proving it read the
        // real member value — not merely detected presence.
        assertEquals("plan:7", codegenTyped.value());
        // Identical behavior: codegen path == reflective-scan path.
        assertEquals(
                ((TestNoNativeConverterType) reflectiveConverted).value(),
                codegenTyped.value(),
                "The codegen route's provider must produce the IDENTICAL converted result as the "
                        + "reflective-scan route — proving it sees the same unsupported-member annotation "
                        + "(same value() member) via the reflective fallback");
    }

    @Test
    @DisplayName(
            "codegen JaxRsDescriptor route — provider sees UNSUPPORTED-member annotation via reflective fallback, identical to reflective scan")
    void codegenDescriptorRoute_unsupportedMemberAnnotation_matchesReflectiveScan() throws Exception {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.E2eDescUnsupportedMarkedResource", """
                        package dev.vertique.test;

                        import dev.vertique.codegen.jaxrs.TestNoNativeConverterType;
                        import dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;
                        import jakarta.ws.rs.QueryParam;

                        @Path("/e2e-desc-unsupported-marked")
                        public class E2eDescUnsupportedMarkedResource {
                            public E2eDescUnsupportedMarkedResource() {}

                            @GET
                            public String getMarked(
                                    @QueryParam("id") @TestUnsupportedMemberMarker("desc") TestNoNativeConverterType id) {
                                return String.valueOf(id);
                            }
                        }
                        """));

        result.assertSuccess();

        // Drive the real describe() output, exactly as the descriptor-registration path
        // (JaxRsRouteRegistrar) would consume it.
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass("dev.vertique.test.E2eDescUnsupportedMarkedResource");
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();
        Class<?> descriptorClass =
                result.loadGeneratedClass("dev.vertique.test.E2eDescUnsupportedMarkedResource_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();
        Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        @SuppressWarnings("unchecked")
        List<ResourceMethodMeta> metas = (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());

        ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);

        boolean fallbackSuppliedAnnotation = false;
        for (Annotation a : paramMeta.annotationsLazy().get()) {
            if (a instanceof TestUnsupportedMemberMarker) {
                fallbackSuppliedAnnotation = true;
                break;
            }
        }
        assertTrue(
                fallbackSuppliedAnnotation,
                "The codegen descriptor ParamMeta must expose the unsupported-member annotation via "
                        + "annotationsLazy() through the reflective fallback");

        ParamConversionResolver resolver = ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of()), Set.of(new UnsupportedMemberSensitiveProvider()));
        ConversionContext codegenCtx = ConversionContexts.forParamMeta(paramMeta);
        Object codegenConverted = resolver.fromString("13", codegenCtx);

        Annotation[] reflectiveAnnotations = reflectiveParameterAnnotations(resourceClass);
        ParamConverter<?> reflectiveConverter = new UnsupportedMemberSensitiveProvider()
                .getConverter(TestNoNativeConverterType.class, TestNoNativeConverterType.class, reflectiveAnnotations);
        assertTrue(
                reflectiveConverter != null,
                "Reflective-scan baseline: the provider must match on the reflectively-read annotation array");
        @SuppressWarnings("unchecked")
        Object reflectiveConverted = ((ParamConverter<TestNoNativeConverterType>) reflectiveConverter).fromString("13");

        TestNoNativeConverterType codegenTyped = assertInstanceOf(
                TestNoNativeConverterType.class,
                codegenConverted,
                "The provider must have matched via the unsupported-member annotation materialized on the "
                        + "codegen descriptor path through the reflective fallback");
        assertEquals("desc:13", codegenTyped.value());
        assertEquals(
                ((TestNoNativeConverterType) reflectiveConverted).value(),
                codegenTyped.value(),
                "The codegen descriptor route's provider must produce the IDENTICAL converted result as the "
                        + "reflective-scan route — proving parity for the unsupported-member annotation");
    }
}
