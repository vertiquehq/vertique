// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end parity test for the bean-param field input-policy fix (HIGH 1 — round-4 review).
 *
 * <p>Drives {@link ParameterExtractor#materializeBean} and {@link ParameterExtractor#extractArguments}
 * with a mocked {@link InputObjectProcessor} so the assertions observe what production code
 * actually passes through, not a re-implementation of {@code resolveParamPolicies}.
 *
 * <p>Production behaviour relevant to the assertions: when the resolved per-field
 * {@link EffectiveInputPolicies} has empty canonicalizer + sanitizer chains
 * ({@link EffectiveInputPolicies#isEmpty()} returns {@code true}),
 * {@code extractScalarParam} skips the processor call entirely. So the visible difference between
 * "field chain is empty" and "field chain has policy X" is whether the processor was invoked at
 * all for that field's {@link InputLocation}.
 */
class BeanParamFieldPolicyParityTest {

    // --- Stub policy implementations ---

    /** Stub {@link Canonicalizer} used as a policy marker. */
    static final class StubCanon implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext ctx) {
            return value;
        }
    }

    /** Stub {@link Sanitizer} used as a policy marker. */
    static final class StubSanit implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext ctx) {
            return value;
        }
    }

    /** Distinct route-level canonicalizer to prove route baseline pass-through. */
    static final class RouteCanon implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext ctx) {
            return value;
        }
    }

    // --- Bean fixtures ---

    static class BeanWithCanon {
        @QueryParam("name")
        @Canonicalize(StubCanon.class)
        public String name;
    }

    static class BeanWithSanit {
        @QueryParam("tag")
        @Sanitize(StubSanit.class)
        public String tag;
    }

    static class BeanWithSkipCanon {
        @QueryParam("q")
        @SkipCanonicalization
        public String q;
    }

    static class BeanNoPolicies {
        @QueryParam("page")
        public String page;
    }

    // --- Tests ---

    @Nested
    @DisplayName("codegen path — BeanParamFieldMeta with annotations populated")
    class CodegenPath {

        @Test
        @DisplayName("@Canonicalize field — InputObjectProcessor receives [StubCanon] in canonicalizer chain")
        void canonicalizeField_codegenPath_appliesFieldCanon() throws Exception {
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithCanon.class, "name", true /* with annotations */, List.of(), List.of());

            assertTrue(captured.isPresent(), "Per-field processor invocation must occur for @Canonicalize");
            assertEquals(
                    List.of(StubCanon.class),
                    captured.get().canonicalizers(),
                    "Codegen path with field @Canonicalize must surface [StubCanon] to InputObjectProcessor");
            assertEquals(List.of(), captured.get().sanitizers(), "No sanitizer declared");
        }

        @Test
        @DisplayName("@Sanitize field — InputObjectProcessor receives [StubSanit] in sanitizer chain")
        void sanitizeField_codegenPath_appliesFieldSanit() throws Exception {
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithSanit.class, "tag", true /* with annotations */, List.of(), List.of());

            assertTrue(captured.isPresent(), "Per-field processor invocation must occur for @Sanitize");
            assertEquals(List.of(), captured.get().canonicalizers(), "No canonicalizer declared on the field");
            assertEquals(
                    List.of(StubSanit.class),
                    captured.get().sanitizers(),
                    "Codegen path with field @Sanitize must surface [StubSanit] to InputObjectProcessor");
        }

        @Test
        @DisplayName("@SkipCanonicalization field with non-empty route chain — processor NOT invoked for the field")
        void skipCanonField_codegenPath_clearsRouteChain() throws Exception {
            // @SkipCanonicalization must clear the per-field chain. With route sanit also empty,
            // isEmpty()=true and extractScalarParam never reaches the processor for the
            // per-field call. (The route-level BEAN_PARAM intermediate-map call still happens because
            // routePolicies has a non-empty canon chain — that's a separate invocation we filter out.)
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithSkipCanon.class, "q", true /* with annotations */, List.of(RouteCanon.class), List.of());

            assertTrue(
                    captured.isEmpty(),
                    "@SkipCanonicalization must clear the per-field chain; processor must NOT be "
                            + "invoked for the per-field scalar (chain is empty after clearing)");
        }

        @Test
        @DisplayName("no field annotations — InputObjectProcessor receives the route baseline unchanged")
        void noFieldAnnotations_inheritsRouteBaseline() throws Exception {
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanNoPolicies.class, "page", true /* with annotations */, List.of(RouteCanon.class), List.of());

            assertTrue(captured.isPresent(), "Per-field processor invocation must occur (route chain is non-empty)");
            assertEquals(
                    List.of(RouteCanon.class),
                    captured.get().canonicalizers(),
                    "Field with no input-policy annotations must inherit the route canonicalizer chain");
        }
    }

    @Nested
    @DisplayName("pre-fix shape — BeanParamFieldMeta with annotations=null (regression pin)")
    class PreFixShape {

        @Test
        @DisplayName("@Canonicalize field but annotations=null — processor NOT invoked for the field")
        void canonicalizeField_butAnnotationsNull_dropsFieldCanon() throws Exception {
            // Pre-fix shape: BeanParamFieldMeta carries annotations=null. The field-level @Canonicalize
            // cannot be derived from a null annotation array. The per-field policies fall back to
            // the route baseline (empty here), and extractScalarParam skips the processor.
            // If a regression ever reverted the round-4 fix back to passing empty per-field policies,
            // this assertion would still hold — but the codegenPath assertions above would FAIL,
            // which is the actual regression signal. This test is a complementary sanity pin: when
            // annotations are absent, NO field-level policy is applied.
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithCanon.class, "name", false /* annotations stripped */, List.of(), List.of());

            assertTrue(
                    captured.isEmpty(),
                    "When BeanParamFieldMeta.meta().annotations() is null and route chain is empty, "
                            + "the per-field processor invocation must not happen — proving the "
                            + "@Canonicalize annotation cannot leak through despite being on the bean class");
        }

        @Test
        @DisplayName("@Canonicalize field but annotations=null — route chain is preserved (only field-level dropped)")
        void canonicalizeField_butAnnotationsNull_preservesRouteChain() throws Exception {
            // With route chain non-empty AND annotations=null, the per-field policies degrade to the
            // route baseline only — no [StubCanon] from the bean's @Canonicalize. The route chain still
            // applies (the codegen fix is precisely about preserving field-level policies when present;
            // when absent, route baseline propagates as-is).
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithCanon.class,
                    "name",
                    false /* annotations stripped */,
                    List.of(RouteCanon.class),
                    List.of());

            assertTrue(captured.isPresent(), "Per-field processor invocation must occur (route chain is non-empty)");
            assertEquals(
                    List.of(RouteCanon.class),
                    captured.get().canonicalizers(),
                    "Per-field chain must equal the route baseline (NOT contain [StubCanon] — annotations are null)");
        }
    }

    @Nested
    @DisplayName("reflective vs codegen parity — same bean class produces same captured policies")
    class ReflectiveParity {

        @Test
        @DisplayName(
                "reflective extractBeanParam and codegen materializeBean produce identical chain for @Canonicalize")
        void reflectiveAndCodegen_sameCapturedChain() throws Exception {
            Optional<EffectiveInputPolicies> codegenCaptured =
                    invokeMaterializeBean(BeanWithCanon.class, "name", true, List.of(), List.of());

            Optional<EffectiveInputPolicies> reflectiveCaptured =
                    invokeExtractBeanParam(BeanWithCanon.class, "name", List.of(), List.of());

            assertTrue(codegenCaptured.isPresent(), "Codegen path must invoke processor for @Canonicalize");
            assertTrue(reflectiveCaptured.isPresent(), "Reflective path must invoke processor for @Canonicalize");
            assertEquals(
                    codegenCaptured.get().canonicalizers(),
                    reflectiveCaptured.get().canonicalizers(),
                    "Reflective and codegen paths must produce identical canonicalizer chains");
            assertEquals(
                    codegenCaptured.get().sanitizers(),
                    reflectiveCaptured.get().sanitizers(),
                    "Reflective and codegen paths must produce identical sanitizer chains");
        }
    }

    // --- Helpers ---

    /**
     * Invokes {@link ParameterExtractor#materializeBean} with a single-field
     * {@code BeanParamFieldMeta[]} for {@code beanClass.fieldName}; returns the
     * {@link EffectiveInputPolicies} the {@link InputObjectProcessor} was called with for the
     * per-field scalar value, or {@link Optional#empty()} if the processor was not invoked for
     * that field's location (e.g. because the resolved chain was empty).
     *
     * @param beanClass       the bean type whose field we extract
     * @param fieldName       the field name (also the JAX-RS query param name)
     * @param withAnnotations whether to populate {@code annotations} on the {@link ParamMeta}
     * @param routeCanon      route-level canonicalizer chain
     * @param routeSanit      route-level sanitizer chain
     */
    private static Optional<EffectiveInputPolicies> invokeMaterializeBean(
            Class<?> beanClass,
            String fieldName,
            boolean withAnnotations,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit)
            throws Exception {
        Field f = beanClass.getDeclaredField(fieldName);
        Annotation[] annotations = withAnnotations ? f.getAnnotations() : null;
        ParamMeta pm = new ParamMeta(fieldName, ParamSource.QUERY, String.class, null, null, null, annotations);
        BeanParamFieldMeta[] fields = new BeanParamFieldMeta[] {new BeanParamFieldMeta(fieldName, pm)};

        ResourceMethodMeta meta = stubMeta(routeCanon, routeSanit);
        InputObjectProcessor processor = newProcessorStub();
        ParameterExtractor extractor =
                new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);

        BoundRequest request = stubBoundRequestWithQuery(fieldName, "value-to-process");
        RoutingContext ctx = mock(RoutingContext.class);

        EffectiveInputPolicies routePolicies = new EffectiveInputPolicies(routeCanon, routeSanit);

        Object result = extractor.materializeBean(fields, routePolicies, request, ctx, beanClass);
        assertNotNull(result, "materializeBean must produce a bean instance");

        return capturePerFieldPolicies(processor, InputLocation.QUERY);
    }

    /**
     * Invokes the reflective bean-param extraction path ({@code extractBeanParam} via
     * {@code extractArguments}) and returns the per-field {@link EffectiveInputPolicies}, or
     * {@link Optional#empty()} if the processor was not invoked for that field's location.
     *
     * <p>This goes through {@code computeBeanFields → resolveFieldParam}, which populates
     * {@code ParamMeta.annotations()} from {@code Field.getAnnotations()} — proving the reflective
     * path produces the same annotation array the codegen path does.
     */
    private static Optional<EffectiveInputPolicies> invokeExtractBeanParam(
            Class<?> beanClass,
            String fieldName,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit)
            throws Exception {
        ParamMeta beanParam =
                new ParamMeta(fieldName, ParamSource.BEAN_PARAM, beanClass, null, null, null, new Annotation[0]);
        ResourceMethodMeta meta = stubMetaWithParam(beanParam, routeCanon, routeSanit);

        InputObjectProcessor processor = newProcessorStub();
        ParameterExtractor extractor =
                new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);

        BoundRequest request = stubBoundRequestWithQuery(fieldName, "value-to-process");
        RoutingContext ctx = mock(RoutingContext.class);

        Object[] args = extractor.extractArguments(ctx, request);
        assertNotNull(args[0], "extractArguments must populate the bean-param slot");
        assertTrue(beanClass.isInstance(args[0]), "Result must be an instance of the bean type");

        return capturePerFieldPolicies(processor, InputLocation.QUERY);
    }

    /**
     * Returns the {@link EffectiveInputPolicies} that the {@link InputObjectProcessor} was called
     * with for the given target {@link InputLocation}, or {@link Optional#empty()} if it was never
     * called for that location. Filtering by {@code InputLocation} is unambiguous because
     * {@code BEAN_PARAM} (the route-level intermediate-map call) and the per-field locations
     * (QUERY/PATH/HEADER/COOKIE/FORM) cannot overlap on the same logical step.
     */
    private static Optional<EffectiveInputPolicies> capturePerFieldPolicies(
            InputObjectProcessor processor, InputLocation target) {
        ArgumentCaptor<EffectiveInputPolicies> policyCaptor = ArgumentCaptor.forClass(EffectiveInputPolicies.class);
        ArgumentCaptor<InputLocation> locationCaptor = ArgumentCaptor.forClass(InputLocation.class);
        // verify at least 0 — accommodates the case where the processor was never invoked at all.
        verify(processor, atLeast(0))
                .processInput(any(), any(), policyCaptor.capture(), locationCaptor.capture(), any());
        List<EffectiveInputPolicies> allPolicies = policyCaptor.getAllValues();
        List<InputLocation> allLocations = locationCaptor.getAllValues();
        for (int i = 0; i < allLocations.size(); i++) {
            if (allLocations.get(i) == target) {
                return Optional.of(allPolicies.get(i));
            }
        }
        // Sanity: if processor was never invoked at all and target was the per-field location, the
        // per-field chain was empty; if target was BEAN_PARAM, the route chain was empty.
        if (allLocations.isEmpty()) {
            verify(processor, never())
                    .processInput(
                            any(),
                            any(),
                            any(EffectiveInputPolicies.class),
                            any(InputLocation.class),
                            any(InputFieldNameResolver.class));
        }
        return Optional.empty();
    }

    private static InputObjectProcessor newProcessorStub() {
        InputObjectProcessor processor = mock(InputObjectProcessor.class);
        when(processor.processInput(
                        any(),
                        any(),
                        any(EffectiveInputPolicies.class),
                        any(InputLocation.class),
                        any(InputFieldNameResolver.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return processor;
    }

    private static ResourceMethodMeta stubMeta(
            List<Class<? extends Canonicalizer>> routeCanon, List<Class<? extends Sanitizer>> routeSanit) {
        ResourceMethodMeta meta = mock(ResourceMethodMeta.class);
        when(meta.params()).thenReturn(List.of());
        when(meta.routeCanonicalizerChain()).thenReturn(routeCanon);
        when(meta.routeSanitizerChain()).thenReturn(routeSanit);
        when(meta.operationId()).thenReturn("test-op");
        return meta;
    }

    private static ResourceMethodMeta stubMetaWithParam(
            ParamMeta param,
            List<Class<? extends Canonicalizer>> routeCanon,
            List<Class<? extends Sanitizer>> routeSanit) {
        ResourceMethodMeta meta = mock(ResourceMethodMeta.class);
        when(meta.params()).thenReturn(List.of(param));
        when(meta.routeCanonicalizerChain()).thenReturn(routeCanon);
        when(meta.routeSanitizerChain()).thenReturn(routeSanit);
        when(meta.operationId()).thenReturn("test-op");
        // ParameterExtractor's constructor now derives cachedParamPolicies through
        // ReflectiveInvocationPolicies.resolveParameter(meta.method(), i, route), which
        // dereferences the Method. A single unstubbed param leaves method() returning Mockito's
        // default null and NPEs at construction, so stub it with a real single-parameter Method
        // from this fixture (its own parameter carries no policy annotations, so resolution is a
        // pure route-baseline pass-through).
        when(meta.method()).thenReturn(policySourceMethod());
        return meta;
    }

    /**
     * A real, single-parameter {@link Method} used only to satisfy
     * {@link ResourceMethodMeta#method()} in {@link #stubMetaWithParam}; see that method's comment.
     */
    private static Method policySourceMethod() {
        try {
            return BeanParamFieldPolicyParityTest.class.getDeclaredMethod("policySourceMethodTarget", Object.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unused")
    private static void policySourceMethodTarget(Object ignored) {}

    /**
     * Builds a {@link BoundRequest} stub exposing a single query parameter bound to a
     * {@link RequestValue}, for driving both the reflective {@code extractArguments(ctx, boundRequest)}
     * path and {@code materializeBean(..., boundRequest, ...)}. All other parameter maps are empty and
     * the body wraps {@code null}.
     */
    private static BoundRequest stubBoundRequestWithQuery(String name, String value) {
        Map<String, RequestValue> query = Map.of(name, RequestValue.of(value));
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return query;
            }

            @Override
            public Map<String, RequestValue> headers() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return Map.of();
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
}
