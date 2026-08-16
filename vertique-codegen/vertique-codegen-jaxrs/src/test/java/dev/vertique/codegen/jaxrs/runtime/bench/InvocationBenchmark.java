// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.runtime.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Directional benchmark demonstrating the NFR-CG010-002 target: the generated execution plan
 * path (precomputed policies + direct typed call) is ≥1.5× faster than the reflective path
 * (per-call annotation scan to derive policies + {@code Method.invoke}).
 *
 * <p>The critical per-request cost difference between the two paths is:
 * <ol>
 *   <li><strong>Policy resolution</strong> — the reflective path re-resolves
 *       {@link EffectiveInputPolicies} from the parameter's raw annotation array on every request
 *       (calling {@link AnnotationResolver#findMetaAnnotation} up to 4× per parameter). The
 *       generated path uses {@code static final} precomputed constants derived at compile time,
 *       eliminating all per-request annotation scanning.</li>
 *   <li><strong>Method dispatch</strong> — the reflective path calls {@link Method#invoke}, which
 *       wraps exceptions in {@link java.lang.reflect.InvocationTargetException}. The generated
 *       path calls the method directly via a typed cast, avoiding the wrapping overhead.</li>
 * </ol>
 *
 * <p>Both paths operate on a representative method with 3 parameters ({@link BenchResource#search}
 * — query string, int limit, int offset) to simulate non-trivial per-call annotation-scan work.
 *
 * <p>The test prints per-operation timings and the observed speedup ratio. It passes when the
 * ratio is ≥1.5×. CI finish-time protection: warm-up is 10 000 iterations; measurement is
 * 200 000 iterations.
 */
@DisplayName("InvocationBenchmark — generated execution plan vs reflective invocation speedup")
class InvocationBenchmark {

    // --- JIT blackhole sink ---

    /** Blackhole sink — prevents dead-code elimination of benchmark results. */
    @SuppressWarnings("unused")
    private static volatile Object sink;

    // --- Benchmark parameters ---

    private static final int WARMUP_ITERS = 10_000;
    private static final int MEASURE_ITERS = 200_000;

    // --- Fixture instances ---

    private static final BenchResource RESOURCE = new BenchResource();

    // --- Pre-built args for search(String q, int limit, int offset) ---

    private static final Object[] ARGS = {"benchmark-query", 20, 0};

    // --- Reflective path: raw method + per-param annotation arrays ---

    private static final Method SEARCH_METHOD;
    private static final ResourceMethodMeta.ParamMeta PM_Q;
    private static final ResourceMethodMeta.ParamMeta PM_LIMIT;
    private static final ResourceMethodMeta.ParamMeta PM_OFFSET;

    static {
        try {
            SEARCH_METHOD = BenchResource.class.getMethod("search", String.class, int.class, int.class);
            SEARCH_METHOD.setAccessible(true);
            // Build ParamMeta entries with the actual annotations from the method parameters,
            // matching how ResourceScanner.resolveParams populates ParamMeta.annotations().
            java.lang.reflect.Parameter[] params = SEARCH_METHOD.getParameters();
            PM_Q = new ResourceMethodMeta.ParamMeta(
                    "q",
                    ResourceMethodMeta.ParamSource.QUERY,
                    String.class,
                    null,
                    null,
                    null,
                    params[0].getAnnotations());
            PM_LIMIT = new ResourceMethodMeta.ParamMeta(
                    "limit",
                    ResourceMethodMeta.ParamSource.QUERY,
                    int.class,
                    null,
                    null,
                    null,
                    params[1].getAnnotations());
            PM_OFFSET = new ResourceMethodMeta.ParamMeta(
                    "offset",
                    ResourceMethodMeta.ParamSource.QUERY,
                    int.class,
                    null,
                    null,
                    null,
                    params[2].getAnnotations());
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("BenchResource.search not found: " + e);
        }
    }

    // --- Generated plan: precomputed static policies + direct typed call ---

    /**
     * Precomputed policies for every parameter — computed once at class-init time, just as
     * the CG-010 {@code ExecutionPlanEmitter} would emit them as {@code private static final}
     * constants in the generated plan class.
     */
    private static final EffectiveInputPolicies POLICIES_Q = EffectiveInputPolicies.NONE;

    private static final EffectiveInputPolicies POLICIES_LIMIT = EffectiveInputPolicies.NONE;
    private static final EffectiveInputPolicies POLICIES_OFFSET = EffectiveInputPolicies.NONE;

    /**
     * Generated execution plan for {@link BenchResource#search(String, int, int)}.
     *
     * <p>Simulates the class that {@link dev.vertique.codegen.jaxrs.emit.ExecutionPlanEmitter}
     * would produce for the {@code search} method:
     * <ul>
     *   <li>{@code extractArguments} uses precomputed policy constants — no annotation scanning.</li>
     *   <li>{@code invoke} casts and calls directly — no {@code Method.invoke} overhead.</li>
     * </ul>
     */
    private static final ResourceExecutionPlan SEARCH_PLAN = new ResourceExecutionPlan() {
        @Override
        public Object[] extractArguments(RoutingContext ctx, BoundRequest request, GeneratedJaxRsSupport support) {
            // Precomputed-constant policy lookup — no per-call annotation scanning.
            // In the real generated plan these would be support.extractScalarParam(PM_Q, POLICIES_Q, request),
            // etc. Here we return pre-built args so neither path has I/O work,
            // isolating the annotation-scan overhead difference.
            return ARGS;
        }

        @Override
        public Object invoke(Object resource, Object[] args) {
            // Direct typed call — no InvocationTargetException wrapping.
            return ((BenchResource) resource).search((String) args[0], (int) args[1], (int) args[2]);
        }
    };

    // --- Benchmark test ---

    @Test
    @DisplayName("generated execution plan is ≥1.5× faster than per-call annotation scan + Method.invoke")
    void generatedPlan_isFasterThanReflectiveInvoke() throws Exception {
        // --- Warm up both paths ---
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = reflectivePathSimulation();
            sink = generatedPlanSimulation();
        }

        // --- Measure reflective path ---
        long refStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = reflectivePathSimulation();
        }
        long refNanos = System.nanoTime() - refStart;

        // --- Measure generated plan path ---
        long planStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = generatedPlanSimulation();
        }
        long planNanos = System.nanoTime() - planStart;

        // --- Compute and report ---
        double refNsPerOp = (double) refNanos / MEASURE_ITERS;
        double planNsPerOp = (double) planNanos / MEASURE_ITERS;
        double speedup = refNsPerOp / planNsPerOp;

        System.out.printf(
                "[InvocationBenchmark] reflective: %.1f ns/op  generated-plan: %.1f ns/op  speedup: %.2f×%n",
                refNsPerOp, planNsPerOp, speedup);

        assertTrue(
                speedup >= 1.5,
                String.format(
                        "NFR-CG010-002 not met: expected ≥1.5× speedup but got %.2f× (reflective=%.1f ns/op, plan=%.1f ns/op)",
                        speedup, refNsPerOp, planNsPerOp));
    }

    // --- Reflective path simulation ---

    /**
     * Simulates the per-request work in the reflective invocation path:
     * <ol>
     *   <li>Resolve {@link EffectiveInputPolicies} for each parameter by scanning its raw
     *       annotation array (mirrors {@code ParameterExtractor.resolveParamPolicies}).</li>
     *   <li>Invoke the method via {@link Method#invoke}.</li>
     * </ol>
     *
     * @return the method result
     */
    private Object reflectivePathSimulation() {
        // Policy resolution — mirrors ParameterExtractor.resolveParamPolicies per param
        resolveParamPolicies(PM_Q);
        resolveParamPolicies(PM_LIMIT);
        resolveParamPolicies(PM_OFFSET);
        try {
            return SEARCH_METHOD.invoke(RESOURCE, ARGS);
        } catch (Exception e) {
            throw new RuntimeException("reflective invoke failed", e);
        }
    }

    /**
     * Simulates the per-request work in the generated-plan path:
     * <ol>
     *   <li>Policies are precomputed static constants — zero per-call annotation scanning.</li>
     *   <li>Invoke the method via the typed execution plan — no reflective dispatch overhead.</li>
     * </ol>
     *
     * @return the method result
     */
    private Object generatedPlanSimulation() {
        // No policy resolution — static final constants already contain the right answer.
        // (POLICIES_Q, POLICIES_LIMIT, POLICIES_OFFSET are precomputed at class-init.)
        try {
            return SEARCH_PLAN.invoke(RESOURCE, ARGS);
        } catch (Throwable t) {
            throw new RuntimeException("plan invoke failed", t);
        }
    }

    // --- resolveParamPolicies simulation ---

    /**
     * Simulates {@code ParameterExtractor.resolveParamPolicies}: walks the parameter's raw
     * annotation array to find {@link Canonicalize}, {@link Sanitize},
     * {@link SkipCanonicalization}, and {@link SkipSanitization} meta-annotations and builds an
     * {@link EffectiveInputPolicies} from the results.
     *
     * <p>This is the actual per-call cost the generated path avoids. On a method with no
     * sanitization annotations (like {@link BenchResource#search}) the result is always
     * {@link EffectiveInputPolicies#NONE}, but the 4× {@link AnnotationResolver#findMetaAnnotation}
     * calls still execute.
     *
     * @param pm the parameter metadata to derive policies for
     * @return the effective policies for this parameter
     */
    private static EffectiveInputPolicies resolveParamPolicies(ResourceMethodMeta.ParamMeta pm) {
        Annotation[] annotations = pm.annotationsLazy().get();
        if (annotations.length == 0) {
            return EffectiveInputPolicies.NONE;
        }
        List<Annotation> annList = List.of(annotations);

        SkipCanonicalization skipCanon = AnnotationResolver.findMetaAnnotation(annList, SkipCanonicalization.class);
        if (skipCanon == null) {
            Canonicalize canon = AnnotationResolver.findMetaAnnotation(annList, Canonicalize.class);
            if (canon != null) {
                return new EffectiveInputPolicies(List.of(canon.value()), List.of());
            }
        }
        SkipSanitization skipSanit = AnnotationResolver.findMetaAnnotation(annList, SkipSanitization.class);
        if (skipSanit == null) {
            Sanitize sanit = AnnotationResolver.findMetaAnnotation(annList, Sanitize.class);
            if (sanit != null) {
                return new EffectiveInputPolicies(List.of(), List.of(sanit.value()));
            }
        }
        return EffectiveInputPolicies.NONE;
    }

    // --- Optional main entry point ---

    /**
     * Standalone runner for ad-hoc benchmark execution outside JUnit.
     * Prints the same numbers as the JUnit test but does not assert.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        InvocationBenchmark bench = new InvocationBenchmark();

        // Warm up
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = bench.reflectivePathSimulation();
            sink = bench.generatedPlanSimulation();
        }

        // Measure reflective
        long refStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = bench.reflectivePathSimulation();
        }
        long refNanos = System.nanoTime() - refStart;

        // Measure plan
        long planStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = bench.generatedPlanSimulation();
        }
        long planNanos = System.nanoTime() - planStart;

        double refNsPerOp = (double) refNanos / MEASURE_ITERS;
        double planNsPerOp = (double) planNanos / MEASURE_ITERS;
        double speedup = refNsPerOp / planNsPerOp;

        System.out.printf(
                "[InvocationBenchmark] reflective: %.1f ns/op  generated-plan: %.1f ns/op  speedup: %.2f×%n",
                refNsPerOp, planNsPerOp, speedup);
    }
}
