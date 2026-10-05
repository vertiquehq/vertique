// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * Bean whose single field pairs an additive {@code @Sanitize} with {@code @SkipSanitization} —
     * the field-level shape of the IP-15 parameter conflict.
     */
    static class ConflictingBean {
        @QueryParam("q")
        @Sanitize(StubSanit.class)
        @SkipSanitization
        public String q;
    }

    /** Resource carrying a {@code @BeanParam} of the conflicting bean, scanned by the real scanner. */
    @Path("/conflicting-bean")
    static class ConflictingBeanResource {
        @GET
        public void search(@BeanParam ConflictingBean bean) {}
    }

    /** Bean with no field-level policies — inherits the {@code @BeanParam} parameter baseline. */
    static class PlainBean {
        @QueryParam("page")
        public String page;
    }

    /**
     * Resource whose {@code @BeanParam} parameter itself carries {@code @Sanitize} — the shape
     * issue #533 requires both engines to honour.
     */
    @Path("/bean-param-param-policy")
    static class BeanParamParameterSanitizedResource {
        @GET
        public void search(@BeanParam @Sanitize(StubSanit.class) PlainBean bean) {}
    }

    /** Sanitizer that prefixes {@code clean:} — used by real-engine regression probes. */
    static final class PrefixSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext ctx) {
            return value == null ? null : "clean:" + value;
        }
    }

    /** Type-level sanitizer that prefixes {@code clean:} (non-idempotent with {@link FieldPrefixSanitizer}). */
    static final class TypePrefixSanitizer implements Sanitizer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String sanitize(String value, InputValueContext ctx) {
            CALLS.incrementAndGet();
            return value == null ? null : "clean:" + value;
        }
    }

    /** Field-level sanitizer that prefixes {@code field:} (non-idempotent with {@link TypePrefixSanitizer}). */
    static final class FieldPrefixSanitizer implements Sanitizer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String sanitize(String value, InputValueContext ctx) {
            CALLS.incrementAndGet();
            return value == null ? null : "field:" + value;
        }
    }

    /** Canonicalizer that strips commas so a numeric field can convert after the wire string is normalized. */
    static final class StripCommasCanon implements Canonicalizer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String canonicalize(String value, InputValueContext ctx) {
            CALLS.incrementAndGet();
            return value == null ? null : value.replace(",", "");
        }
    }

    /** Bean with type-level and field-level {@code @Sanitize} — must compose once, not replay. */
    @Sanitize(TypePrefixSanitizer.class)
    static class TypeAndFieldSanitizedBean {
        @QueryParam("page")
        @Sanitize(FieldPrefixSanitizer.class)
        public String page;
    }

    @Path("/type-and-field-sanitized-bean")
    static class TypeAndFieldSanitizedBeanResource {
        @GET
        public void search(@BeanParam TypeAndFieldSanitizedBean bean) {}
    }

    /** Bean whose type-level canonicalizer must run on the wire string before int conversion. */
    @Canonicalize(StripCommasCanon.class)
    static class NumericCanonBean {
        @QueryParam("n")
        public int n;
    }

    @Path("/numeric-canon-bean")
    static class NumericCanonBeanResource {
        @GET
        public void search(@BeanParam NumericCanonBean bean) {}
    }

    /** Bean with a type-level {@code @Sanitize} and no field-level / parameter-level policy. */
    @Sanitize(PrefixSanitizer.class)
    static class TypeSanitizedBean {
        @QueryParam("page")
        public String page;
    }

    @Path("/type-sanitized-bean")
    static class TypeSanitizedBeanResource {
        @GET
        public void search(@BeanParam TypeSanitizedBean bean) {}
    }

    @Path("/dual-sanitize-then-skip")
    static class DualSanitizeThenSkipResource {
        @GET
        public void search(
                @BeanParam @Sanitize(PrefixSanitizer.class) PlainBean sanitized,
                @BeanParam @SkipSanitization PlainBean skipped) {}
    }

    @Path("/dual-skip-then-sanitize")
    static class DualSkipThenSanitizeResource {
        @GET
        public void search(
                @BeanParam @SkipSanitization PlainBean skipped,
                @BeanParam @Sanitize(PrefixSanitizer.class) PlainBean sanitized) {}
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
            // @SkipCanonicalization clears the composed per-field chain (shared owner skip rules).
            // With route sanit also empty, isEmpty()=true and extractScalarParam never reaches the
            // processor for the per-field call. There is no intermediate-map second walk.
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
    @DisplayName("pre-fix shape — BeanParamFieldMeta with annotations=null")
    class PreFixShape {

        @Test
        @DisplayName("annotations=null still applies Class field metadata via resolvePropertyPolicies")
        void canonicalizeField_annotationsNull_stillUsesClassMetadata() throws Exception {
            // Single-pass composition reads bean-type/field metadata from the Class, not from
            // ParamMeta.annotations(). Stripping annotations on the companion meta must not drop
            // the field's declared @Canonicalize — that would reintroduce a reflective/codegen split.
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithCanon.class, "name", false /* annotations stripped */, List.of(), List.of());

            assertTrue(captured.isPresent(), "Class field @Canonicalize must still reach the processor");
            assertEquals(List.of(StubCanon.class), captured.get().canonicalizers());
        }

        @Test
        @DisplayName("annotations=null composes route baseline with Class field metadata")
        void canonicalizeField_annotationsNull_composesRouteAndField() throws Exception {
            Optional<EffectiveInputPolicies> captured = invokeMaterializeBean(
                    BeanWithCanon.class,
                    "name",
                    false /* annotations stripped */,
                    List.of(RouteCanon.class),
                    List.of());

            assertTrue(captured.isPresent());
            assertEquals(
                    List.of(RouteCanon.class, StubCanon.class),
                    captured.get().canonicalizers(),
                    "baseline + field metadata compose once (route then field)");
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

    @Nested
    @DisplayName("startup rejection — a conflicting bean field fails when the route's extractor is built")
    class BeanFieldConflict {

        @Test
        @DisplayName("@Sanitize + @SkipSanitization on a bean field — ParameterExtractor construction throws")
        void conflictingBeanField_failsExtractorConstruction() {
            List<ResourceMethodMeta> metas =
                    new ResourceScanner(new SecurityPolicyBuilder()).scanResource(new ConflictingBeanResource());
            assertEquals(1, metas.size(), "expected exactly one discovered method");
            ResourceMethodMeta meta = metas.get(0);

            InvocationPolicyConflictException ex = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> new ParameterExtractor(
                            meta, List.of(), new RestContextResolution(Set.of()), newProcessorStub()),
                    "a conflicting @BeanParam field must be rejected when the route's extractor is built, "
                            + "not on the first request");
            assertEquals(PolicyAxis.SANITIZE, ex.axis(), "the conflict is on the SANITIZE axis");
            assertTrue(
                    ex.getMessage().contains("ConflictingBean.q"),
                    "message must name the conflicting field site: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("@BeanParam parameter policies — parameter-level @Sanitize applies on both paths (#533)")
    class BeanParamParameterPolicies {

        @Test
        @DisplayName("reflective: @BeanParam @Sanitize(X) with a field that declares nothing — field inherits [X]")
        void reflective_parameterSanitize_appliesToFieldsWithoutOwnPolicy() throws Exception {
            List<ResourceMethodMeta> metas = new ResourceScanner(new SecurityPolicyBuilder())
                    .scanResource(new BeanParamParameterSanitizedResource());
            assertEquals(1, metas.size());
            ResourceMethodMeta meta = metas.get(0);

            InputObjectProcessor processor = newProcessorStub();
            ParameterExtractor extractor =
                    new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);

            BoundRequest request = stubBoundRequestWithQuery("page", "value-to-process");
            RoutingContext ctx = mock(RoutingContext.class);
            Object[] args = extractor.extractArguments(ctx, request);
            assertNotNull(args[0]);
            assertTrue(PlainBean.class.isInstance(args[0]));

            Optional<EffectiveInputPolicies> fieldPolicies = capturePerFieldPolicies(processor, InputLocation.QUERY);
            assertTrue(fieldPolicies.isPresent(), "plain field must inherit the @BeanParam parameter sanitize chain");
            assertEquals(List.of(StubSanit.class), fieldPolicies.get().sanitizers());

            // PlainBean declares no object-level policies beyond the parameter baseline already
            // applied per-field; there is no intermediate-map second walk.
            Optional<EffectiveInputPolicies> beanPolicies =
                    capturePerFieldPolicies(processor, InputLocation.BEAN_PARAM);
            assertTrue(beanPolicies.isEmpty(), "intermediate map must not be re-submitted after per-field processing");
        }

        @Test
        @DisplayName("codegen materializeBean: same baseline as reflective — [StubSanit] reaches the field")
        void codegen_parameterSanitize_baselineMatchesReflective() throws Exception {
            // Direct materializeBean call with the parameter's resolved policies (what POL_i carries).
            EffectiveInputPolicies paramPolicies = new EffectiveInputPolicies(List.of(), List.of(StubSanit.class));
            Field f = PlainBean.class.getDeclaredField("page");
            ParamMeta pm = new ParamMeta("page", ParamSource.QUERY, String.class, null, null, null, f.getAnnotations());
            BeanParamFieldMeta[] fields = new BeanParamFieldMeta[] {new BeanParamFieldMeta("page", pm)};

            ResourceMethodMeta meta = stubMeta(List.of(), List.of());
            InputObjectProcessor processor = newProcessorStub();
            ParameterExtractor extractor =
                    new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);

            BoundRequest request = stubBoundRequestWithQuery("page", "value-to-process");
            RoutingContext ctx = mock(RoutingContext.class);
            Object result = extractor.materializeBean(fields, paramPolicies, request, ctx, PlainBean.class);
            assertNotNull(result);

            Optional<EffectiveInputPolicies> fieldPolicies = capturePerFieldPolicies(processor, InputLocation.QUERY);
            assertTrue(fieldPolicies.isPresent());
            assertEquals(List.of(StubSanit.class), fieldPolicies.get().sanitizers());

            Optional<EffectiveInputPolicies> beanPolicies =
                    capturePerFieldPolicies(processor, InputLocation.BEAN_PARAM);
            assertTrue(beanPolicies.isEmpty(), "no second map-stage processInput call");
        }
    }

    @Nested
    @DisplayName("real-engine regressions — cache isolation and type-only metadata (#533 review)")
    class RealEngineRegressions {

        @BeforeEach
        void resetCounters() {
            TypePrefixSanitizer.CALLS.set(0);
            FieldPrefixSanitizer.CALLS.set(0);
            StripCommasCanon.CALLS.set(0);
        }

        @Test
        @DisplayName("two same-class @BeanParam baselines stay isolated (sanitize then skip)")
        void dualBeanParams_sanitizeThenSkip_isolated() throws Exception {
            Object[] args = extractWithRealEngine(new DualSanitizeThenSkipResource(), "page", "raw");
            PlainBean sanitized = (PlainBean) args[0];
            PlainBean skipped = (PlainBean) args[1];
            assertEquals("clean:raw", sanitized.page, "first parameter's @Sanitize must apply");
            assertEquals("raw", skipped.page, "second parameter's @SkipSanitization must not reuse the first baseline");
        }

        @Test
        @DisplayName("two same-class @BeanParam baselines stay isolated (skip then sanitize)")
        void dualBeanParams_skipThenSanitize_isolated() throws Exception {
            Object[] args = extractWithRealEngine(new DualSkipThenSanitizeResource(), "page", "raw");
            PlainBean skipped = (PlainBean) args[0];
            PlainBean sanitized = (PlainBean) args[1];
            assertEquals("raw", skipped.page, "first parameter's @SkipSanitization must apply");
            assertEquals("clean:raw", sanitized.page, "second parameter's @Sanitize must not reuse the first baseline");
        }

        @Test
        @DisplayName("type-only @Sanitize still runs when the @BeanParam parameter has an empty invocation chain")
        void typeOnlySanitize_emptyInvocationBaseline_stillProcesses() throws Exception {
            Object[] args = extractWithRealEngine(new TypeSanitizedBeanResource(), "page", "raw");
            TypeSanitizedBean bean = (TypeSanitizedBean) args[0];
            assertEquals("clean:raw", bean.page, "bean-type @Sanitize must run even with EffectiveInputPolicies.NONE");
        }

        @Test
        @DisplayName("reflective: type+field @Sanitize compose once — order and call count")
        void typeAndFieldSanitize_reflective_composeOnce_orderAndCount() throws Exception {
            Object[] args = extractWithRealEngine(new TypeAndFieldSanitizedBeanResource(), "page", "raw");
            TypeAndFieldSanitizedBean bean = (TypeAndFieldSanitizedBean) args[0];
            // TypePrefix then FieldPrefix exactly once: field:clean:raw — not field:clean:field:raw.
            assertEquals(
                    "field:clean:raw",
                    bean.page,
                    "type then field must compose once; replaying the map walk double-applies the field sanitizer");
            assertEquals(1, TypePrefixSanitizer.CALLS.get(), "type sanitizer must run exactly once");
            assertEquals(1, FieldPrefixSanitizer.CALLS.get(), "field sanitizer must run exactly once");
        }

        @Test
        @DisplayName("codegen materializeBean: type+field @Sanitize compose once — order and call count")
        void typeAndFieldSanitize_codegenMaterializeBean_composeOnce_orderAndCount() throws Exception {
            Field f = TypeAndFieldSanitizedBean.class.getDeclaredField("page");
            ParamMeta pm = new ParamMeta("page", ParamSource.QUERY, String.class, null, null, null, f.getAnnotations());
            BeanParamFieldMeta[] fields = new BeanParamFieldMeta[] {new BeanParamFieldMeta("page", pm)};

            ResourceMethodMeta meta = stubMeta(List.of(), List.of());
            InputObjectProcessor processor = realProcessor();
            ParameterExtractor extractor =
                    new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), processor);

            BoundRequest request = stubBoundRequestWithQuery("page", "raw");
            Object result = extractor.materializeBean(
                    fields,
                    EffectiveInputPolicies.NONE,
                    request,
                    mock(RoutingContext.class),
                    TypeAndFieldSanitizedBean.class);

            assertEquals("field:clean:raw", ((TypeAndFieldSanitizedBean) result).page);
            assertEquals(1, TypePrefixSanitizer.CALLS.get(), "type sanitizer must run exactly once on codegen path");
            assertEquals(1, FieldPrefixSanitizer.CALLS.get(), "field sanitizer must run exactly once on codegen path");
        }

        @Test
        @DisplayName("type-level @Canonicalize runs on the wire string before numeric conversion")
        void typeCanonicalize_numericField_beforeConversion() throws Exception {
            Object[] args = extractWithRealEngine(new NumericCanonBeanResource(), "n", "1,234");
            NumericCanonBean bean = (NumericCanonBean) args[0];
            assertEquals(1234, bean.n, "commas must be stripped before int conversion");
            assertEquals(1, StripCommasCanon.CALLS.get(), "canonicalizer must run exactly once before conversion");
        }

        @Test
        @DisplayName("policy-free control bean is unchanged when no type or invocation chain is declared")
        void policyFreeControl_unchanged() throws Exception {
            @Path("/plain")
            class PlainResource {
                @GET
                public void search(@BeanParam PlainBean bean) {}
            }
            Object[] args = extractWithRealEngine(new PlainResource(), "page", "raw");
            PlainBean bean = (PlainBean) args[0];
            assertEquals("raw", bean.page);
        }

        private static InputObjectProcessor realProcessor() {
            return InputObjectProcessor.createDefault(
                    type -> {
                        if (type == StripCommasCanon.class) {
                            return new StripCommasCanon();
                        }
                        throw new AssertionError("no canonicalizer declared: " + type);
                    },
                    type -> {
                        if (type == PrefixSanitizer.class) {
                            return new PrefixSanitizer();
                        }
                        if (type == TypePrefixSanitizer.class) {
                            return new TypePrefixSanitizer();
                        }
                        if (type == FieldPrefixSanitizer.class) {
                            return new FieldPrefixSanitizer();
                        }
                        throw new AssertionError("unresolvable sanitizer: " + type);
                    });
        }

        private static Object[] extractWithRealEngine(Object resource, String queryName, String queryValue) {
            List<ResourceMethodMeta> metas = new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource);
            assertEquals(1, metas.size());
            ResourceMethodMeta meta = metas.get(0);
            ParameterExtractor extractor =
                    new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()), realProcessor());
            BoundRequest request = stubBoundRequestWithQuery(queryName, queryValue);
            return extractor.extractArguments(mock(RoutingContext.class), request);
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
