// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.core.resilience.Retry;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link ServiceContractEntries} builder and the
 * {@link ServiceContractContributor} SPI integration in
 * {@link ServiceContractRegistry#build(Set, Set, JsonObject)}.
 */
class ServiceContractEntriesTest {

    // --- Fixtures ---

    /** Minimal annotated contract used for default scanning tests. */
    @ServiceContract(namespace = "test", value = "test-service")
    interface TestService {
        @ServiceOperation("doWork")
        Future<String> doWork(String input);
    }

    static class TestServiceImpl implements TestService {
        @Override
        public Future<String> doWork(String input) {
            return Future.succeededFuture(input);
        }
    }

    /**
     * Annotated contract whose address collides with {@link TestService} when both are registered.
     */
    @ServiceContract(namespace = "test", value = "test-service")
    interface ConflictingService {
        @ServiceOperation("doWork")
        Future<String> doWork(String input);
    }

    static class ConflictingServiceImpl implements ConflictingService {
        @Override
        public Future<String> doWork(String input) {
            return Future.succeededFuture(input);
        }
    }

    /** Handler class used as a contributor-backed contract (not a shared interface). */
    static class MyHandler {
        public Future<Void> execute(String payload) {
            return Future.succeededFuture();
        }

        public Future<String> query(String input) {
            return Future.succeededFuture(input);
        }
    }

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static Method executeMethod() throws NoSuchMethodException {
        return MyHandler.class.getMethod("execute", String.class);
    }

    private static Method queryMethod() throws NoSuchMethodException {
        return MyHandler.class.getMethod("query", String.class);
    }

    // --- EntryBuilder Tests ---

    @Nested
    @DisplayName("ServiceContractEntries builder")
    class EntryBuilderTests {

        @Test
        @DisplayName("Happy path: builds a complete entry with correct fields")
        void shouldBuildCompleteEntry() throws Exception {
            MyHandler handler = new MyHandler();
            Method method = executeMethod();

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(method)
                    .payloadType(String.class)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();

            assertEquals(MyHandler.class, entry.contract());
            assertSame(handler, entry.serviceInstance());
            assertEquals("job", entry.namespace());
            assertEquals("my-handler", entry.name());
            assertEquals("services/job/my-handler", entry.baseAddress());
            assertEquals(1, entry.operations().size());

            ServiceMethodMeta meta = entry.operations().get("execute");
            assertNotNull(meta, "Expected 'execute' operation");
            assertEquals("services/job/my-handler/execute", meta.address());
            assertEquals("job", meta.namespace());
            assertEquals("my-handler", meta.name());
            assertEquals("execute", meta.operation());
            assertEquals(String.class, meta.payloadType());
            assertEquals(Void.class, meta.returnType());
            assertEquals(1, meta.params().size());
            ParamMeta param = meta.params().get(0);
            assertEquals("payload", param.name());
            assertEquals(ParamSource.PAYLOAD, param.source());
            assertEquals(String.class, param.type());
        }

        @Test
        @DisplayName("Multiple operations: both appear in the built entry's operations map")
        void shouldBuildEntryWithMultipleOperations() throws Exception {
            MyHandler handler = new MyHandler();

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("multi-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done()
                    .operation("query")
                    .method(queryMethod())
                    .returnType(String.class)
                    .done()
                    .build();

            assertEquals(2, entry.operations().size());
            assertTrue(entry.operations().containsKey("execute"), "Expected 'execute' operation");
            assertTrue(entry.operations().containsKey("query"), "Expected 'query' operation");
        }

        @Test
        @DisplayName("Validation: missing contract throws IllegalStateException")
        void shouldThrowWhenContractMissing() throws Exception {
            var builder = ServiceContractEntries.deployable()
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Validation: missing serviceInstance throws IllegalStateException")
        void shouldThrowWhenServiceInstanceMissing() throws Exception {
            var builder = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Building without namespace succeeds (namespace is optional)")
        void shouldSucceedWhenNamespaceMissing() throws Exception {
            var entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done()
                    .build();

            assertEquals("", entry.namespace());
            assertEquals("services/my-handler", entry.baseAddress());
        }

        @Test
        @DisplayName("Validation: missing name throws IllegalStateException")
        void shouldThrowWhenNameMissing() throws Exception {
            var builder = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Validation: no operations throws IllegalStateException")
        void shouldThrowWhenNoOperations() {
            var builder = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler");

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Validation: missing method on operation throws IllegalStateException")
        void shouldThrowWhenMethodMissingOnOperation() {
            var builder = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .returnType(Void.class)
                    .done();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Validation: missing returnType on operation throws IllegalStateException")
        void shouldThrowWhenReturnTypeMissingOnOperation() throws Exception {
            var builder = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .done();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("Default deployment options: 1 instance with event-loop threading model")
        void shouldUseDefaultDeploymentOptionsWhenNotSet() throws Exception {
            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done()
                    .build();

            DeploymentOptions opts = entry.deploymentOptions();
            assertEquals(1, opts.getInstances());
            assertNotEquals(ThreadingModel.WORKER, opts.getThreadingModel());
        }

        @Test
        @DisplayName("Config-based deployment options: instances and worker=true applied")
        void shouldApplyDeploymentOptionsFromConfig() throws Exception {
            JsonObject config = new JsonObject()
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "job",
                                            new JsonObject()
                                                    .put(
                                                            "my-handler",
                                                            new JsonObject()
                                                                    .put("instances", 3)
                                                                    .put("worker", true))));

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .done()
                    .deploymentOptions(config, "services", "job", "my-handler")
                    .build();

            assertEquals(3, entry.deploymentOptions().getInstances());
            assertEquals(ThreadingModel.WORKER, entry.deploymentOptions().getThreadingModel());
        }

        @Test
        @DisplayName("One-way operation: oneWay flag set on the built ServiceMethodMeta")
        void shouldSetOneWayFlagOnOperation() throws Exception {
            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(new MyHandler())
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(executeMethod())
                    .returnType(Void.class)
                    .oneWay()
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("execute");
            assertNotNull(meta);
            assertTrue(meta.oneWay(), "Expected oneWay=true on the built operation meta");
        }

        @Test
        @DisplayName("Annotation resolution: methodAnnotations and classAnnotations populated from method/class")
        void shouldResolveAnnotationsFromMethodAndClass() throws Exception {
            MyHandler handler = new MyHandler();
            Method method = executeMethod();

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(method)
                    .returnType(Void.class)
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("execute");
            assertNotNull(meta);
            // methodAnnotations and classAnnotations must not be null — they are populated by
            // AnnotationResolver even when no annotations are present (returns empty list)
            assertNotNull(meta.methodAnnotations());
            assertNotNull(meta.classAnnotations());
            // MyHandler has no annotations, so both lists should be empty
            assertTrue(meta.methodAnnotations().isEmpty(), "Expected no method annotations on MyHandler.execute");
            assertTrue(meta.classAnnotations().isEmpty(), "Expected no class annotations on MyHandler");
        }
    }

    // --- ServiceContractRegistry contributor integration tests ---

    @Nested
    @DisplayName("ServiceContractRegistry.build() with contributors")
    class ContributorIntegrationTests {

        @Test
        @DisplayName("Contributor entries merged: both default and contributor entries present")
        void shouldMergeContributorEntriesWithDefaultEntries() throws Exception {
            MyHandler handler = new MyHandler();
            Method method = executeMethod();

            ServiceContractContributor contributor = config -> List.of(ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("my-handler")
                    .operation("execute")
                    .method(method)
                    .returnType(Void.class)
                    .done()
                    .build());

            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new TestServiceImpl()), Set.of(contributor), new JsonObject(), configParser());

            assertEquals(2, registry.entries().size());
            boolean hasDefault =
                    registry.entries().stream().anyMatch(e -> e.baseAddress().equals("services/test/test-service"));
            boolean hasContributor =
                    registry.entries().stream().anyMatch(e -> e.baseAddress().equals("services/job/my-handler"));
            assertTrue(hasDefault, "Expected default services/test/test-service entry");
            assertTrue(hasContributor, "Expected contributor services/job/my-handler entry");
        }

        @Test
        @DisplayName("Address collision across contributor and default: ServiceRegistrationException thrown")
        void shouldThrowOnAddressCollisionBetweenContributorAndDefault() throws Exception {
            // MyHandler uses the same namespace/name/operation as TestServiceImpl's "doWork" operation
            // We need to make an operation address that clashes with TestService's address.
            // TestService address: test/test-service/doWork
            // Contributor entry: namespace="test", name="test-service", operation="doWork"
            MyHandler handler = new MyHandler();
            Method method = executeMethod();

            ServiceContractContributor contributor = config -> List.of(ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("test")
                    .name("test-service")
                    .operation("doWork")
                    .method(method)
                    .returnType(Void.class)
                    .done()
                    .build());

            assertThrows(
                    ServiceRegistrationException.class,
                    () -> ServiceContractRegistry.build(
                            Set.of(new TestServiceImpl()), Set.of(contributor), new JsonObject(), configParser()));
        }

        @Test
        @DisplayName("Duplicate contract key from contributor: ServiceRegistrationException thrown")
        void shouldThrowOnDuplicateContractKeyFromContributor() throws Exception {
            // TestServiceImpl registers TestService.class; contributor also registers TestService.class
            Method doWorkMethod = TestService.class.getMethod("doWork", String.class);
            TestServiceImpl instance = new TestServiceImpl();

            ServiceContractContributor contributor = config -> List.of(ServiceContractEntries.deployable()
                    .contract(TestService.class)
                    .serviceInstance(instance)
                    .namespace("other")
                    .name("other-svc")
                    .operation("doWork")
                    .method(doWorkMethod)
                    .returnType(String.class)
                    .done()
                    .build());

            assertThrows(
                    ServiceRegistrationException.class,
                    () -> ServiceContractRegistry.build(
                            Set.of(new TestServiceImpl()), Set.of(contributor), new JsonObject(), configParser()));
        }

        @Test
        @DisplayName("Empty contributor set: registry builds normally with only default entries")
        void shouldBuildNormallyWithEmptyContributorSet() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new TestServiceImpl()), Set.of(), new JsonObject(), configParser());

            assertEquals(1, registry.entries().size());
            ContractEntry<TestService> entry = registry.resolve(TestService.class);
            assertEquals("services/test/test-service", entry.baseAddress());
        }

        @Test
        @DisplayName("Contributor returns empty list: no error, only default entries registered")
        void shouldBuildNormallyWhenContributorReturnsEmptyList() {
            ServiceContractContributor emptyContributor = config -> List.of();

            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new TestServiceImpl()), Set.of(emptyContributor), new JsonObject(), configParser());

            assertEquals(1, registry.entries().size());
            assertNotNull(registry.resolve(TestService.class));
        }
    }

    // --- ResilienceAnnotationTests ---

    @Nested
    @DisplayName("resilience annotations")
    class ResilienceAnnotationTests {

        // --- Test Fixtures ---

        @Timeout(value = 5000)
        static class ClassLevelTimeoutHandler {
            public Future<Void> execute(String payload) {
                return Future.succeededFuture();
            }
        }

        static class MethodLevelRetryHandler {
            @Retry(maxRetries = 2, delayMs = 500)
            public Future<Void> execute(String payload) {
                return Future.succeededFuture();
            }
        }

        @Timeout(value = 10000)
        static class OverrideHandler {
            @Timeout(value = 3000)
            public Future<Void> execute(String payload) {
                return Future.succeededFuture();
            }
        }

        static class NoAnnotationsHandler {
            public Future<Void> execute(String payload) {
                return Future.succeededFuture();
            }
        }

        // --- Helpers ---

        private ContractEntry<?> buildEntryFor(Object handler, Method method) {
            return ServiceContractEntries.deployable()
                    .contract(handler.getClass())
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("resilience-handler")
                    .operation("execute")
                    .method(method)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();
        }

        // --- Tests ---

        @Test
        @DisplayName("class-level @Timeout is auto-resolved when no method annotation overrides it")
        void shouldResolveClassLevelTimeout() throws Exception {
            ClassLevelTimeoutHandler handler = new ClassLevelTimeoutHandler();
            Method method = ClassLevelTimeoutHandler.class.getMethod("execute", String.class);

            ContractEntry<?> entry = buildEntryFor(handler, method);
            ResilienceAnnotations resilience = entry.operations().get("execute").resilienceAnnotations();

            assertNotNull(resilience.timeout(), "Expected class-level @Timeout to be resolved");
            assertEquals(5000L, resilience.timeout().value());
        }

        @Test
        @DisplayName("method-level @Retry is auto-resolved from method annotation")
        void shouldResolveMethodLevelRetry() throws Exception {
            MethodLevelRetryHandler handler = new MethodLevelRetryHandler();
            Method method = MethodLevelRetryHandler.class.getMethod("execute", String.class);

            ContractEntry<?> entry = buildEntryFor(handler, method);
            ResilienceAnnotations resilience = entry.operations().get("execute").resilienceAnnotations();

            assertNotNull(resilience.retry(), "Expected method-level @Retry to be resolved");
            assertEquals(2, resilience.retry().maxRetries());
        }

        @Test
        @DisplayName("method-level @Timeout(3000) overrides class-level @Timeout(10000)")
        void shouldPreferMethodOverClass() throws Exception {
            OverrideHandler handler = new OverrideHandler();
            Method method = OverrideHandler.class.getMethod("execute", String.class);

            ContractEntry<?> entry = buildEntryFor(handler, method);
            ResilienceAnnotations resilience = entry.operations().get("execute").resilienceAnnotations();

            assertNotNull(resilience.timeout(), "Expected @Timeout to be resolved");
            assertEquals(
                    3000L,
                    resilience.timeout().value(),
                    "Method-level @Timeout(3000) should win over class-level @Timeout(10000)");
        }

        @Test
        @DisplayName("no annotations resolves to ResilienceAnnotations.NONE (hasAny() == false)")
        void shouldReturnNoneWhenNoAnnotations() throws Exception {
            NoAnnotationsHandler handler = new NoAnnotationsHandler();
            Method method = NoAnnotationsHandler.class.getMethod("execute", String.class);

            ContractEntry<?> entry = buildEntryFor(handler, method);
            ResilienceAnnotations resilience = entry.operations().get("execute").resilienceAnnotations();

            assertFalse(resilience.hasAny(), "Expected no resilience annotations on plain handler");
        }

        @Test
        @DisplayName("explicit resilienceAnnotations() override takes precedence over auto-resolve")
        void shouldUseExplicitResilienceWhenSet() throws Exception {
            // ClassLevelTimeoutHandler has @Timeout(5000) at class level; we override with NONE
            ClassLevelTimeoutHandler handler = new ClassLevelTimeoutHandler();
            Method method = ClassLevelTimeoutHandler.class.getMethod("execute", String.class);
            ResilienceAnnotations explicit = ResilienceAnnotations.NONE;

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(handler.getClass())
                    .serviceInstance(handler)
                    .namespace("job")
                    .name("resilience-handler")
                    .operation("execute")
                    .method(method)
                    .returnType(Void.class)
                    .resilienceAnnotations(explicit)
                    .done()
                    .build();

            ResilienceAnnotations resilience = entry.operations().get("execute").resilienceAnnotations();

            assertFalse(
                    resilience.hasAny(),
                    "Explicit ResilienceAnnotations.NONE should suppress auto-resolved @Timeout(5000)");
        }
    }

    // --- Builder Extension Tests (new setters: handlerMethod, handlerParam, methodAnnotations, classAnnotations) ---

    /**
     * Synthetic marker annotation used in builder-extension tests to inject a known annotation
     * without relying on annotations that may already be auto-resolved from test fixtures.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface SyntheticMarker {
        String value();
    }

    /**
     * A minimal contract interface whose class-level annotations differ from the impl class.
     * Used to verify that {@code .classAnnotations(...)} overrides auto-resolution from the impl.
     */
    @SyntheticMarker("contract-class")
    interface ContractWithClassAnnotation {
        Future<String> compute(String input);
    }

    /** Direct implementation whose class-level annotations differ from the contract interface. */
    @SyntheticMarker("impl-class")
    static class DirectImpl implements ContractWithClassAnnotation {
        @Override
        public Future<String> compute(String input) {
            return Future.succeededFuture(input);
        }
    }

    /** A handler class that has a handler-pattern method with an extra SecurityContext parameter. */
    static class HandlerWithSecurityContext {
        public Future<String> compute(String input, SecurityContext sc) {
            return Future.succeededFuture(input);
        }
    }

    @Nested
    @DisplayName("OperationBuilder extension setters")
    class OperationBuilderExtensionTests {

        // --- Helpers ---

        private static Method contractComputeMethod() throws NoSuchMethodException {
            return ContractWithClassAnnotation.class.getMethod("compute", String.class);
        }

        private static Method handlerComputeMethod() throws NoSuchMethodException {
            return HandlerWithSecurityContext.class.getMethod("compute", String.class, SecurityContext.class);
        }

        private static ContractEntry<?> buildMinimalEntry(Object instance, Method method) {
            return ServiceContractEntries.deployable()
                    .contract(instance.getClass())
                    .serviceInstance(instance)
                    .namespace("test")
                    .name("ext-svc")
                    .operation("compute")
                    .method(method)
                    .returnType(String.class)
                    .param("input", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();
        }

        // --- Test 1: defaults preserved when no new setters called ---

        @Test
        @DisplayName("defaults preserved: method==handlerMethod, params==handlerParams, annotations auto-resolved")
        void defaultsPreservedWhenNoSettersCalled() throws Exception {
            DirectImpl impl = new DirectImpl();
            Method method = contractComputeMethod();

            ContractEntry<?> entry = buildMinimalEntry(impl, method);
            ServiceMethodMeta meta = entry.operations().get("compute");

            assertNotNull(meta);
            // method and handlerMethod must be equal (same descriptor)
            assertEquals(meta.method(), meta.handlerMethod(), "handlerMethod should default to method");
            // params and handlerParams must be equal (same list contents)
            assertEquals(meta.params(), meta.handlerParams(), "handlerParams should default to params");
            // methodAnnotations must match AnnotationResolver auto-resolution
            assertEquals(
                    AnnotationResolver.resolveMethodAnnotations(method),
                    meta.methodAnnotations(),
                    "methodAnnotations should default to AnnotationResolver.resolveMethodAnnotations(method)");
            // classAnnotations must match AnnotationResolver auto-resolution from impl class
            assertEquals(
                    AnnotationResolver.resolveClassAnnotations(impl.getClass()),
                    meta.classAnnotations(),
                    "classAnnotations should default to AnnotationResolver.resolveClassAnnotations(impl.class)");
        }

        // --- Test 2: handlerMethod and handlerParams diverge when set ---

        @Test
        @DisplayName("handlerMethod and handlerParams diverge from method and params when explicitly set")
        void handlerMethodAndHandlerParamsDivergeWhenSet() throws Exception {
            DirectImpl impl = new DirectImpl();
            Method contractMethod = contractComputeMethod();
            Method handlerMethod = handlerComputeMethod();

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(impl.getClass())
                    .serviceInstance(impl)
                    .namespace("test")
                    .name("ext-svc")
                    .operation("compute")
                    .method(contractMethod)
                    .returnType(String.class)
                    .param("input", ParamSource.PAYLOAD, String.class)
                    .handlerMethod(handlerMethod)
                    .handlerParam("input", ParamSource.PAYLOAD, String.class)
                    .handlerParam("sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("compute");
            assertNotNull(meta);

            // contract method stays as set
            assertEquals(contractMethod, meta.method().resolve(), "method() should resolve to the contract method");

            // handlerMethod should resolve to the explicitly set handler method
            assertEquals(
                    handlerMethod,
                    meta.handlerMethod().resolve(),
                    "handlerMethod() should resolve to the handler method");

            // They must differ
            assertNotEquals(meta.method(), meta.handlerMethod(), "method and handlerMethod descriptors should differ");

            // params: the original PAYLOAD-only list
            assertEquals(1, meta.params().size(), "params should have 1 entry (contract params only)");
            assertEquals(ParamSource.PAYLOAD, meta.params().get(0).source());
            assertEquals(String.class, meta.params().get(0).type());

            // handlerParams: the two explicitly set handler params
            assertEquals(2, meta.handlerParams().size(), "handlerParams should have 2 entries");

            ParamMeta handlerParam0 = meta.handlerParams().get(0);
            assertEquals("input", handlerParam0.name());
            assertEquals(ParamSource.PAYLOAD, handlerParam0.source());
            assertEquals(String.class, handlerParam0.type());

            ParamMeta handlerParam1 = meta.handlerParams().get(1);
            assertEquals("sc", handlerParam1.name());
            assertEquals(ParamSource.DISPATCH_CONTEXT, handlerParam1.source());
            assertEquals(SecurityContext.class, handlerParam1.type());
            // lookup key for SecurityContext must be SC_KEY
            assertEquals(
                    SecurityContext.class.getName(),
                    handlerParam1.lookupKey(),
                    "SecurityContext param lookup key must be SC_KEY");
        }

        // --- Test 3: methodAnnotations override auto-resolution ---

        @Test
        @DisplayName("explicit methodAnnotations override auto-resolution from method")
        void methodAnnotationsOverrideAutoResolution() throws Exception {
            DirectImpl impl = new DirectImpl();
            Method method = contractComputeMethod();

            SyntheticMarker syntheticAnnotation = DirectImpl.class.getAnnotation(SyntheticMarker.class);
            assertNotNull(syntheticAnnotation, "Fixture: DirectImpl must carry @SyntheticMarker");
            List<java.lang.annotation.Annotation> explicitAnnotations = List.of(syntheticAnnotation);

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(impl.getClass())
                    .serviceInstance(impl)
                    .namespace("test")
                    .name("ext-svc")
                    .operation("compute")
                    .method(method)
                    .returnType(String.class)
                    .methodAnnotations(explicitAnnotations)
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("compute");
            assertNotNull(meta);
            assertEquals(
                    explicitAnnotations,
                    meta.methodAnnotations(),
                    "methodAnnotations should be exactly the explicitly provided list");
            // Confirm it differs from what AnnotationResolver would auto-resolve
            assertNotEquals(
                    AnnotationResolver.resolveMethodAnnotations(method),
                    meta.methodAnnotations(),
                    "Should differ from auto-resolved method annotations");
        }

        // --- Test 4: classAnnotations override auto-resolution ---

        @Test
        @DisplayName("explicit classAnnotations override auto-resolution from impl class")
        void classAnnotationsOverrideAutoResolution() throws Exception {
            DirectImpl impl = new DirectImpl();
            Method method = contractComputeMethod();

            // Contract-derived annotations (from the interface, not the impl)
            List<java.lang.annotation.Annotation> contractDerived =
                    AnnotationResolver.resolveClassAnnotations(ContractWithClassAnnotation.class);

            // Impl-derived annotations (from the impl class, which is what auto-resolution would produce)
            List<java.lang.annotation.Annotation> implDerived =
                    AnnotationResolver.resolveClassAnnotations(impl.getClass());

            // Verify the fixture actually has different annotations on contract vs impl
            assertNotEquals(
                    contractDerived,
                    implDerived,
                    "Fixture: contract and impl must have different class-level annotations");

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(impl.getClass())
                    .serviceInstance(impl)
                    .namespace("test")
                    .name("ext-svc")
                    .operation("compute")
                    .method(method)
                    .returnType(String.class)
                    .classAnnotations(contractDerived)
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("compute");
            assertNotNull(meta);
            assertEquals(
                    contractDerived,
                    meta.classAnnotations(),
                    "classAnnotations should be the contract-derived list, not impl-derived");
            assertNotEquals(
                    implDerived,
                    meta.classAnnotations(),
                    "classAnnotations must differ from impl auto-resolution when explicitly set");
        }

        // --- Test 5: handlerParam lookup key derivation ---

        @Test
        @DisplayName("handlerParam lookup key: SecurityContext subtype → SC_KEY, other types → type.getName()")
        void handlerParamLookupKeyDerivationMatchesParam() throws Exception {
            DirectImpl impl = new DirectImpl();
            Method method = contractComputeMethod();

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(impl.getClass())
                    .serviceInstance(impl)
                    .namespace("test")
                    .name("ext-svc")
                    .operation("compute")
                    .method(method)
                    .returnType(String.class)
                    .handlerParam("sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                    .handlerParam("other", ParamSource.DISPATCH_CONTEXT, String.class)
                    .done()
                    .build();

            ServiceMethodMeta meta = entry.operations().get("compute");
            assertNotNull(meta);
            assertEquals(2, meta.handlerParams().size());

            ParamMeta scParam = meta.handlerParams().get(0);
            assertEquals(SecurityContext.class.getName(), scParam.lookupKey(), "SecurityContext should map to SC_KEY");

            ParamMeta otherParam = meta.handlerParams().get(1);
            assertEquals(
                    String.class.getName(), otherParam.lookupKey(), "Non-SecurityContext should use type.getName()");
        }

        // --- Test 6: handlerMethodOverride without explicit resilience resolves from contract ---

        /**
         * Contract interface with a class-level {@link Timeout} annotation that differs from the
         * plain handler class used in the handler-pattern resilience tests.
         */
        @Timeout(value = 10000)
        interface TimeoutContract {
            Future<String> compute(String input);
        }

        /**
         * Handler class for the handler-pattern resilience tests — has no {@code @Timeout}
         * annotation so that auto-resolution from the handler class would yield no timeout.
         */
        static class NoTimeoutHandler {
            public Future<String> compute(String input) {
                return Future.succeededFuture(input);
            }
        }

        @Test
        @DisplayName(
                "handlerMethodOverride without explicit resilience resolves @Timeout from contract declaring class")
        void handlerMethodOverride_withoutExplicitResilience_resolvesFromContract() throws Exception {
            // Contract has @Timeout(10000) at class level; handler has no timeout.
            // Building via .handlerMethod(handlerM).method(contractM) without calling
            // .resilienceAnnotations(...) must pick up the contract's @Timeout.
            NoTimeoutHandler handler = new NoTimeoutHandler();
            Method contractM = TimeoutContract.class.getMethod("compute", String.class);
            Method handlerM = NoTimeoutHandler.class.getMethod("compute", String.class);

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(NoTimeoutHandler.class)
                    .serviceInstance(handler)
                    .namespace("test")
                    .name("timeout-svc")
                    .operation("compute")
                    .method(contractM)
                    .handlerMethod(handlerM)
                    .returnType(String.class)
                    .done()
                    .build();

            ResilienceAnnotations resilience = entry.operations().get("compute").resilienceAnnotations();
            assertNotNull(
                    resilience.timeout(),
                    "Expected @Timeout from the contract's declaring class, not the handler (which has none)");
            assertEquals(10000L, resilience.timeout().value(), "Should resolve @Timeout(10000) from TimeoutContract");
        }

        @Test
        @DisplayName("handlerMethodOverride without explicit classAnnotations resolves from contract declaring class")
        void handlerMethodOverride_withoutExplicitClassAnnotations_resolvesFromContract() throws Exception {
            // TimeoutContract has @Timeout at class level; NoTimeoutHandler has no class annotations.
            // Building via .handlerMethod(handlerM).method(contractM) without calling
            // .classAnnotations(...) must derive class annotations from the contract interface.
            NoTimeoutHandler handler = new NoTimeoutHandler();
            Method contractM = TimeoutContract.class.getMethod("compute", String.class);
            Method handlerM = NoTimeoutHandler.class.getMethod("compute", String.class);

            ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(NoTimeoutHandler.class)
                    .serviceInstance(handler)
                    .namespace("test")
                    .name("timeout-svc")
                    .operation("compute")
                    .method(contractM)
                    .handlerMethod(handlerM)
                    .returnType(String.class)
                    .done()
                    .build();

            List<java.lang.annotation.Annotation> classAnnotations =
                    entry.operations().get("compute").classAnnotations();
            List<java.lang.annotation.Annotation> contractAnnotations =
                    AnnotationResolver.resolveClassAnnotations(TimeoutContract.class);
            List<java.lang.annotation.Annotation> handlerAnnotations =
                    AnnotationResolver.resolveClassAnnotations(NoTimeoutHandler.class);

            assertEquals(
                    contractAnnotations,
                    classAnnotations,
                    "classAnnotations should be derived from the contract interface when handlerMethod is set");
            assertNotEquals(
                    handlerAnnotations,
                    classAnnotations,
                    "classAnnotations must differ from handler class auto-resolution");
        }

        // --- Test 7: delayed-job contributor parity (no new setters → current behavior preserved) ---

        @Test
        @DisplayName("delayed-job contributor parity: existing builder API produces same behavior as before extension")
        void delayedJobContributorParityTest() throws Exception {
            // Simulate what DelayedJobContractContributor does: no new setters, direct method+param only
            MyHandler handler = new MyHandler();
            Method method = executeMethod();

            ContractEntry<?> legacyEntry = ServiceContractEntries.deployable()
                    .contract(MyHandler.class)
                    .serviceInstance(handler)
                    .namespace("delayed-job")
                    .name("my-handler")
                    .operation("execute")
                    .address("jobs/delayed/my-handler/execute")
                    .method(method)
                    .payloadType(String.class)
                    .returnType(Void.class)
                    .param("payload", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();

            ServiceMethodMeta meta = legacyEntry.operations().get("execute");
            assertNotNull(meta);

            // Parity assertions: handler-equivalence preserved
            assertEquals(meta.method(), meta.handlerMethod(), "Legacy path: method == handlerMethod");
            assertEquals(meta.params(), meta.handlerParams(), "Legacy path: params == handlerParams");

            // address is set explicitly
            assertEquals("jobs/delayed/my-handler/execute", meta.address());
            assertNull(meta.stableTargetId(), "Non-service entries have null stableTargetId");

            // classAnnotations auto-resolved from handler instance class (not a new setter)
            assertEquals(
                    AnnotationResolver.resolveClassAnnotations(handler.getClass()),
                    meta.classAnnotations(),
                    "Legacy path: classAnnotations auto-resolved from serviceInstance.getClass()");

            // methodAnnotations auto-resolved from method (not a new setter)
            assertEquals(
                    AnnotationResolver.resolveMethodAnnotations(method),
                    meta.methodAnnotations(),
                    "Legacy path: methodAnnotations auto-resolved from method");
        }
    }
}
