// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.annotation.Timeout;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceRegistrarTest {

    // --- Contract Fixtures ---

    @ServiceContract(namespace = "integration", value = "test-service")
    interface ValidService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("fetchData")
        Future<List<String>> getData();

        @ServiceOperation("doNothing")
        Future<Void> doNothing();
    }

    interface NoAnnotationService {
        Future<String> greet(String name);
    }

    @ServiceContract(namespace = "business", value = "overloaded")
    interface OverloadedService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("greet2")
        Future<String> greet(String name, int count);
    }

    @ServiceContract(namespace = "business", value = "bad-return")
    interface BadReturnService {
        @ServiceOperation("greet")
        String greet(String name);
    }

    @ServiceContract(namespace = "business", value = "multi-payload")
    interface MultiPayloadService {
        @ServiceOperation("greet")
        Future<String> greet(String first, String second);
    }

    @ServiceContract(namespace = "business", value = "with-context")
    interface WithContextService {
        @ServiceOperation("greet")
        Future<String> greet(String name, SecurityContext sc);

        @ServiceOperation("process")
        Future<Void> process(String payload);
    }

    @Timeout(10000)
    @ServiceContract(namespace = "integration", value = "policy-service")
    interface PolicyService {
        @Timeout(5000)
        @CircuitBreaker(maxFailures = 3)
        @Retry(maxRetries = 2)
        @ServiceOperation("resilientOp")
        Future<String> resilientOp(String input);

        @ServiceOperation("inheritedTimeout")
        Future<String> inheritedTimeout(String input);
    }

    // --- Implementation Fixtures ---

    static class ValidServiceImpl implements ValidService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<List<String>> getData() {
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    static class NoAnnotationServiceImpl implements NoAnnotationService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello");
        }
    }

    static class OverloadedServiceImpl implements OverloadedService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello");
        }

        @Override
        public Future<String> greet(String name, int count) {
            return Future.succeededFuture("Hello");
        }
    }

    static class BadReturnServiceImpl implements BadReturnService {
        @Override
        public String greet(String name) {
            return "Hello";
        }
    }

    static class MultiPayloadServiceImpl implements MultiPayloadService {
        @Override
        public Future<String> greet(String first, String second) {
            return Future.succeededFuture("Hello");
        }
    }

    static class WithContextServiceImpl implements WithContextService {
        @Override
        public Future<String> greet(String name, SecurityContext sc) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<Void> process(String payload) {
            return Future.succeededFuture();
        }
    }

    static class PolicyServiceImpl implements PolicyService {
        @Override
        public Future<String> resilientOp(String input) {
            return Future.succeededFuture(input);
        }

        @Override
        public Future<String> inheritedTimeout(String input) {
            return Future.succeededFuture(input);
        }
    }

    // --- Helpers ---

    private ServiceRegistrar registrar() {
        return new ServiceRegistrar();
    }

    private Map<Class<?>, List<ServiceMethodMeta>> scanValid() {
        return registrar().scan(Set.of(new ValidServiceImpl()));
    }

    // --- Tests ---

    @Test
    @DisplayName("scan of ValidServiceImpl returns 3 method metas")
    void shouldScanValidServiceAndBuildMetas() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        assertTrue(result.containsKey(ValidService.class));
        assertEquals(3, result.get(ValidService.class).size());
    }

    @Test
    @DisplayName("@ServiceOperation value is used as operation name")
    void shouldResolveOperationNameFromAnnotation() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta getDataMeta = metas.stream()
                .filter(m -> m.operation().equals("fetchData"))
                .findFirst()
                .orElse(null);
        assertNotNull(getDataMeta, "Expected operation named 'fetchData' from @ServiceOperation");
    }

    @Test
    @DisplayName("Operation name is taken from @ServiceOperation value")
    void shouldUseMethodNameWhenNoOperationAnnotation() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        boolean hasGreet = metas.stream().anyMatch(m -> m.operation().equals("greet"));
        assertTrue(hasGreet, "Expected operation named 'greet' from @ServiceOperation");
    }

    @Test
    @DisplayName("Address is built as services/{type}/{name}/{operation}")
    void shouldBuildCorrectAddress() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta greetMeta = metas.stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();
        assertEquals("services/integration/test-service/greet", greetMeta.address());
        ServiceMethodMeta dataMeta = metas.stream()
                .filter(m -> m.operation().equals("fetchData"))
                .findFirst()
                .orElseThrow();
        assertEquals("services/integration/test-service/fetchData", dataMeta.address());
    }

    @Test
    @DisplayName("A plain parameter is classified as PAYLOAD")
    void shouldClassifyPayloadParam() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta greetMeta = metas.stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, greetMeta.params().size());
        ParamMeta param = greetMeta.params().get(0);
        assertEquals(ParamSource.PAYLOAD, param.source());
        assertEquals(String.class, param.type());
    }

    @Test
    @DisplayName("SecurityContext parameter is classified as DISPATCH_CONTEXT with SC_KEY")
    void shouldClassifySecurityContextParam() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new WithContextServiceImpl()));
        List<ServiceMethodMeta> metas = result.get(WithContextService.class);
        ServiceMethodMeta greetMeta = metas.stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, greetMeta.params().size());
        assertEquals(ParamSource.PAYLOAD, greetMeta.params().get(0).source());
        assertEquals(ParamSource.DISPATCH_CONTEXT, greetMeta.params().get(1).source());
        assertEquals(
                SecurityContext.class.getName(),
                greetMeta.params().get(1).lookupKey(),
                "SecurityContext param must use SecurityContext.class.getName() as lookupKey");
    }

    @Test
    @DisplayName("Body parameter in process() is classified as PAYLOAD (String)")
    void shouldClassifyProcessPayloadParam() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new WithContextServiceImpl()));
        List<ServiceMethodMeta> metas = result.get(WithContextService.class);
        ServiceMethodMeta processMeta = metas.stream()
                .filter(m -> m.operation().equals("process"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, processMeta.params().size());
        assertEquals(ParamSource.PAYLOAD, processMeta.params().get(0).source());
        assertEquals(String.class, processMeta.params().get(0).type());
    }

    @Test
    @DisplayName("Return type of Future<String> resolves to String.class")
    void shouldResolveReturnTypeString() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta greetMeta = metas.stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();
        assertEquals(String.class, greetMeta.returnType());
    }

    @Test
    @DisplayName("Return type of Future<Void> resolves to Void.class")
    void shouldResolveReturnTypeVoid() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta doNothingMeta = metas.stream()
                .filter(m -> m.operation().equals("doNothing"))
                .findFirst()
                .orElseThrow();
        assertEquals(Void.class, doNothingMeta.returnType());
    }

    @Test
    @DisplayName("Return type of Future<List<String>> resolves to List.class (raw)")
    void shouldResolveReturnTypeParameterized() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        List<ServiceMethodMeta> metas = result.get(ValidService.class);
        ServiceMethodMeta dataMeta = metas.stream()
                .filter(m -> m.operation().equals("fetchData"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.class, dataMeta.returnType());
    }

    @Test
    @DisplayName("Method-level policy annotations override type-level for resilientOp")
    void shouldResolvePolicyAnnotationsMethodOverridesType() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new PolicyServiceImpl()));
        List<ServiceMethodMeta> metas = result.get(PolicyService.class);
        ServiceMethodMeta resilientMeta = metas.stream()
                .filter(m -> m.operation().equals("resilientOp"))
                .findFirst()
                .orElseThrow();

        var policies = resilientMeta.resilienceAnnotations();
        assertTrue(policies.hasAny());
        assertTrue(policies.timeout().isPresent());
        assertEquals(
                5000L,
                policies.timeout().orElseThrow().value(),
                "Method-level timeout (5000) must override class-level (10000)");
        assertTrue(policies.circuitBreaker().isPresent());
        assertEquals(3, policies.circuitBreaker().orElseThrow().maxFailures());
        assertTrue(policies.retry().isPresent());
        assertEquals(2, policies.retry().orElseThrow().maxRetries());
    }

    @Test
    @DisplayName("Type-level @Timeout is inherited by methods without their own timeout")
    void shouldResolvePolicyAnnotationsInheritedFromType() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new PolicyServiceImpl()));
        List<ServiceMethodMeta> metas = result.get(PolicyService.class);
        ServiceMethodMeta inheritedMeta = metas.stream()
                .filter(m -> m.operation().equals("inheritedTimeout"))
                .findFirst()
                .orElseThrow();

        var policies = inheritedMeta.resilienceAnnotations();
        assertTrue(policies.hasAny());
        assertTrue(policies.timeout().isPresent());
        assertEquals(10000L, policies.timeout().orElseThrow().value(), "Should inherit class-level timeout (10000)");
        assertTrue(policies.circuitBreaker().isEmpty());
        assertTrue(policies.retry().isEmpty());
    }

    @Test
    @DisplayName("Missing @ServiceContract annotation produces a violation")
    void shouldFailOnMissingAnnotation() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new NoAnnotationServiceImpl())));
        assertFalse(ex.violations().isEmpty());
    }

    @Test
    @DisplayName("Overloaded methods produce a violation")
    void shouldFailOnOverloadedMethods() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new OverloadedServiceImpl())));
        List<ServiceRegistrationViolation> violations = ex.violations();
        assertTrue(
                violations.stream().anyMatch(v -> v.message().contains("Overloaded")),
                "Expected overloaded-methods violation");
    }

    @Test
    @DisplayName("Non-Future return type produces a violation")
    void shouldFailOnNonFutureReturnType() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new BadReturnServiceImpl())));
        List<ServiceRegistrationViolation> violations = ex.violations();
        assertTrue(
                violations.stream().anyMatch(v -> v.message().contains("Future")),
                "Expected Future return type violation");
    }

    @Test
    @DisplayName("Multiple payload parameters produce a violation")
    void shouldFailOnMultiplePayloadParams() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new MultiPayloadServiceImpl())));
        List<ServiceRegistrationViolation> violations = ex.violations();
        assertTrue(
                violations.stream().anyMatch(v -> v.message().contains("payload")),
                "Expected multiple-payload violation");
    }

    @Test
    @DisplayName("Two implementations of the same contract interface produce a duplicate violation")
    void shouldFailOnDuplicateContracts() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new ValidServiceImpl(), new AnotherValidServiceImpl())));
        List<ServiceRegistrationViolation> violations = ex.violations();
        assertTrue(
                violations.stream().anyMatch(v -> v.message().contains("Duplicate")),
                "Expected duplicate-contract violation");
    }

    @Test
    @DisplayName("Multiple invalid services collect all violations before throwing")
    void shouldCollectAllViolations() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(
                        new NoAnnotationServiceImpl(), new BadReturnServiceImpl(), new MultiPayloadServiceImpl())));
        // Each bad service contributes at least one violation
        assertTrue(
                ex.violations().size() >= 3,
                "Expected at least 3 violations, got: " + ex.violations().size());
    }

    // --- Fixtures for @OneWay tests ---

    @ServiceContract(namespace = "integration", value = "one-way")
    interface OneWayService {
        @OneWay
        @ServiceOperation("fireEvent")
        Future<Void> fireEvent(String event);

        @ServiceOperation("requestReply")
        Future<String> requestReply(String input);
    }

    static class OneWayServiceImpl implements OneWayService {
        @Override
        public Future<Void> fireEvent(String event) {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> requestReply(String input) {
            return Future.succeededFuture(input);
        }
    }

    @ServiceContract(namespace = "integration", value = "bad-one-way")
    interface BadOneWayService {
        @OneWay
        @ServiceOperation("badOneWay")
        Future<String> badOneWay(String input);
    }

    static class BadOneWayServiceImpl implements BadOneWayService {
        @Override
        public Future<String> badOneWay(String input) {
            return Future.succeededFuture(input);
        }
    }

    // --- Fixtures for DispatchEnvelope<?> rejection test ---

    @ServiceContract(namespace = "integration", value = "body-param")
    interface BodyParamService {
        @ServiceOperation("handle")
        Future<String> handle(DispatchEnvelope<?> body, String id);
    }

    static class BodyParamServiceImpl implements BodyParamService {
        @Override
        public Future<String> handle(DispatchEnvelope<?> body, String id) {
            return Future.succeededFuture(id);
        }
    }

    // --- Fixtures for duplicate operation name tests ---

    @ServiceContract(namespace = "integration", value = "dup-op")
    interface DuplicateOperationService {
        @ServiceOperation("findUser")
        Future<String> getById(String id);

        @ServiceOperation("findUser")
        Future<String> getByEmail(String email);
    }

    static class DuplicateOperationServiceImpl implements DuplicateOperationService {
        @Override
        public Future<String> getById(String id) {
            return Future.succeededFuture(id);
        }

        @Override
        public Future<String> getByEmail(String email) {
            return Future.succeededFuture(email);
        }
    }

    @ServiceContract(namespace = "integration", value = "collision")
    interface OperationNameCollisionService {
        @ServiceOperation("findUser")
        Future<String> findUser(String id);

        @ServiceOperation("findUser")
        Future<String> lookupUser(String email);
    }

    static class OperationNameCollisionServiceImpl implements OperationNameCollisionService {
        @Override
        public Future<String> findUser(String id) {
            return Future.succeededFuture(id);
        }

        @Override
        public Future<String> lookupUser(String email) {
            return Future.succeededFuture(email);
        }
    }

    // --- Fixtures for cross-contract address collision test ---

    @ServiceContract(namespace = "integration", value = "shared")
    interface ContractA {
        @ServiceOperation("process")
        Future<String> process(String data);
    }

    static class ContractAImpl implements ContractA {
        @Override
        public Future<String> process(String data) {
            return Future.succeededFuture(data);
        }
    }

    @ServiceContract(namespace = "integration", value = "shared")
    interface ContractB {
        @ServiceOperation("process")
        Future<String> process(String data);
    }

    static class ContractBImpl implements ContractB {
        @Override
        public Future<String> process(String data) {
            return Future.succeededFuture(data);
        }
    }

    // --- Tests: Blank service name rejection ---

    @ServiceContract(value = "   ")
    interface BlankNameService {
        @ServiceOperation("op")
        Future<Void> op();
    }

    static class BlankNameServiceImpl implements BlankNameService {
        @Override
        public Future<Void> op() {
            return Future.succeededFuture();
        }
    }

    @Test
    @DisplayName("Should reject blank @ServiceContract.value()")
    void shouldRejectBlankServiceName() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new BlankNameServiceImpl())));
        assertTrue(ex.getMessage().contains("@ServiceContract.value() must not be blank"));
    }

    // --- Tests: DispatchEnvelope<?> rejection ---

    @Test
    @DisplayName("Should reject DispatchEnvelope<?> parameters in contract interfaces")
    void shouldRejectBodyParameter() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new BodyParamServiceImpl())));
        assertTrue(ex.getMessage().contains("DispatchEnvelope<?> parameters are not allowed"));
    }

    // --- Tests: Duplicate operation name ---

    @Test
    @DisplayName("Should reject duplicate operation names from @ServiceOperation")
    void shouldRejectDuplicateOperationNames() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new DuplicateOperationServiceImpl())));
        assertTrue(ex.getMessage().contains("Duplicate operation id 'findUser'"));
    }

    @Test
    @DisplayName("Should reject operation name colliding with another method's name")
    void shouldRejectOperationNameCollision() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new OperationNameCollisionServiceImpl())));
        assertTrue(ex.getMessage().contains("Duplicate operation id 'findUser'"));
    }

    // --- Tests: Cross-contract address collision ---

    @Test
    @DisplayName("Should reject duplicate addresses across different contracts")
    void shouldRejectDuplicateAddressesAcrossContracts() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new ContractAImpl(), new ContractBImpl())));
        assertTrue(ex.getMessage().contains("Duplicate address"));
    }

    // --- Tests: @OneWay ---

    @Test
    @DisplayName("@OneWay Future<Void> method is accepted and meta.oneWay() is true")
    void shouldAcceptOneWayFutureVoid() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new OneWayServiceImpl()));
        List<ServiceMethodMeta> metas = result.get(OneWayService.class);
        ServiceMethodMeta fireEventMeta = metas.stream()
                .filter(m -> m.operation().equals("fireEvent"))
                .findFirst()
                .orElseThrow();
        assertTrue(fireEventMeta.oneWay(), "@OneWay method must have oneWay=true");
        assertEquals(Void.class, fireEventMeta.returnType());
    }

    @Test
    @DisplayName("Non-@OneWay Future<Void> method has oneWay=false")
    void shouldNotSetOneWayForNonAnnotatedVoidMethod() {
        Map<Class<?>, List<ServiceMethodMeta>> result = scanValid();
        ServiceMethodMeta doNothingMeta = result.get(ValidService.class).stream()
                .filter(m -> m.operation().equals("doNothing"))
                .findFirst()
                .orElseThrow();
        assertFalse(doNothingMeta.oneWay(), "Non-annotated method must have oneWay=false");
    }

    @Test
    @DisplayName("Non-@OneWay method in OneWayService has oneWay=false")
    void shouldNotSetOneWayForNonAnnotatedMethodInSameContract() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new OneWayServiceImpl()));
        ServiceMethodMeta requestReplyMeta = result.get(OneWayService.class).stream()
                .filter(m -> m.operation().equals("requestReply"))
                .findFirst()
                .orElseThrow();
        assertFalse(requestReplyMeta.oneWay(), "Non-@OneWay method must have oneWay=false");
    }

    @Test
    @DisplayName("@OneWay with non-Void return type produces a violation")
    void shouldRejectOneWayWithNonVoidReturn() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new BadOneWayServiceImpl())));
        assertTrue(
                ex.violations().stream().anyMatch(v -> v.message().contains("@OneWay")),
                "Expected @OneWay validation violation");
    }

    // --- Additional fixture for duplicate test ---

    @ServiceContract(namespace = "integration", value = "test-service")
    interface ValidService2 extends ValidService {}

    static class AnotherValidServiceImpl implements ValidService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hi " + name);
        }

        @Override
        public Future<List<String>> getData() {
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }
}
