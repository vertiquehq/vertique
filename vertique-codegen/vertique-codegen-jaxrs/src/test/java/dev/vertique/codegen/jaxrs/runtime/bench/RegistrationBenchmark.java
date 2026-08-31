// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.runtime.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Directional benchmark demonstrating the NFR-CG010-001 target: generated-descriptor path
 * completes JAX-RS resource registration ≥2× faster than the reflective walk.
 *
 * <p>Two measurements are made over a hot JVM loop:
 * <ol>
 *   <li><strong>Generated path</strong> — calls
 *       {@link GeneratedJaxRsResourceDescriptor#describe(Object, GeneratedJaxRsDescriptorSupport, List)}
 *       on the {@link BenchResource_JaxRsDescriptor} companion directly, simulating the
 *       {@link GeneratedJaxRsDescriptorRegistry} fast path that {@code ResourceScanner} takes when
 *       a companion is present.</li>
 *   <li><strong>Reflective path</strong> — walks {@link BenchResource}'s declared methods via
 *       {@link Class#getDeclaredMethods()}, inspects JAX-RS annotations, and classifies parameters,
 *       simulating what the bare reflective {@code ResourceScanner} does when no companion
 *       is on the classpath.</li>
 * </ol>
 *
 * <p>The test prints per-operation timings and the observed speedup ratio. It passes when the
 * ratio is ≥2×. CI finish-time protection: warm-up is 10 000 iterations; measurement is 100 000
 * iterations — well within the 10 s target.
 *
 * <p>The class name deliberately does not match Surefire's default include patterns, so it is
 * excluded from {@code test}, {@code verify}, and CI regardless of the module it lives in. Run it
 * manually via its {@link #main(String[])} entry point, or with {@code ./mvnw -ntp test
 * -Dtest=RegistrationBenchmark -pl vertique-codegen/vertique-codegen-jaxrs} (which forces Surefire
 * to select it by name even though the default includes would skip it).
 */
@DisplayName("RegistrationBenchmark — generated-descriptor vs reflective-scan speedup")
class RegistrationBenchmark {

    // --- JIT blackhole sink (prevents dead-code elimination) ---

    /** Blackhole sink — assigned but never read; keeps the JIT from dead-code-eliminating results. */
    @SuppressWarnings("unused")
    private static volatile Object sink;

    // --- Warmup and measurement parameters ---

    private static final int WARMUP_ITERS = 10_000;
    private static final int MEASURE_ITERS = 100_000;

    // --- Fixture instances ---

    private static final BenchResource RESOURCE = new BenchResource();
    private static final BenchResource_JaxRsDescriptor DESCRIPTOR = new BenchResource_JaxRsDescriptor();
    private static final GeneratedJaxRsDescriptorSupport SUPPORT = new GeneratedJaxRsDescriptorSupport();

    // --- Annotations used by the reflective path scanner below ---

    private static final java.util.Set<Class<? extends Annotation>> HTTP_VERB_ANNOTATIONS =
            java.util.Set.of(GET.class, POST.class, PUT.class, DELETE.class);

    // --- Benchmark test ---

    @Test
    @DisplayName("generated-descriptor path is ≥2× faster than reflective scan over 100k iterations")
    void generatedDescriptor_isFasterThanReflectiveScan() {
        // --- Warm up both paths ---
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = generatedDescribe();
            sink = reflectiveScan();
        }

        // --- Measure generated path ---
        long genStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = generatedDescribe();
        }
        long genNanos = System.nanoTime() - genStart;

        // --- Measure reflective path ---
        long refStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = reflectiveScan();
        }
        long refNanos = System.nanoTime() - refStart;

        // --- Compute and report ---
        double genNsPerOp = (double) genNanos / MEASURE_ITERS;
        double refNsPerOp = (double) refNanos / MEASURE_ITERS;
        double speedup = refNsPerOp / genNsPerOp;

        System.out.printf(
                "[RegistrationBenchmark] generated: %.1f ns/op  reflective: %.1f ns/op  speedup: %.2f×%n",
                genNsPerOp, refNsPerOp, speedup);

        assertTrue(
                speedup >= 2.0,
                String.format(
                        "NFR-CG010-001 not met: expected ≥2× speedup but got %.2f× (generated=%.1f ns/op, reflective=%.1f ns/op)",
                        speedup, genNsPerOp, refNsPerOp));
    }

    // --- Generated-descriptor path simulation ---

    /**
     * Simulates the {@code GeneratedJaxRsDescriptorRegistry} fast path: calls
     * {@code descriptor.describe(...)} and returns the resulting list.
     *
     * @return the list of {@link ResourceMethodMeta} produced by the descriptor companion
     */
    private List<ResourceMethodMeta> generatedDescribe() {
        List<SecurityPolicyViolation> violations = new ArrayList<>();
        return DESCRIPTOR.describe(RESOURCE, SUPPORT, violations);
    }

    // --- Reflective path simulation ---

    /**
     * Simulates the core reflective work that {@code ResourceScanner} performs when no descriptor
     * companion is on the classpath:
     * <ol>
     *   <li>Walk {@link Class#getDeclaredMethods()} over the resource class and its superclasses.</li>
     *   <li>For each method, check for an HTTP-verb annotation (GET, POST, PUT, DELETE).</li>
     *   <li>For each HTTP-verb method, classify each parameter by scanning its annotations.</li>
     *   <li>Collect the results into a list of {@link MethodInfo} value objects.</li>
     * </ol>
     *
     * <p>The returned list is assigned to the global sink so the JIT cannot eliminate the work.
     *
     * @return the list of discovered method info entries
     */
    private List<MethodInfo> reflectiveScan() {
        Class<?> clazz = BenchResource.class;
        Map<String, Method> seen = new LinkedHashMap<>();

        // Superclass walk (mirrors ResourceScanner.collectMethods)
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String key = methodKey(m);
                seen.putIfAbsent(key, m);
            }
        }

        List<MethodInfo> result = new ArrayList<>();
        for (Method m : seen.values()) {
            // Check for HTTP verb annotation
            String httpMethod = resolveHttpMethod(m);
            if (httpMethod == null) {
                continue;
            }
            // Classify parameters
            List<String> paramSources = new ArrayList<>();
            for (Parameter p : m.getParameters()) {
                paramSources.add(classifyParam(p));
            }
            result.add(new MethodInfo(m.getName(), httpMethod, paramSources));
        }
        return result;
    }

    /**
     * Computes a deduplication key for a method: its name plus the erased parameter type names,
     * colon-separated. Mirrors the key produced by {@code JaxRsMethodDiscovery.collect}.
     *
     * @param m the method to compute the key for
     * @return the deduplication key string
     */
    private static String methodKey(Method m) {
        StringBuilder sb = new StringBuilder(m.getName());
        for (Class<?> pt : m.getParameterTypes()) {
            sb.append(':').append(pt.getName());
        }
        return sb.toString();
    }

    /**
     * Returns the HTTP verb string for the method, or {@code null} if no HTTP verb annotation is
     * present. Mirrors the annotation-check logic in {@code ResourceScanner.resolveHttpMethod}.
     *
     * @param m the method to inspect
     * @return the HTTP verb string, or {@code null}
     */
    private static String resolveHttpMethod(Method m) {
        if (m.isAnnotationPresent(GET.class)) return "GET";
        if (m.isAnnotationPresent(POST.class)) return "POST";
        if (m.isAnnotationPresent(PUT.class)) return "PUT";
        if (m.isAnnotationPresent(DELETE.class)) return "DELETE";
        return null;
    }

    /**
     * Classifies a single method parameter based on its JAX-RS annotations.
     * Returns a short string identifying the classification.
     *
     * @param p the parameter to classify
     * @return a string such as {@code "PATH"}, {@code "QUERY"}, {@code "HEADER"}, {@code "BODY"}
     */
    private static String classifyParam(Parameter p) {
        if (p.isAnnotationPresent(PathParam.class)) return "PATH";
        if (p.isAnnotationPresent(QueryParam.class)) return "QUERY";
        if (p.isAnnotationPresent(HeaderParam.class)) return "HEADER";
        if (p.isAnnotationPresent(jakarta.ws.rs.CookieParam.class)) return "COOKIE";
        if (p.isAnnotationPresent(jakarta.ws.rs.FormParam.class)) return "FORM";
        if (p.isAnnotationPresent(jakarta.ws.rs.BeanParam.class)) return "BEAN_PARAM";
        return "BODY";
    }

    // --- Simple value record for reflective-path results ---

    /**
     * Lightweight result record for the reflective scanner simulation.
     * Carries enough information to prevent dead-code elimination.
     *
     * @param name        the method name
     * @param httpMethod  the HTTP verb
     * @param paramSources parameter classification labels
     */
    private record MethodInfo(String name, String httpMethod, List<String> paramSources) {}

    // --- Optional main entry point ---

    /**
     * Standalone runner for ad-hoc benchmark execution outside JUnit.
     * Prints the same numbers as the JUnit test but does not assert.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        RegistrationBenchmark bench = new RegistrationBenchmark();

        // Warm up
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = bench.generatedDescribe();
            sink = bench.reflectiveScan();
        }

        // Measure generated
        long genStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = bench.generatedDescribe();
        }
        long genNanos = System.nanoTime() - genStart;

        // Measure reflective
        long refStart = System.nanoTime();
        for (int i = 0; i < MEASURE_ITERS; i++) {
            sink = bench.reflectiveScan();
        }
        long refNanos = System.nanoTime() - refStart;

        double genNsPerOp = (double) genNanos / MEASURE_ITERS;
        double refNsPerOp = (double) refNanos / MEASURE_ITERS;
        double speedup = refNsPerOp / genNsPerOp;

        System.out.printf(
                "[RegistrationBenchmark] generated: %.1f ns/op  reflective: %.1f ns/op  speedup: %.2f×%n",
                genNsPerOp, refNsPerOp, speedup);
    }
}
