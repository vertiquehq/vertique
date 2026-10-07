// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.ACTION_VALUE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.EXECUTOR_ROLE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.Result;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.AuthenticatedOnlyPolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Caller;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.DenyEveryonePolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Engine;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.ExecutorRolePolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Harness;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for typed access-policy collection during manual service registration.
 *
 * <p>Collection is rooted at the actual {@link ServiceContract} class, never at the implementation
 * class. Each test registers real implementations through {@link ServiceRegistrar}, then dispatches
 * allowed and denied calls through the produced metadata and {@link
 * dev.vertique.services.interceptor.ServiceAuthorizationInterceptor} on a real event bus. Tests whose
 * name starts with {@code characterization} pin behavior that must not change.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServiceRegistrarPolicyIT {

    private static final Caller DENIED_CALLER = Caller.user("viewer").withRoles("viewer");
    private static final Caller GRANTED_CALLER = Caller.user("granted").withRoles(EXECUTOR_ROLE);

    // --- Fixtures: inherited implementation fulfilling a policy-bearing contract ---

    /** Implementation operations inherited by an implementation class that does not implement the contract. */
    public static class InheritedOperations {
        private final AtomicInteger executions = new AtomicInteger();

        /**
         * Counts and echoes.
         *
         * @param payload input
         * @return result
         */
        public Future<String> guarded(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("guarded:" + payload);
        }

        /**
         * Returns how many calls reached business logic.
         *
         * @return the count
         */
        public int executions() {
            return executions.get();
        }
    }

    /** Contract that introduces the policy below an inherited implementation. */
    @ServiceContract(namespace = "it", value = "registrar-inherited")
    public interface InheritedContract {
        /**
         * Guarded operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ExecutorRolePolicy.class)
        @ServiceOperation("guarded")
        Future<String> guarded(String payload);
    }

    /** Implementation whose operation comes from a superclass that never mentions the contract. */
    public static final class InheritedImpl extends InheritedOperations implements InheritedContract {}

    // --- Fixtures: policy-bearing parent interfaces and child contracts ---

    /** Parent interface carrying a type-level policy and one operation. */
    @RequiresPolicy(ExecutorRolePolicy.class)
    public interface TypeGuardedParent {
        /**
         * Operation guarded by the parent's type-level policy.
         *
         * @param payload input
         * @return result
         */
        @ServiceOperation("typeInherited")
        Future<String> typeInherited(String payload);
    }

    /** Child contract that inherits the parent's type-level policy without restating it. */
    @ServiceContract(namespace = "it", value = "registrar-type-inherited")
    public interface TypeInheritingContract extends TypeGuardedParent {}

    /** Child contract restating the identical type-level reference. */
    @ServiceContract(namespace = "it", value = "registrar-type-identical")
    @RequiresPolicy(ExecutorRolePolicy.class)
    public interface TypeIdenticalContract extends TypeGuardedParent {}

    /** Child contract declaring a different type-level reference than its parent. */
    @ServiceContract(namespace = "it", value = "registrar-type-distinct")
    @RequiresPolicy(AuthenticatedOnlyPolicy.class)
    public interface TypeDistinctContract extends TypeGuardedParent {}

    /** Parent interface carrying a method-level policy. */
    public interface MethodGuardedParent {
        /**
         * Operation guarded at the method level.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ExecutorRolePolicy.class)
        @ServiceOperation("methodInherited")
        Future<String> methodInherited(String payload);
    }

    /** Child contract that inherits the method declaration unchanged. */
    @ServiceContract(namespace = "it", value = "registrar-method-inherited")
    public interface MethodInheritingContract extends MethodGuardedParent {}

    /** Child contract that redeclares the method without restating the policy. */
    @ServiceContract(namespace = "it", value = "registrar-method-redeclared")
    public interface MethodRedeclaringContract extends MethodGuardedParent {
        @Override
        @ServiceOperation("methodInherited")
        Future<String> methodInherited(String payload);
    }

    /** Child contract that redeclares the method with the identical policy reference. */
    @ServiceContract(namespace = "it", value = "registrar-method-identical")
    public interface MethodIdenticalContract extends MethodGuardedParent {
        @Override
        @RequiresPolicy(ExecutorRolePolicy.class)
        @ServiceOperation("methodInherited")
        Future<String> methodInherited(String payload);
    }

    /** Child contract that redeclares the method with a different policy reference. */
    @ServiceContract(namespace = "it", value = "registrar-method-distinct")
    public interface MethodDistinctContract extends MethodGuardedParent {
        @Override
        @RequiresPolicy(AuthenticatedOnlyPolicy.class)
        @ServiceOperation("methodInherited")
        Future<String> methodInherited(String payload);
    }

    /** Shared implementation shape for the parent/child fixtures. */
    public static class CountingParentOperations {
        private final AtomicInteger executions = new AtomicInteger();

        /**
         * Counts and echoes.
         *
         * @param payload input
         * @return result
         */
        public Future<String> typeInherited(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("typeInherited:" + payload);
        }

        /**
         * Counts and echoes.
         *
         * @param payload input
         * @return result
         */
        public Future<String> methodInherited(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("methodInherited:" + payload);
        }

        /**
         * Returns how many calls reached business logic.
         *
         * @return the count
         */
        public int executions() {
            return executions.get();
        }
    }

    /** Implements {@link TypeInheritingContract}. */
    public static final class TypeInheritingImpl extends CountingParentOperations implements TypeInheritingContract {}

    /** Implements {@link TypeIdenticalContract}. */
    public static final class TypeIdenticalImpl extends CountingParentOperations implements TypeIdenticalContract {}

    /** Implements {@link TypeDistinctContract}. */
    public static final class TypeDistinctImpl extends CountingParentOperations implements TypeDistinctContract {}

    /** Implements {@link MethodInheritingContract}. */
    public static final class MethodInheritingImpl extends CountingParentOperations
            implements MethodInheritingContract {}

    /** Implements {@link MethodRedeclaringContract}. */
    public static final class MethodRedeclaringImpl extends CountingParentOperations
            implements MethodRedeclaringContract {}

    /** Implements {@link MethodIdenticalContract}. */
    public static final class MethodIdenticalImpl extends CountingParentOperations implements MethodIdenticalContract {}

    /** Implements {@link MethodDistinctContract}. */
    public static final class MethodDistinctImpl extends CountingParentOperations implements MethodDistinctContract {}

    // --- Fixtures: mixed declarations ---

    /** Contract mixing a policy reference with an inline role declaration on one method. */
    @ServiceContract(namespace = "it", value = "registrar-mixed-method")
    public interface MixedMethodContract {
        /**
         * Operation mixing declarations.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ExecutorRolePolicy.class)
        @RolesAllowed("admin")
        @ServiceOperation("mixed")
        Future<String> mixed(String payload);
    }

    /** Contract with a type-level policy and an inline action on one of its methods. */
    @ServiceContract(namespace = "it", value = "registrar-mixed-type")
    @RequiresPolicy(ExecutorRolePolicy.class)
    public interface MixedTypeContract {
        /**
         * Operation with an inline action under a type-level policy.
         *
         * @param payload input
         * @return result
         */
        @RequiresAction(ACTION_VALUE)
        @ServiceOperation("mixed")
        Future<String> mixed(String payload);
    }

    /** Implements {@link MixedMethodContract}. */
    public static final class MixedMethodImpl implements MixedMethodContract {
        @Override
        public Future<String> mixed(String payload) {
            return Future.succeededFuture(payload);
        }
    }

    /** Implements {@link MixedTypeContract}. */
    public static final class MixedTypeImpl implements MixedTypeContract {
        @Override
        public Future<String> mixed(String payload) {
            return Future.succeededFuture(payload);
        }
    }

    // --- Fixtures: handler pattern ---

    /** Contract guarded by a policy and served through the handler pattern. */
    @ServiceContract(namespace = "it", value = "registrar-handler")
    public interface HandlerContract {
        /**
         * Guarded operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ExecutorRolePolicy.class)
        @ServiceOperation("handled")
        Future<String> handled(String payload);
    }

    /** Handler for {@link HandlerContract}. */
    public static final class GuardedHandler implements ServiceHandler<HandlerContract> {
        private final AtomicInteger executions = new AtomicInteger();

        /**
         * Counts and echoes.
         *
         * @param payload input
         * @return result
         */
        public Future<String> handled(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("handled:" + payload);
        }

        /**
         * Returns how many calls reached business logic.
         *
         * @return the count
         */
        public int executions() {
            return executions.get();
        }
    }

    // --- Fixtures: policy declared on the implementation side ---

    /** Contract that declares no policy at all. */
    @ServiceContract(namespace = "it", value = "registrar-impl-declared")
    public interface UnguardedContract {
        /**
         * Operation with no declaration on the contract.
         *
         * @param payload input
         * @return result
         */
        @ServiceOperation("open")
        Future<String> open(String payload);
    }

    /** Implementation whose own method declares a deny-everyone policy that must never contribute. */
    public static final class ImplementationDeclaredPolicyService implements UnguardedContract {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        @RequiresPolicy(DenyEveryonePolicy.class)
        public Future<String> open(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("open:" + payload);
        }

        /**
         * Returns how many calls reached business logic.
         *
         * @return the count
         */
        public int executions() {
            return executions.get();
        }
    }

    // --- Fixtures: inline-only declarations ---

    /** Contract using only inline declarations: resilience, a custom-order mix and an inline action. */
    @ServiceContract(namespace = "it", value = "registrar-inline-only")
    @dev.vertique.resilience.annotation.Timeout(10000)
    public interface InlineOnlyContract {
        /**
         * Operation with resilience annotations around an inline action.
         *
         * @param payload input
         * @return result
         */
        @dev.vertique.resilience.annotation.Timeout(5000)
        @RequiresAction(ACTION_VALUE)
        @Retry(maxRetries = 2)
        @ServiceOperation("resilient")
        Future<String> resilient(String payload);
    }

    /** Implementation of {@link InlineOnlyContract}. */
    public static final class InlineOnlyService implements InlineOnlyContract {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public Future<String> resilient(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("resilient:" + payload);
        }

        /**
         * Returns how many calls reached business logic.
         *
         * @return the count
         */
        public int executions() {
            return executions.get();
        }
    }

    /** Contract mixing resilience annotations with a typed policy. */
    @ServiceContract(namespace = "it", value = "registrar-typed-resilient")
    public interface TypedResilientContract {
        /**
         * Typed operation with resilience annotations on both sides of the policy reference.
         *
         * @param payload input
         * @return result
         */
        @dev.vertique.resilience.annotation.Timeout(5000)
        @RequiresPolicy(ExecutorRolePolicy.class)
        @Retry(maxRetries = 2)
        @ServiceOperation("typedResilient")
        Future<String> typedResilient(String payload);
    }

    /** Implementation of {@link TypedResilientContract}. */
    public static final class TypedResilientService implements TypedResilientContract {
        @Override
        public Future<String> typedResilient(String payload) {
            return Future.succeededFuture(payload);
        }
    }

    // --- Tests ---

    @Test
    @DisplayName(
            "an inherited implementation fulfilling a policy-bearing contract is enforced from the actual contract")
    void shouldCollectPolicyFromTheActualContract(Vertx vertx) throws Exception {
        // Given an implementation whose operation is inherited from a class that never mentions the contract
        InheritedImpl impl = new InheritedImpl();

        // When it is registered
        ServiceMethodMeta meta = onlyMeta(register(impl), InheritedContract.class);

        // Then the contract's policy is part of the produced metadata
        assertEquals(
                Optional.of(ExecutorRolePolicy.class),
                AccessPolicyResolver.select(meta.methodAnnotations(), meta.classAnnotations()),
                "the policy declared on the contract must be collected");
        // And dispatch denies the unprivileged caller before any effect and permits the granted one
        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("a type-level policy on a parent interface applies to the child contract's operations")
    void shouldEnforceTypePolicyInheritedFromAParentInterface(Vertx vertx) throws Exception {
        TypeInheritingImpl impl = new TypeInheritingImpl();

        ServiceMethodMeta meta = onlyMeta(register(impl), TypeInheritingContract.class);

        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("identical type-level references on parent and child coalesce and are enforced")
    void shouldCoalesceIdenticalTypeReferences(Vertx vertx) throws Exception {
        TypeIdenticalImpl impl = new TypeIdenticalImpl();

        ServiceMethodMeta meta = onlyMeta(register(impl), TypeIdenticalContract.class);

        assertEquals(
                Optional.of(ExecutorRolePolicy.class),
                AccessPolicyResolver.select(meta.methodAnnotations(), meta.classAnnotations()));
        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("distinct type-level references on parent and child reject registration")
    void shouldRejectDistinctTypeReferences() {
        assertThrows(
                ServiceRegistrationException.class,
                () -> register(new TypeDistinctImpl()),
                "a child type policy that differs from its parent's must not register");
    }

    @Test
    @DisplayName("a method policy declared on a parent interface survives a child redeclaration without it")
    void shouldKeepMethodPolicyWhenChildRedeclaresTheMethod(Vertx vertx) throws Exception {
        MethodRedeclaringImpl impl = new MethodRedeclaringImpl();

        ServiceMethodMeta meta = onlyMeta(register(impl), MethodRedeclaringContract.class);

        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("a method policy inherited unchanged from a parent interface is enforced")
    void shouldEnforceInheritedMethodPolicy(Vertx vertx) throws Exception {
        MethodInheritingImpl impl = new MethodInheritingImpl();

        ServiceMethodMeta meta = onlyMeta(register(impl), MethodInheritingContract.class);

        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("identical method references on parent and child coalesce and are enforced")
    void shouldCoalesceIdenticalMethodReferences(Vertx vertx) throws Exception {
        MethodIdenticalImpl impl = new MethodIdenticalImpl();

        ServiceMethodMeta meta = onlyMeta(register(impl), MethodIdenticalContract.class);

        assertEquals(
                Optional.of(ExecutorRolePolicy.class),
                AccessPolicyResolver.select(meta.methodAnnotations(), meta.classAnnotations()));
        assertDeniedThenPermitted(vertx, meta, impl::executions);
    }

    @Test
    @DisplayName("distinct method references on parent and child reject registration")
    void shouldRejectDistinctMethodReferences() {
        assertThrows(
                ServiceRegistrationException.class,
                () -> register(new MethodDistinctImpl()),
                "a child method policy that differs from its parent's must not register");
    }

    @Test
    @DisplayName("a policy mixed with an inline security annotation rejects registration")
    void shouldRejectPolicyMixedWithInlineSecurity() {
        assertAll(
                () -> assertThrows(
                        ServiceRegistrationException.class,
                        () -> register(new MixedMethodImpl()),
                        "a method policy plus an inline role must not register"),
                () -> assertThrows(
                        ServiceRegistrationException.class,
                        () -> register(new MixedTypeImpl()),
                        "a type policy plus an inline method action must not register"));
    }

    @Test
    @DisplayName("the handler pattern enforces the policy declared on the actual contract")
    void shouldEnforceContractPolicyForHandlerPattern(Vertx vertx) throws Exception {
        GuardedHandler handler = new GuardedHandler();

        ServiceMethodMeta meta = onlyMeta(register(handler), HandlerContract.class);

        assertDeniedThenPermitted(vertx, meta, handler::executions);
    }

    @Test
    @DisplayName("characterization: a policy declared on the implementation side never contributes")
    void characterization_implementationDeclaredPolicy_neverContributes(Vertx vertx) throws Exception {
        // Given an implementation method annotated with a deny-everyone policy under a contract with none
        ImplementationDeclaredPolicyService impl = new ImplementationDeclaredPolicyService();

        // When it is registered and dispatched with no caller
        ServiceMethodMeta meta = onlyMeta(register(impl), UnguardedContract.class);
        try (Harness harness = Harness.start(vertx, meta, Engine.absent())) {
            Result<?> result = harness.dispatchAndAwait(null);

            // Then collection stayed rooted at the contract: nothing is enforced and nothing is emitted
            assertAll(
                    () -> assertEquals(
                            Optional.empty(),
                            AccessPolicyResolver.select(meta.methodAnnotations(), meta.classAnnotations())),
                    () -> assertTrue(result.isSuccess(), "the implementation-side declaration is ignored"),
                    () -> assertEquals(1, impl.executions()),
                    () -> assertEquals(0, harness.events().size()));
        }
    }

    @Test
    @DisplayName("characterization: inline-only metadata, resilience and annotation order are unchanged")
    void characterization_inlineOnlyMetadata_isUnchanged(Vertx vertx) throws Exception {
        // Given a contract with only inline declarations and resilience annotations
        InlineOnlyService impl = new InlineOnlyService();
        Method method = InlineOnlyContract.class.getMethod("resilient", String.class);

        // When it is registered
        ServiceMethodMeta meta = onlyMeta(register(impl), InlineOnlyContract.class);

        // Then the metadata equals what the legacy resolvers produce, in the same order
        assertAll(
                () -> assertEquals(
                        AnnotationResolver.resolveMethodAnnotations(method),
                        meta.methodAnnotations(),
                        "method annotations and their order are unchanged"),
                () -> assertEquals(
                        AnnotationResolver.resolveClassAnnotations(InlineOnlyContract.class),
                        meta.classAnnotations(),
                        "class annotations are unchanged"),
                () -> assertEquals(
                        ResilienceAnnotations.resolve(InlineOnlyContract.class, method),
                        meta.resilienceAnnotations(),
                        "resilience resolution is unchanged"),
                () -> assertTrue(
                        meta.methodAnnotations().stream().noneMatch(a -> a instanceof RequiresPolicy),
                        "no policy reference appears on an inline-only operation"));

        // And the inline action still guards dispatch exactly as before
        try (Harness harness = Harness.start(vertx, meta, Engine.installed())) {
            Result<?> denied = harness.dispatchAndAwait(DENIED_CALLER);
            Result<?> granted = harness.dispatchAndAwait(GRANTED_CALLER);
            assertAll(
                    () -> assertTrue(denied.isFailure(), "caller without the grant is denied"),
                    () -> assertTrue(granted.isSuccess(), "caller with the grant is permitted"),
                    () -> assertEquals(1, impl.executions(), "only the permitted call reaches business logic"),
                    () -> assertEquals(2, harness.events().size(), "one event per attempt"),
                    () -> assertEquals(
                            2,
                            harness.engine().authorizer().calls(),
                            "the authorizer is asked once per attempt through the existing action path"));
        }
    }

    @Test
    @DisplayName("characterization: a typed operation keeps the non-security annotation order and resilience result")
    void characterization_typedOperation_keepsNonSecurityAnnotationOrder() throws Exception {
        Method method = TypedResilientContract.class.getMethod("typedResilient", String.class);

        ServiceMethodMeta meta = onlyMeta(register(new TypedResilientService()), TypedResilientContract.class);

        List<Annotation> legacyNonSecurity = AnnotationResolver.resolveMethodAnnotations(method).stream()
                .filter(a -> !(a instanceof RequiresPolicy))
                .toList();
        List<Annotation> producedNonSecurity = meta.methodAnnotations().stream()
                .filter(a -> !(a instanceof RequiresPolicy))
                .toList();
        assertAll(
                () -> assertEquals(
                        legacyNonSecurity,
                        producedNonSecurity,
                        "non-security annotations keep their relative order and values"),
                () -> assertEquals(
                        ResilienceAnnotations.resolve(TypedResilientContract.class, method),
                        meta.resilienceAnnotations(),
                        "resilience resolution is unchanged by a policy reference"));
    }

    // --- Helpers ---

    private static Map<Class<?>, List<ServiceMethodMeta>> register(Object implementation) {
        return new ServiceRegistrar().scan(Set.of(implementation));
    }

    private static ServiceMethodMeta onlyMeta(Map<Class<?>, List<ServiceMethodMeta>> registered, Class<?> contract) {
        List<ServiceMethodMeta> metas = registered.get(contract);
        assertEquals(1, metas.size(), "exactly one operation is registered for " + contract.getSimpleName());
        return metas.get(0);
    }

    /**
     * Dispatches an unprivileged and then a granted call through the registered metadata and asserts
     * the denial happened before any business work, with one event, and the grant ran the work once.
     */
    private static void assertDeniedThenPermitted(Vertx vertx, ServiceMethodMeta meta, IntSupplier executions)
            throws Exception {
        try (Harness harness = Harness.start(vertx, meta, Engine.absent())) {
            // When an unprivileged caller dispatches
            Result<?> denied = harness.dispatchAndAwait(DENIED_CALLER);

            // Then it is refused before any effect, with one denying event and no recovery
            assertAll(
                    "denied call",
                    () -> assertTrue(denied.isFailure(), "the unprivileged call must fail"),
                    () -> assertEquals(0, executions.getAsInt(), "business work must not run"),
                    () -> assertEquals(1, harness.events().size(), "exactly one decision event"),
                    () -> assertFalse(
                            harness.events().get(0).decision().permitted(), "the event must record the denial"),
                    () -> assertFalse(harness.recoverInvoked(), "recoverError must be bypassed"));

            // When a granted caller dispatches
            Result<?> granted = harness.dispatchAndAwait(GRANTED_CALLER);

            // Then the work runs exactly once and a second, permitting event is recorded
            assertAll(
                    "granted call",
                    () -> assertTrue(granted.isSuccess(), "the granted call must succeed"),
                    () -> assertEquals(1, executions.getAsInt(), "business work runs once"),
                    () -> assertEquals(2, harness.events().size(), "one event per attempt"),
                    () -> assertTrue(
                            harness.events().get(1).decision().permitted(), "the second event records the permit"));
        }
    }
}
