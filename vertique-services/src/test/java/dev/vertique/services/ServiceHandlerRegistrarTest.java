// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.security.SecurityContext;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link ServiceRegistrar} handler-pattern detection, validation, and metadata
 * building.
 *
 * <p>Covers all validation rules that apply when an implementation uses
 * {@link ServiceHandler}{@code <C>}: contract type resolution, handler method matching, payload
 * parameter consistency, return type consistency, overload rejection, double-pattern rejection,
 * and coexistence with direct-implementation services.
 *
 * <p>No Vert.x runtime is required — {@code ServiceRegistrar.scan()} is synchronous.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceHandlerRegistrarTest {

    // --- Contract Fixtures ---

    /** Contract for handler-pattern tests. */
    @ServiceContract(namespace = "test", value = "handler-test")
    interface HandlerTestService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("doNothing")
        Future<Void> doNothing();
    }

    /** Separate contract for coexistence and duplicate-contract tests. */
    @ServiceContract(namespace = "test", value = "other-test")
    interface OtherTestService {
        @ServiceOperation("ping")
        Future<String> ping();
    }

    /**
     * Interface that is NOT annotated with {@link ServiceContract}, used to verify that the
     * registrar rejects handlers whose contract type parameter lacks the annotation.
     */
    interface NotAContract {
        Future<String> doStuff();
    }

    // --- Handler Fixtures ---

    /** Valid handler with a {@link SecurityContext} injection parameter on {@code greet}. */
    static class ValidHandler implements ServiceHandler<HandlerTestService> {

        public Future<String> greet(String name, SecurityContext sc) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /** Valid handler whose method signatures exactly match the contract (no extra params). */
    static class ExactSignatureHandler implements ServiceHandler<HandlerTestService> {

        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /** Handler that omits the {@code doNothing()} method required by the contract. */
    static class MissingMethodHandler implements ServiceHandler<HandlerTestService> {

        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }
        // Missing doNothing()
    }

    /**
     * Handler where {@code greet} declares {@code Integer} instead of {@code String} as its payload
     * parameter — payload type mismatch with the contract.
     */
    static class WrongPayloadHandler implements ServiceHandler<HandlerTestService> {

        public Future<String> greet(Integer number) {
            return Future.succeededFuture("wrong");
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /**
     * Handler where {@code greet} returns {@code Future<Integer>} instead of
     * {@code Future<String>} — return type mismatch with the contract.
     */
    static class WrongReturnTypeHandler implements ServiceHandler<HandlerTestService> {

        public Future<Integer> greet(String name) {
            return Future.succeededFuture(42);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /**
     * Handler with two overloaded {@code greet} methods — only one method per operation name is
     * allowed on handler classes.
     */
    static class OverloadedHandler implements ServiceHandler<HandlerTestService> {

        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<String> greet(String name, SecurityContext sc) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /**
     * Double-pattern handler: implements both {@link ServiceHandler}{@code <HandlerTestService>}
     * and the contract interface {@link HandlerTestService} directly — forbidden combination.
     */
    static class DoublePatternHandler implements ServiceHandler<HandlerTestService>, HandlerTestService {

        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }

        @Override
        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /** Second contract for testing additional {@link ServiceContract} interface detection. */
    @ServiceContract(namespace = "test", value = "extra-contract")
    interface ExtraTestService {
        @ServiceOperation("extra")
        Future<String> extra();
    }

    /**
     * Handler that implements {@link ServiceHandler}{@code <}{@link HandlerTestService}{@code >} but
     * also directly implements another {@link ServiceContract}-annotated interface — should be
     * rejected because only one contract is allowed per implementation.
     */
    static class HandlerWithExtraContract implements ServiceHandler<HandlerTestService>, ExtraTestService {

        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> extra() {
            return Future.succeededFuture("extra");
        }
    }

    /** {@link ServiceContract}-annotated interface reachable only through a non-annotated intermediary. */
    @ServiceContract(namespace = "test", value = "transitive-contract")
    interface TransitiveBaseService {
        @ServiceOperation("transOp")
        Future<String> transOp();
    }

    /** Non-annotated intermediary that extends the {@link ServiceContract} interface. */
    interface TransitiveMiddle extends TransitiveBaseService {}

    /**
     * Handler that implements {@link ServiceHandler}{@code <}{@link HandlerTestService}{@code >} but
     * also implements {@link TransitiveMiddle} (which transitively extends a
     * {@link ServiceContract}-annotated interface). Should be rejected.
     */
    static class HandlerWithTransitiveExtraContract implements ServiceHandler<HandlerTestService>, TransitiveMiddle {

        public Future<String> greet(String name) {
            return Future.succeededFuture("hi:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> transOp() {
            return Future.succeededFuture("transitive");
        }
    }

    /**
     * Intermediate interface that extends {@link ServiceHandler} with a concrete type
     * argument — the contract type is bound in the extends clause, not by the implementation
     * class directly.
     */
    interface IntermediateHandler extends ServiceHandler<HandlerTestService> {}

    /**
     * Handler that implements {@link ServiceHandler}{@code <}{@link HandlerTestService}{@code >}
     * indirectly through an intermediate interface. The registrar must resolve the contract by
     * recursing into the interface hierarchy.
     */
    static class IndirectHandler implements IntermediateHandler {

        public Future<String> greet(String name) {
            return Future.succeededFuture("indirect:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /** Multi-level interface chain for deep interface hierarchy resolution testing. */
    interface Level1Handler extends ServiceHandler<HandlerTestService> {}

    /** Second level in the interface chain. */
    interface Level2Handler extends Level1Handler {}

    /**
     * Handler implementing {@link ServiceHandler} through a two-level interface chain.
     * Tests that BFS interface hierarchy traversal works at depth &gt; 1.
     */
    static class DeepIndirectHandler implements Level2Handler {

        public Future<String> greet(String name) {
            return Future.succeededFuture("deep:" + name);
        }

        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /** Direct implementation of {@link OtherTestService} for coexistence tests. */
    static class OtherServiceImpl implements OtherTestService {

        @Override
        public Future<String> ping() {
            return Future.succeededFuture("pong");
        }
    }

    /**
     * Direct implementation of {@link HandlerTestService} used to verify that handler and
     * direct-impl services for the same contract are treated as duplicate-contract violations.
     */
    static class DirectImplOfHandlerTestService implements HandlerTestService {

        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("direct:" + name);
        }

        @Override
        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }
    }

    /**
     * Handler for a contract that lacks the {@link ServiceContract} annotation — should be
     * rejected.
     */
    static class InvalidContractHandler implements ServiceHandler<NotAContract> {

        public Future<String> doStuff() {
            return Future.succeededFuture("nope");
        }
    }

    // --- Helper ---

    private ServiceRegistrar registrar() {
        return new ServiceRegistrar();
    }

    // --- Tests: Handler Detection and Contract Resolution ---

    @Test
    @DisplayName("Should detect handler pattern and resolve contract from generic type")
    void shouldDetectHandlerAndResolveContract() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ValidHandler()));

        assertTrue(result.containsKey(HandlerTestService.class));
        assertEquals(2, result.get(HandlerTestService.class).size());
    }

    @Test
    @DisplayName("Should build correct metadata for handler method with SecurityContext injection")
    void shouldBuildCorrectMetadataForHandlerWithInjection() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ValidHandler()));

        List<ServiceMethodMeta> metas = result.get(HandlerTestService.class);
        ServiceMethodMeta greetMeta = metas.stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();

        // Contract method should be the interface method
        assertEquals("greet", greetMeta.method().name());
        assertEquals(HandlerTestService.class, greetMeta.method().declaringClass());

        // Handler method should be declared on the handler class, not the contract
        assertNotEquals(greetMeta.method(), greetMeta.handlerMethod());
        assertEquals(ValidHandler.class, greetMeta.handlerMethod().declaringClass());
        assertEquals(2, greetMeta.resolveHandlerMethod().getParameterCount());

        // Contract params should NOT include DISPATCH_CONTEXT — only the payload
        assertEquals(1, greetMeta.params().size());
        assertEquals(ParamSource.PAYLOAD, greetMeta.params().get(0).source());

        // Handler params should include both PAYLOAD and DISPATCH_CONTEXT (SC)
        assertEquals(2, greetMeta.handlerParams().size());
        assertEquals(ParamSource.PAYLOAD, greetMeta.handlerParams().get(0).source());
        assertEquals(
                ParamSource.DISPATCH_CONTEXT, greetMeta.handlerParams().get(1).source());
        assertEquals(
                dev.vertique.security.SecurityContext.class.getName(),
                greetMeta.handlerParams().get(1).lookupKey(),
                "SecurityContext handler param must use SecurityContext.class.getName() as lookupKey");
    }

    @Test
    @DisplayName("Should build correct metadata for handler method with no payload (doNothing)")
    void shouldBuildCorrectMetadataForHandlerNoPayload() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ValidHandler()));

        ServiceMethodMeta doNothingMeta = result.get(HandlerTestService.class).stream()
                .filter(m -> m.operation().equals("doNothing"))
                .findFirst()
                .orElseThrow();

        // Both contract method and handler method are the same (no extra params)
        assertEquals("doNothing", doNothingMeta.method().name());
        assertEquals(HandlerTestService.class, doNothingMeta.method().declaringClass());
        assertEquals(ValidHandler.class, doNothingMeta.handlerMethod().declaringClass());

        // No params in either contract or handler
        assertTrue(doNothingMeta.params().isEmpty());
        assertTrue(doNothingMeta.handlerParams().isEmpty());
    }

    @Test
    @DisplayName("Should accept handler with exact contract signature (no extra params)")
    void shouldAcceptExactSignatureHandler() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ExactSignatureHandler()));

        assertTrue(result.containsKey(HandlerTestService.class));
        ServiceMethodMeta greetMeta = result.get(HandlerTestService.class).stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();

        // Handler params should be the same structure as contract params (1 PAYLOAD, no injectable)
        assertEquals(1, greetMeta.handlerParams().size());
        assertEquals(ParamSource.PAYLOAD, greetMeta.handlerParams().get(0).source());
        assertEquals(String.class, greetMeta.handlerParams().get(0).type());
    }

    // --- Tests: Address and Operation Name from Contract Annotation ---

    @Test
    @DisplayName("Should use contract annotation for address construction")
    void shouldUseContractAnnotationForAddresses() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ValidHandler()));

        ServiceMethodMeta greetMeta = result.get(HandlerTestService.class).stream()
                .filter(m -> m.operation().equals("greet"))
                .findFirst()
                .orElseThrow();

        assertEquals("services/test/handler-test/greet", greetMeta.address());
    }

    @Test
    @DisplayName("Should derive operation name from contract method name")
    void shouldDeriveOperationNameFromContractMethod() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new ValidHandler()));

        List<ServiceMethodMeta> metas = result.get(HandlerTestService.class);
        assertTrue(metas.stream().anyMatch(m -> m.operation().equals("greet")));
        assertTrue(metas.stream().anyMatch(m -> m.operation().equals("doNothing")));
    }

    // --- Tests: Validation — Missing Methods ---

    @Test
    @DisplayName("Should reject handler missing a contract method")
    void shouldRejectMissingMethod() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new MissingMethodHandler())));
        assertTrue(
                ex.getMessage().contains("doNothing"),
                "Expected violation about missing doNothing, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Payload Mismatch ---

    @Test
    @DisplayName("Should reject handler with mismatched payload type")
    void shouldRejectWrongPayloadType() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new WrongPayloadHandler())));
        assertTrue(
                ex.getMessage().contains("payload"),
                "Expected violation about payload mismatch, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Return Type Mismatch ---

    @Test
    @DisplayName("Should reject handler with mismatched generic return type")
    void shouldRejectWrongReturnType() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new WrongReturnTypeHandler())));
        assertTrue(
                ex.getMessage().contains("return type"),
                "Expected violation about return type mismatch, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Overloaded Handler Methods ---

    @Test
    @DisplayName("Should reject handler with overloaded methods")
    void shouldRejectOverloadedHandler() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new OverloadedHandler())));
        assertTrue(
                ex.getMessage().contains("overloaded"), "Expected violation about overloads, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Double-Pattern Rejection ---

    @Test
    @DisplayName("Should reject double-pattern (handler + direct implementation of same contract)")
    void shouldRejectDoublePattern() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new DoublePatternHandler())));
        assertTrue(
                ex.getMessage().contains("both"), "Expected violation about double pattern, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Non-@ServiceContract Contract ---

    @Test
    @DisplayName("Should reject handler for non-@ServiceContract contract")
    void shouldRejectNonServiceContractAnnotation() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new InvalidContractHandler())));
        assertTrue(
                ex.getMessage().contains("@ServiceContract"),
                "Expected violation about missing @ServiceContract annotation, got: " + ex.getMessage());
    }

    // --- Tests: Coexistence and Duplicate Contracts ---

    @Test
    @DisplayName("Should allow handler and direct-impl to coexist for different contracts")
    void shouldAllowCoexistenceOfBothPatterns() {
        Map<Class<?>, List<ServiceMethodMeta>> result =
                registrar().scan(Set.of(new ValidHandler(), new OtherServiceImpl()));

        assertTrue(result.containsKey(HandlerTestService.class));
        assertTrue(result.containsKey(OtherTestService.class));
    }

    @Test
    @DisplayName("Should reject duplicate contract across handler and direct-impl for same contract")
    void shouldRejectDuplicateContractAcrossPatterns() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new ValidHandler(), new DirectImplOfHandlerTestService())));
        assertTrue(
                ex.getMessage().contains("Duplicate"),
                "Expected duplicate-contract violation, got: " + ex.getMessage());
    }

    // --- Tests: Validation — Additional @ServiceContract Interfaces ---

    @Test
    @DisplayName("Should reject handler that also implements additional @ServiceContract interface")
    void shouldRejectHandlerWithAdditionalServiceContractInterface() {
        ServiceRegistrationException ex = assertThrows(
                ServiceRegistrationException.class, () -> registrar().scan(Set.of(new HandlerWithExtraContract())));
        assertTrue(
                ex.getMessage().contains("additional @ServiceContract"),
                "Expected violation about additional @ServiceContract interface, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should reject handler with transitive @ServiceContract interface through non-annotated intermediary")
    void shouldRejectHandlerWithTransitiveServiceContractInterface() {
        ServiceRegistrationException ex = assertThrows(ServiceRegistrationException.class, () -> registrar()
                .scan(Set.of(new HandlerWithTransitiveExtraContract())));
        assertTrue(
                ex.getMessage().contains("additional @ServiceContract"),
                "Expected violation about additional @ServiceContract interface, got: " + ex.getMessage());
    }

    // --- Tests: Intermediate Interface Resolution ---

    @Test
    @DisplayName("Should resolve contract through intermediate interface extending ServiceHandler")
    void shouldResolveContractThroughIntermediateInterface() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new IndirectHandler()));

        assertTrue(result.containsKey(HandlerTestService.class));
        assertEquals(2, result.get(HandlerTestService.class).size());
    }

    @Test
    @DisplayName("Should resolve contract through multi-level interface chain")
    void shouldResolveContractThroughDeepInterfaceChain() {
        Map<Class<?>, List<ServiceMethodMeta>> result = registrar().scan(Set.of(new DeepIndirectHandler()));

        assertTrue(result.containsKey(HandlerTestService.class));
        assertEquals(2, result.get(HandlerTestService.class).size());
    }
}
