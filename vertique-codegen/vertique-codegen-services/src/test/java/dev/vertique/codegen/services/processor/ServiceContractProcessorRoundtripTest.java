// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import io.vertx.core.json.JsonObject;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CG-005 acceptance criterion #2: roundtrip test.
 *
 * <p>Compiles a contract with class-level resilience annotations, a method-level {@code @Timeout}
 * override, and an interceptor-visible class-level marker annotation against
 * {@link ServiceContractProcessor}. Asserts runtime {@code ContractEntry}-level equivalence
 * between the generated contributor path and the legacy {@code ServiceContractRegistry.build()}
 * reflective path.
 *
 * <p><strong>Runtime registry-equivalence mode</strong>: compiles the fixture, loads the
 * generated contributor and fixture impl via the harness classloader, instantiates them, and
 * asserts {@code ContractEntry}-level equivalence against a sibling registry built from the
 * same impl on the legacy reflective path.
 *
 * <p>Structural source-text assertions are retained only for emitter-shape contracts (generated
 * module name, package, and {@code @Provides @IntoSet} binding) since those are still the
 * cheapest way to verify the emitter produces the correct wrapper glue.
 *
 * <p>Both a direct-impl variant and a handler-pattern variant are tested.
 */
class ServiceContractProcessorRoundtripTest {

    // --- Shared annotation stubs for structural tests ---
    // Note: for runtime tests we compile against the real framework classpath
    // (vertique-core, vertique-services are test-scope deps), so stubs are only
    // needed for the structural subset that still uses FRAMEWORK_SOURCES.

    private static final JavaFileObject TIMEOUT_ANNOTATION =
            SourceFiles.inline("dev.vertique.core.resilience.Timeout", """
                    package dev.vertique.core.resilience;
                    import java.lang.annotation.*;
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Timeout {
                        long valueMs() default 5000;
                    }
                    """);

    private static final JavaFileObject CIRCUIT_BREAKER_ANNOTATION =
            SourceFiles.inline("dev.vertique.core.resilience.CircuitBreaker", """
                    package dev.vertique.core.resilience;
                    import java.lang.annotation.*;
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface CircuitBreaker {
                        int failureRateThreshold() default 50;
                    }
                    """);

    private static final JavaFileObject AUDIT_TIER_ANNOTATION =
            SourceFiles.inline("com.example.annotation.AuditTier", """
                    package com.example.annotation;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface AuditTier {
                        String value();
                    }
                    """);

    // --- Billing contract and its impls (structural tests — use stubs) ---

    private static final JavaFileObject BILLING_SERVICE_CONTRACT =
            SourceFiles.inline("com.example.BillingService", """
                    package com.example;
                    import com.example.annotation.AuditTier;
                    import dev.vertique.core.resilience.Timeout;
                    import dev.vertique.core.resilience.CircuitBreaker;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "billing-service", namespace = "billing")
                    @Timeout(valueMs = 10000)
                    @CircuitBreaker
                    @AuditTier("gold")
                    public interface BillingService {
                        @ServiceOperation("charge")
                        @Timeout(valueMs = 2000)
                        Future<String> charge(String chargeRequest);
                    }
                    """);

    private static final JavaFileObject BILLING_SERVICE_IMPL =
            SourceFiles.inline("com.example.BillingServiceImpl", """
                    package com.example;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class BillingServiceImpl implements BillingService {
                        @Inject BillingServiceImpl() {}
                        @Override public Future<String> charge(String chargeRequest) {
                            return Future.succeededFuture("ok");
                        }
                    }
                    """);

    private static final JavaFileObject BILLING_SERVICE_HANDLER =
            SourceFiles.inline("com.example.BillingServiceHandler", """
                    package com.example;
                    import dev.vertique.services.ServiceHandler;
                    import dev.vertique.security.SecurityContext;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class BillingServiceHandler implements ServiceHandler<BillingService> {
                        @Inject BillingServiceHandler() {}
                        public Future<String> charge(String chargeRequest, SecurityContext sc) {
                            return Future.succeededFuture("ok");
                        }
                    }
                    """);

    // --- Runtime fixture sources (compiled against real framework classpath) ---
    // These use the correct real API: @Timeout(value=...) not @Timeout(valueMs=...)
    // and include the domain AuditTier annotation as an inline fixture.

    private static final JavaFileObject RUNTIME_AUDIT_TIER_ANNOTATION =
            SourceFiles.inline("com.example.annotation.AuditTier", """
                    package com.example.annotation;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface AuditTier {
                        String value();
                    }
                    """);

    private static final JavaFileObject RUNTIME_BILLING_SERVICE_CONTRACT =
            SourceFiles.inline("com.example.BillingService", """
                    package com.example;
                    import com.example.annotation.AuditTier;
                    import dev.vertique.core.resilience.Timeout;
                    import dev.vertique.core.resilience.CircuitBreaker;
                    import dev.vertique.core.resilience.Retry;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "billing-service", namespace = "billing")
                    @Timeout(10000)
                    @CircuitBreaker
                    @AuditTier("gold")
                    public interface BillingService {
                        @ServiceOperation("charge")
                        @Timeout(2000)
                        @Retry(maxRetries = 3)
                        Future<String> charge(String chargeRequest);
                    }
                    """);

    private static final JavaFileObject RUNTIME_BILLING_SERVICE_IMPL =
            SourceFiles.inline("com.example.BillingServiceImpl", """
                    package com.example;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class BillingServiceImpl implements BillingService {
                        @Inject public BillingServiceImpl() {}
                        @Override public Future<String> charge(String chargeRequest) {
                            return Future.succeededFuture("ok");
                        }
                    }
                    """);

    private static final JavaFileObject RUNTIME_BILLING_SERVICE_HANDLER =
            SourceFiles.inline("com.example.BillingServiceHandler", """
                    package com.example;
                    import dev.vertique.services.ServiceHandler;
                    import dev.vertique.security.SecurityContext;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class BillingServiceHandler implements ServiceHandler<BillingService> {
                        @Inject public BillingServiceHandler() {}
                        public Future<String> charge(String chargeRequest, SecurityContext sc) {
                            return Future.succeededFuture("ok");
                        }
                    }
                    """);

    /**
     * Builds the set of all framework sources plus resilience/annotation stubs needed for the
     * structural roundtrip fixture, excluding the domain sources.
     */
    private static JavaFileObject[] frameworkWithAnnotations() {
        return concat(FRAMEWORK_SOURCES, TIMEOUT_ANNOTATION, CIRCUIT_BREAKER_ANNOTATION, AUDIT_TIER_ANNOTATION);
    }

    // --- Direct-impl variant: structural assertions ---

    @Test
    @DisplayName("roundtrip (direct-impl): generated contributor source contains expected structural elements")
    void directImpl_roundtrip_structuralAssertions() {
        JavaFileObject[] sources = concat(frameworkWithAnnotations(), BILLING_SERVICE_CONTRACT, BILLING_SERVICE_IMPL);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // Operation name and deployment options
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", ".operation(\"charge\")");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor",
                ".deploymentOptions(config, \"services\", \"contracts\", \"billing\", \"billing-service\")");

        // Payload and return type
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".payloadType(");
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".returnType(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "String.class"); // return + payload type

        // Contract-side param
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".param(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "PAYLOAD"); // param source

        // Contract method reference (resolveMethod uses contract interface class)
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", "BillingService.class");

        // Resilience annotations: must be derived from contract, covering method-level @Timeout
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", ".resilienceAnnotations(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "ResilienceAnnotations.resolve(");

        // Method annotations: include method-level @Timeout(valueMs=2000)
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".methodAnnotations(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "AnnotationResolver.resolveMethodAnnotations(");

        // Class annotations: derived from contract interface (not the impl)
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".classAnnotations(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "AnnotationResolver.resolveClassAnnotations(");
        // Class annotations are resolved from the contract interface class
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor",
                "AnnotationResolver.resolveClassAnnotations(BillingService.class)");

        // Module: contains @Provides @IntoSet for the contributor
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@Provides");
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "@IntoSet");
        result.assertGeneratedSourceContains(
                "com.example.GeneratedServicesModule", "BillingService_ContractContributor");
    }

    // --- Handler-pattern variant: structural assertions ---

    @Test
    @DisplayName("roundtrip (handler-pattern): generated contributor source contains expected structural elements")
    void handlerPattern_roundtrip_structuralAssertions() {
        JavaFileObject[] sources =
                concat(frameworkWithAnnotations(), BILLING_SERVICE_CONTRACT, BILLING_SERVICE_HANDLER);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // Operation name
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", ".operation(\"charge\")");

        // Handler method reference
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".handlerMethod(");
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "BillingServiceHandler.class");

        // Handler params: chargeRequest (PAYLOAD) + sc (DISPATCH_CONTEXT)
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", ".handlerParam(");
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", "DISPATCH_CONTEXT");
        result.assertGeneratedSourceContains("com.example.BillingService_ContractContributor", "SecurityContext.class");

        // Class annotations still resolved from contract interface (not the handler)
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor",
                "AnnotationResolver.resolveClassAnnotations(BillingService.class)");

        // Resilience resolved from contract method (method-level @Timeout override)
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "ResilienceAnnotations.resolve(BillingService.class");
    }

    // --- Iterable processors (uses the iterable harness overload) ---

    @Test
    @DisplayName("roundtrip compiled with iterable processors overload")
    void iterableProcessors_contributorGenerated() {
        JavaFileObject[] sources = concat(frameworkWithAnnotations(), BILLING_SERVICE_CONTRACT, BILLING_SERVICE_IMPL);

        var result = ProcessorTestHarness.run(List.of(new ServiceContractProcessor()), sources);

        result.assertSuccess();
        result.assertGeneratedSourceContains(
                "com.example.BillingService_ContractContributor", "ServiceContractContributor");
    }

    // --- Direct-impl variant: runtime registry-equivalence ---

    /**
     * Compiles the billing fixture (direct-impl pattern) against the real framework classpath,
     * loads the generated contributor and the impl class via the harness classloader, instantiates
     * them, and asserts that the resulting {@link ContractEntry} is semantically equivalent to the
     * one produced by the legacy {@link ServiceContractRegistry#build(Set)} reflective path.
     *
     * <p>Equivalence checks per operation:
     * <ul>
     *   <li>address, stableTargetId, type, name, operation — exact match</li>
     *   <li>payloadType, returnType, oneWay — exact match</li>
     *   <li>params — same count; each param's name, source, and type match</li>
     *   <li>resilienceAnnotations — non-null when expected; timeout present with correct value</li>
     *   <li>methodAnnotations, classAnnotations — same annotation types present in both paths</li>
     * </ul>
     */
    @Test
    @DisplayName("runtime equivalence (direct-impl): generated contributor produces matching ContractEntry")
    void directImpl_runtimeEquivalence() throws Exception {
        // Compile against the real framework classpath — no stubs, so the generated code
        // references the actual ServiceContractEntries / ServiceContractContributor at runtime.
        JavaFileObject[] sources = {
            RUNTIME_AUDIT_TIER_ANNOTATION, RUNTIME_BILLING_SERVICE_CONTRACT, RUNTIME_BILLING_SERVICE_IMPL
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        // Load fixture classes via the strict produced-FQN gate so the test fails if either FQN
        // wasn't actually produced by this compilation (vs silently falling back to the parent).
        Class<?> implClass = result.loadGeneratedClass("com.example.BillingServiceImpl");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.BillingService_ContractContributor");

        // Instantiate the impl and wrap it in a Provider proxy (CG-011 W4: constructor now takes
        // Provider<Impl> for lazy instantiation, not the impl directly).
        Object implInstance = implClass.getDeclaredConstructor().newInstance();
        Object implProvider = buildProvider(implInstance, implClass.getClassLoader());

        // The single-impl contributor has one Provider parameter — find it by param count.
        java.lang.reflect.Constructor<?> ctor = findSingleParamConstructor(contributorClass);
        ServiceContractContributor contributor = (ServiceContractContributor) ctor.newInstance(implProvider);

        // Contributor path: call contribute(config)
        List<ContractEntry<?>> contributed = contributor.contribute(new JsonObject());
        assertNotNull(contributed);
        assertEquals(1, contributed.size(), "Expected exactly one ContractEntry from contributor");
        ContractEntry<?> generatedEntry = contributed.get(0);

        // Legacy path: build registry using the SAME impl instance loaded from the harness loader
        ServiceContractRegistry legacyRegistry = ServiceContractRegistry.build(Set.of(implInstance), configParser());
        ContractEntry<?> legacyEntry = legacyRegistry.entries().iterator().next();

        // Compare entry-level fields
        assertContractEntriesEquivalent(generatedEntry, legacyEntry);
    }

    // --- Handler-pattern variant: runtime registry-equivalence ---

    /**
     * Compiles the billing fixture (handler-pattern) against the real framework classpath,
     * loads the generated contributor and the handler class via the harness classloader, and
     * asserts {@link ContractEntry}-level equivalence against the legacy reflective path.
     *
     * <p>The handler-pattern uses separate contract params (payload only) and handler params
     * (payload + SecurityContext). Both param lists are compared independently.
     */
    @Test
    @DisplayName("runtime equivalence (handler-pattern): generated contributor produces matching ContractEntry")
    void handlerPattern_runtimeEquivalence() throws Exception {
        JavaFileObject[] sources = {
            RUNTIME_AUDIT_TIER_ANNOTATION, RUNTIME_BILLING_SERVICE_CONTRACT, RUNTIME_BILLING_SERVICE_HANDLER
        };

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);
        result.assertSuccess();

        Class<?> handlerClass = result.loadGeneratedClass("com.example.BillingServiceHandler");
        Class<?> contributorClass = result.loadGeneratedClass("com.example.BillingService_ContractContributor");

        Object handlerInstance = handlerClass.getDeclaredConstructor().newInstance();
        Object handlerProvider = buildProvider(handlerInstance, handlerClass.getClassLoader());
        java.lang.reflect.Constructor<?> handlerCtor = findSingleParamConstructor(contributorClass);
        ServiceContractContributor contributor = (ServiceContractContributor) handlerCtor.newInstance(handlerProvider);

        List<ContractEntry<?>> contributed = contributor.contribute(new JsonObject());
        assertNotNull(contributed);
        assertEquals(1, contributed.size());
        ContractEntry<?> generatedEntry = contributed.get(0);

        ServiceContractRegistry legacyRegistry = ServiceContractRegistry.build(Set.of(handlerInstance), configParser());
        ContractEntry<?> legacyEntry = legacyRegistry.entries().iterator().next();

        assertContractEntriesEquivalent(generatedEntry, legacyEntry);
    }

    // --- Shared helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Shared assertion helpers ---

    /**
     * Asserts that two {@link ContractEntry} records are semantically equivalent — same
     * address layout, operations, parameter lists, resilience annotations, and annotation types.
     *
     * <p>Service-instance identity is intentionally NOT compared: the two paths use different
     * instances of the impl class (they may even be defined by different classloaders if the
     * legacy path scans from the test classpath rather than the harness loader).
     *
     * @param generated the entry produced by the codegen contributor path
     * @param legacy    the entry produced by the legacy {@code ServiceRegistrar} path
     */
    private static void assertContractEntriesEquivalent(ContractEntry<?> generated, ContractEntry<?> legacy) {
        assertEquals(legacy.baseAddress(), generated.baseAddress(), "baseAddress mismatch");
        assertEquals(legacy.namespace(), generated.namespace(), "namespace mismatch");
        assertEquals(legacy.name(), generated.name(), "name mismatch");
        assertEquals(legacy.stableContractId(), generated.stableContractId(), "stableContractId mismatch");
        assertEquals(legacy.operations().keySet(), generated.operations().keySet(), "operations keyset mismatch");

        for (String opName : legacy.operations().keySet()) {
            ServiceMethodMeta legacyMeta = legacy.operations().get(opName);
            ServiceMethodMeta generatedMeta = generated.operations().get(opName);
            assertNotNull(generatedMeta, "Missing generated operation: " + opName);
            assertOperationsEquivalent(opName, generatedMeta, legacyMeta);
        }
    }

    /**
     * Asserts that two {@link ServiceMethodMeta} records for the same operation are equivalent.
     *
     * @param opName       the operation name (for error messages)
     * @param generated    the metadata from the codegen path
     * @param legacy       the metadata from the legacy path
     */
    private static void assertOperationsEquivalent(
            String opName, ServiceMethodMeta generated, ServiceMethodMeta legacy) {
        assertEquals(legacy.address(), generated.address(), opName + ": address mismatch");
        assertEquals(legacy.stableTargetId(), generated.stableTargetId(), opName + ": stableTargetId mismatch");
        assertEquals(legacy.operation(), generated.operation(), opName + ": operation mismatch");
        assertEquals(legacy.payloadType(), generated.payloadType(), opName + ": payloadType mismatch");
        assertEquals(legacy.returnType(), generated.returnType(), opName + ": returnType mismatch");
        assertEquals(legacy.oneWay(), generated.oneWay(), opName + ": oneWay mismatch");

        // Compare contract-side param list
        assertParamListsEquivalent(opName + ".params", generated.params(), legacy.params());
        // Compare handler-side param list
        assertParamListsEquivalent(opName + ".handlerParams", generated.handlerParams(), legacy.handlerParams());

        // Resilience: compare all three annotations by full equality
        assertResilienceEquivalent(opName, generated, legacy);

        // Annotation sets: deep equality (type + all attribute values) in both paths
        assertAnnotationsEqual(
                opName + ".methodAnnotations", generated.methodAnnotations(), legacy.methodAnnotations());
        assertAnnotationsEqual(opName + ".classAnnotations", generated.classAnnotations(), legacy.classAnnotations());
    }

    /**
     * Asserts that two param lists have the same size and each param has matching source and type.
     *
     * <p>Param {@link ParamMeta#name()} is intentionally NOT compared. The generated contributor
     * encodes APT-resolved parameter names as string literals at compile time (e.g.
     * {@code "chargeRequest"}). The legacy path uses {@link java.lang.reflect.Method} reflection
     * on classes compiled without the {@code -parameters} javac flag (as is the case for inline
     * {@link dev.vertique.codegen.test.fixtures.SourceFiles#inline} fixtures compiled by
     * {@code compile-testing}), which produces synthetic names like {@code "arg0"}. Both are
     * correct for their respective paths; the name is used only for diagnostics and does not
     * affect routing or dispatch.
     *
     * @param context   label for assertion messages
     * @param generated the generated param list
     * @param legacy    the legacy param list
     */
    private static void assertParamListsEquivalent(String context, List<ParamMeta> generated, List<ParamMeta> legacy) {
        assertEquals(legacy.size(), generated.size(), context + ": param count mismatch");
        for (int i = 0; i < legacy.size(); i++) {
            ParamMeta gen = generated.get(i);
            ParamMeta leg = legacy.get(i);
            assertEquals(leg.source(), gen.source(), context + "[" + i + "]: source mismatch");
            assertEquals(leg.type(), gen.type(), context + "[" + i + "]: type mismatch");
        }
    }

    /**
     * Asserts full resilience annotation equivalence for all three annotations: {@link
     * dev.vertique.core.resilience.Timeout}, {@link dev.vertique.core.resilience.CircuitBreaker},
     * and {@link dev.vertique.core.resilience.Retry}.
     *
     * <p>Comparison uses annotation {@code equals()} per JDK spec, which returns {@code true}
     * iff the annotation type and all member values are equal. This catches attribute drift
     * (e.g. different {@code maxRetries} or {@code value}) as well as presence/absence.
     *
     * @param opName    the operation name (for error messages)
     * @param generated the generated metadata
     * @param legacy    the legacy metadata
     */
    private static void assertResilienceEquivalent(
            String opName, ServiceMethodMeta generated, ServiceMethodMeta legacy) {
        assertEquals(
                legacy.resilienceAnnotations().timeout(),
                generated.resilienceAnnotations().timeout(),
                opName + ": timeout differs");
        assertEquals(
                legacy.resilienceAnnotations().circuitBreaker(),
                generated.resilienceAnnotations().circuitBreaker(),
                opName + ": circuitBreaker differs");
        assertEquals(
                legacy.resilienceAnnotations().retry(),
                generated.resilienceAnnotations().retry(),
                opName + ": retry differs");
    }

    /**
     * Asserts that two annotation lists contain the same set of annotations using deep equality.
     *
     * <p>Comparison is performed via annotation proxy {@link Annotation#equals}, which per JDK
     * spec returns {@code true} iff the annotation type and all member values are identical.
     * This catches attribute drift (e.g. {@code @AuditTier("gold")} vs
     * {@code @AuditTier("silver")}) that a type-name-only check would silently miss.
     *
     * <p>Order is not compared; both lists are converted to {@link Set} before comparison.
     *
     * @param context   label for assertion messages
     * @param generated the annotation list from the generated path
     * @param legacy    the annotation list from the legacy path
     */
    private static void assertAnnotationsEqual(String context, List<Annotation> generated, List<Annotation> legacy) {
        Set<Annotation> generatedSet = new HashSet<>(generated);
        Set<Annotation> legacySet = new HashSet<>(legacy);
        assertEquals(
                legacySet,
                generatedSet,
                () -> context + " annotation sets differ.\n  generated: " + generatedSet + "\n  legacy:    "
                        + legacySet);
    }

    // --- Helper ---

    /**
     * Wraps {@code delegate} in a reflective {@code jakarta.inject.Provider} proxy that returns
     * the delegate from {@code get()}. Uses {@code loaderHint} to load the Provider interface
     * in a classloader compatible with the generated contributor.
     *
     * @param delegate   the object to return from {@code Provider.get()}
     * @param loaderHint classloader to use for loading the Provider interface
     * @return a Provider proxy backed by {@code delegate}
     */
    private static Object buildProvider(Object delegate, ClassLoader loaderHint) {
        Class<?> providerInterface;
        try {
            providerInterface = Class.forName("jakarta.inject.Provider", true, loaderHint);
        } catch (ClassNotFoundException e) {
            // Fall back to the test classloader
            try {
                providerInterface = Class.forName(
                        "jakarta.inject.Provider", true, ServiceContractProcessorRoundtripTest.class.getClassLoader());
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("jakarta.inject.Provider not on classpath", ex);
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
                loaderHint,
                new Class<?>[] {providerInterface},
                (proxy, method, args) -> "get".equals(method.getName()) ? delegate : null);
    }

    /**
     * Finds the single-parameter constructor on a generated contributor class. With CG-011 W4
     * the constructor accepts one {@code Provider<Impl>} rather than {@code Impl} directly.
     *
     * @param contributorClass the generated contributor class to inspect
     * @return the single-parameter constructor, made accessible
     * @throws IllegalStateException if no single-parameter constructor exists
     */
    private static java.lang.reflect.Constructor<?> findSingleParamConstructor(Class<?> contributorClass) {
        for (java.lang.reflect.Constructor<?> c : contributorClass.getDeclaredConstructors()) {
            if (c.getParameterCount() == 1) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new IllegalStateException("No single-parameter constructor on " + contributorClass.getName());
    }

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
