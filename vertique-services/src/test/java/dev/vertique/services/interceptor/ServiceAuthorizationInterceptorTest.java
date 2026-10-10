// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.ACTION_VALUE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.EXECUTOR_ROLE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.OPERATOR_ROLE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.READ_SCOPE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.UNSUPPORTED_POLICY_CALLER;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.WRITE_SCOPE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.metaFor;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.UnboundCorrelationContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceRegistrationViolation;
import dev.vertique.services.config.ServiceAuthorizationConfig;
import dev.vertique.services.dispatch.NonRecoverableDispatchFailure;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.AuthenticatedOnlyPolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Caller;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.ExecutorRolePolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.MalformedActionPolicy;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypedPolicyContract;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypedPolicyService;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.UnregisteredActionPolicy;
import io.vertx.core.Future;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
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
 * Unit tests for {@link ServiceAuthorizationInterceptor} — the services {@code @RequiresAction}
 * policy enforcement point (PEP).
 *
 * <p>Verifies the runtime gate in {@link ServiceAuthorizationInterceptor#beforeDispatch}
 * (permit/deny/pass-through, fail-closed on missing identity and on engine errors, exactly one
 * emitted event per gated attempt, ambient {@link InvocationOrigin} propagation into the decision
 * request and the emitted event — identity-002 P2.S5b-ii) and the construction-time startup scan
 * that rejects unparseable / unregistered actions and the absence of the authz engine when a
 * {@code @RequiresAction} is declared.
 */
class ServiceAuthorizationInterceptorTest {

    private static final String ACTION_VALUE = "svc.resource.exec";
    private static final ActionRef ACTION = ActionRef.parse(ACTION_VALUE);
    private static final String CLASS_ACTION_VALUE = "svc.resource.read";
    private static final ActionRef CLASS_ACTION = ActionRef.parse(CLASS_ACTION_VALUE);

    // --- Annotation fixtures (real @RequiresAction instances harvested via reflection) ---

    @RequiresAction(ACTION_VALUE)
    private void methodActionHolder() {}

    @RequiresAction(CLASS_ACTION_VALUE)
    private void classActionHolder() {}

    @RequiresAction("INVALID")
    private void unparseableActionHolder() {}

    @RequiresAction("unknown.x.y")
    private void unregisteredActionHolder() {}

    /** Returns the {@link RequiresAction} declared on the named private method of this test class. */
    private static RequiresAction requiresActionOn(String methodName) {
        try {
            Method m = ServiceAuthorizationInterceptorTest.class.getDeclaredMethod(methodName);
            RequiresAction ra = m.getAnnotation(RequiresAction.class);
            if (ra == null) {
                throw new IllegalStateException("no @RequiresAction on " + methodName);
            }
            return ra;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Dispatch-context builder ---

    private static ServiceDispatchContext dispatchContext(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        return new ServiceDispatchContext(
                "services/svc/exec",
                "svc.exec",
                "",
                "svc",
                "exec",
                DispatchEnvelope.of("payload"),
                false,
                methodAnnotations,
                classAnnotations,
                Map.of());
    }

    // --- ServiceMethodMeta builder (for the startup scan) ---

    private static ServiceMethodMeta metaWith(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        Method execMethod;
        try {
            execMethod = SampleService.class.getMethod("exec", String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        return ServiceMethodMeta.ofDirect(
                new SampleService(),
                ServiceMethodDescriptor.of(execMethod),
                "services/svc/exec",
                "svc.exec",
                "",
                "svc",
                "exec",
                String.class,
                String.class,
                List.of(),
                dev.vertique.resilience.annotation.ResilienceAnnotations.NONE,
                methodAnnotations,
                classAnnotations,
                false);
    }

    /** Minimal service stand-in used only to satisfy {@link ServiceMethodMeta} construction. */
    public static final class SampleService {
        /**
         * Sample operation referenced reflectively to build a {@link ServiceMethodMeta}.
         *
         * @param payload echoed payload
         * @return a succeeded future carrying {@code payload}
         */
        public Future<String> exec(String payload) {
            return Future.succeededFuture(payload);
        }
    }

    // --- Interceptor builder ---

    private static ServiceAuthorizationInterceptor interceptor(
            Authorizer authorizer,
            ActionRegistry registry,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            Set<ServiceMethodMeta> metas) {
        return new ServiceAuthorizationInterceptor(
                Optional.ofNullable(authorizer),
                Optional.ofNullable(registry),
                emitter,
                contextHolder,
                metas,
                ServiceAuthorizationConfig.defaults(),
                TestResilience.shared());
    }

    private static ServiceAuthorizationInterceptor interceptorWithDeadline(
            Authorizer authorizer,
            ActionRegistry registry,
            SecurityEventEmitter emitter,
            ContextHolder contextHolder,
            long deadlineMs,
            dev.vertique.resilience.Resilience resilience) {
        return new ServiceAuthorizationInterceptor(
                Optional.of(authorizer),
                Optional.of(registry),
                emitter,
                contextHolder,
                Set.of(),
                new ServiceAuthorizationConfig(deadlineMs),
                resilience);
    }

    private static final long BOUND_MS = 3_000L;

    /** Records every event a resilience runtime publishes. */
    private static final class ObservedEvents implements dev.vertique.resilience.spi.ResilienceObserver {
        private final java.util.List<dev.vertique.resilience.spi.event.ResilienceEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onEvent(dev.vertique.resilience.spi.event.ResilienceEvent event) {
            events.add(event);
        }

        long timeouts() {
            return events.stream()
                    .filter(dev.vertique.resilience.spi.event.ExecutionCompleted.class::isInstance)
                    .map(dev.vertique.resilience.spi.event.ExecutionCompleted.class::cast)
                    .filter(e -> e.outcome() == dev.vertique.resilience.spi.event.ResilienceOutcomeCategory.TIMEOUT)
                    .count();
        }
    }

    private static ActionRegistry registryAllowing(ActionRef... actions) {
        ActionRegistry registry = mock(ActionRegistry.class);
        when(registry.contains(any())).thenReturn(false);
        for (ActionRef action : actions) {
            when(registry.contains(action)).thenReturn(true);
        }
        return registry;
    }

    // --- Runtime gate tests ---

    @Nested
    @DisplayName("beforeDispatch gate")
    class BeforeDispatchGate {

        @Test
        @DisplayName("permit: method @RequiresAction with a permitting Authorizer continues dispatch")
        void permit_methodAnnotation_present() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            SecurityContext secCtx = mock(SecurityContext.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(secCtx));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.succeeded(), "permit should continue dispatch");
            verify(authorizer).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("deny: a denying Authorizer short-circuits dispatch and emits one permitted=false event")
        void deny_methodAnnotation_authorizerDenies() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(
                            Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            SecurityContext secCtx = mock(SecurityContext.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(secCtx));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.failed(), "deny must short-circuit dispatch with a failed future");
            assertInstanceOf(
                    dev.vertique.services.dispatch.NonRecoverableDispatchFailure.class,
                    result.cause(),
                    "an authz deny must be non-recoverable so a permissive recoverError cannot resurrect it");

            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertFalse(captor.getValue().decision().permitted(), "emitted event must record the deny");
        }

        @Test
        @DisplayName("class-level @RequiresAction is used when the method declares none")
        void classLevel_annotation_used_when_no_method_annotation() {
            Authorizer authorizer = mock(Authorizer.class);
            ArgumentCaptor<AuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            when(authorizer.authorize(requestCaptor.capture()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(CLASS_ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(), List.of(requiresActionOn("classActionHolder"))));

            assertTrue(result.succeeded());
            assertEquals(CLASS_ACTION_VALUE, requestCaptor.getValue().action(), "class-level action must be evaluated");
        }

        @Test
        @DisplayName("method-level @RequiresAction overrides a class-level one")
        void methodAnnotation_overrides_classAnnotation() {
            Authorizer authorizer = mock(Authorizer.class);
            ArgumentCaptor<AuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            when(authorizer.authorize(requestCaptor.capture()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION, CLASS_ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result = pep.beforeDispatch(dispatchContext(
                    List.of(requiresActionOn("methodActionHolder")), List.of(requiresActionOn("classActionHolder"))));

            assertTrue(result.succeeded());
            assertEquals(
                    ACTION_VALUE, requestCaptor.getValue().action(), "method-level action must win over class-level");
        }

        @Test
        @DisplayName("no @RequiresAction anywhere passes through without invoking the Authorizer")
        void noAnnotation_dispatches() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(), emitter, holder, Set.of());

            ServiceDispatchContext ctx = dispatchContext(List.of(), List.of());
            Future<ServiceDispatchContext> result = pep.beforeDispatch(ctx);

            assertTrue(result.succeeded(), "no action gate must succeed immediately");
            assertEquals(ctx, result.result(), "context passes through unchanged");
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
            verify(emitter, never()).emit(any(AuthorizationDecisionEvent.class));
        }

        @Test
        @DisplayName("fail-closed: no bound SecurityContext denies and never authorizes anonymously")
        void failClosed_noBoundSecurityContext_denies() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.empty());

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.failed(), "missing identity must fail closed");
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertFalse(captor.getValue().decision().permitted());
            assertEquals(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    captor.getValue().decision().reasonCode(),
                    "missing identity emits AUTHENTICATION_REQUIRED");
        }

        @Test
        @DisplayName("fail-closed: an Authorizer that never answers denies at the deadline with one "
                + "INTERNAL_AUTHZ_ERROR event, and is reported to resilience observers as a timeout")
        void failClosed_authorizerNeverAnswers_deniesAtTheDeadline() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                ObservedEvents observed = new ObservedEvents();
                Authorizer authorizer = mock(Authorizer.class);
                io.vertx.core.Promise<AuthorizationDecision> hung = io.vertx.core.Promise.promise();
                when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(hung.future());
                SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
                when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
                ContextHolder holder = mock(ContextHolder.class);
                when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
                ServiceAuthorizationInterceptor pep = interceptorWithDeadline(
                        authorizer,
                        registryAllowing(ACTION),
                        emitter,
                        holder,
                        100L,
                        dev.vertique.resilience.Resilience.create(vertx, Set.of(observed)));

                Future<ServiceDispatchContext> result =
                        pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

                // DECISIVE: without the fence this get(...) times out instead of seeing the failure.
                assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
                ArgumentCaptor<AuthorizationDecisionEvent> captor =
                        ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
                verify(emitter, times(1)).emit(captor.capture());
                assertEquals(
                        AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                        captor.getValue().decision().reasonCode());
                assertEquals(1, observed.timeouts());
            } finally {
                vertx.close()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        @Test
        @DisplayName("a pending Authorizer that permits before the deadline still continues dispatch")
        void pendingAuthorizerThatPermitsInTimeContinues() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                Authorizer authorizer = mock(Authorizer.class);
                io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
                when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(pending.future());
                SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
                when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
                ContextHolder holder = mock(ContextHolder.class);
                when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
                ServiceAuthorizationInterceptor pep = interceptorWithDeadline(
                        authorizer,
                        registryAllowing(ACTION),
                        emitter,
                        holder,
                        BOUND_MS * 10,
                        dev.vertique.resilience.Resilience.create(vertx));

                Future<ServiceDispatchContext> result =
                        pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));
                assertFalse(result.isComplete(), "the authorizer is pending");
                pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

                result.toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                ArgumentCaptor<AuthorizationDecisionEvent> captor =
                        ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
                verify(emitter, times(1)).emit(captor.capture());
                assertTrue(captor.getValue().decision().permitted());
            } finally {
                vertx.close()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        @Test
        @DisplayName("an Authorizer pending when the runtime closes is denied once; a late permit changes nothing")
        void authorizerPendingWhenTheRuntimeClosesIsDeniedOnce() throws Exception {
            io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
            try {
                dev.vertique.resilience.Resilience resilience = dev.vertique.resilience.Resilience.create(vertx);
                Authorizer authorizer = mock(Authorizer.class);
                io.vertx.core.Promise<AuthorizationDecision> pending = io.vertx.core.Promise.promise();
                when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(pending.future());
                SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
                when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
                ContextHolder holder = mock(ContextHolder.class);
                when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
                ServiceAuthorizationInterceptor pep = interceptorWithDeadline(
                        authorizer, registryAllowing(ACTION), emitter, holder, BOUND_MS * 10, resilience);

                Future<ServiceDispatchContext> result =
                        pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));
                assertFalse(result.isComplete(), "the authorizer is pending when the runtime closes");
                resilience
                        .close()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

                assertThrows(java.util.concurrent.ExecutionException.class, () -> result.toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
                pending.complete(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                Thread.sleep(200L);
                verify(emitter, times(1)).emit(any(AuthorizationDecisionEvent.class));
            } finally {
                vertx.close()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        @Test
        @DisplayName("fail-closed: an Authorizer that throws denies and emits INTERNAL_AUTHZ_ERROR")
        void failClosed_authorizerThrows_shortCircuits() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenThrow(new RuntimeException("boom"));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.failed(), "engine error must fail closed");
            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    captor.getValue().decision().reasonCode());
        }

        @Test
        @DisplayName(
                "fail-closed: an Authorizer that returns a null Future denies non-recoverably with INTERNAL_AUTHZ_ERROR")
        void failClosed_authorizerReturnsNullFuture_shortCircuits() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(null);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.failed(), "a null authorizer future must fail closed, not escape as a raw NPE");
            assertInstanceOf(
                    dev.vertique.services.dispatch.NonRecoverableDispatchFailure.class,
                    result.cause(),
                    "a null authorizer future must deny non-recoverably so a permissive recoverError cannot resurrect it");
            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertFalse(captor.getValue().decision().permitted(), "emitted event must record the deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    captor.getValue().decision().reasonCode(),
                    "a null authorizer future emits INTERNAL_AUTHZ_ERROR");
        }

        @Test
        @DisplayName(
                "fail-closed: an Authorizer that resolves to a null decision denies non-recoverably with INTERNAL_AUTHZ_ERROR")
        void failClosed_authorizerReturnsNullDecision_shortCircuits() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(Future.succeededFuture(null));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(
                    result.failed(), "a null authorization decision must fail closed, not NPE on decision.permitted()");
            assertInstanceOf(
                    dev.vertique.services.dispatch.NonRecoverableDispatchFailure.class,
                    result.cause(),
                    "a null decision must deny non-recoverably so a permissive recoverError cannot resurrect it");
            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertFalse(captor.getValue().decision().permitted(), "emitted event must record the deny");
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    captor.getValue().decision().reasonCode(),
                    "a null decision emits INTERNAL_AUTHZ_ERROR");
        }

        @Test
        @DisplayName("generated joinable correlation is used on the emitted event when no correlation is bound")
        void generatedCorrelation_emitted_whenUnbound() {
            Authorizer authorizer = mock(Authorizer.class);
            when(authorizer.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(
                            Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
            // No CorrelationContext bound.
            when(holder.current(dev.vertique.core.correlation.CorrelationContext.class))
                    .thenReturn(Optional.empty());

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            var correlation = captor.getValue().correlation();
            assertNotEquals(
                    UnboundCorrelationContext.SENTINEL_ID_VALUE,
                    correlation.requestId().value(),
                    "fail-closed authorization must mint a real joinable requestId");
            assertEquals(
                    "generated:service-authorization",
                    correlation.requestId().source(),
                    "generated fallback must tag its source for observability");
            assertNotEquals(
                    UnboundCorrelationContext.SENTINEL_ID_VALUE,
                    correlation.correlationId().value(),
                    "correlationId must also be joinable, not the unbound sentinel");
        }

        @Test
        @DisplayName("bound ambient InvocationOrigin is carried into the decision request and the emitted event")
        void ambientInvocationOrigin_bound_isCarriedIntoDecisionRequestAndEmittedEvent() {
            InvocationOrigin boundOrigin = InvocationOrigin.of("delayed-job");
            Authorizer authorizer = mock(Authorizer.class);
            ArgumentCaptor<AuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            when(authorizer.authorize(requestCaptor.capture()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ArgumentCaptor<AuthorizationDecisionEvent> eventCaptor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            when(emitter.emit(eventCaptor.capture())).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
            when(holder.current(InvocationOrigin.class)).thenReturn(Optional.of(boundOrigin));

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertTrue(result.succeeded());
            assertEquals(
                    boundOrigin,
                    requestCaptor.getValue().origin(),
                    "the ambient origin must be carried into the decision request");
            assertEquals(
                    boundOrigin,
                    eventCaptor.getValue().invocationOrigin(),
                    "the emitted event must carry the same ambient origin");
        }

        @Test
        @DisplayName("no bound InvocationOrigin defaults the decision request's origin to unspecified")
        void noAmbientInvocationOrigin_defaultsToUnspecified() {
            Authorizer authorizer = mock(Authorizer.class);
            ArgumentCaptor<AuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            when(authorizer.authorize(requestCaptor.capture()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(mock(SecurityContext.class)));
            // No InvocationOrigin bound — Mockito's default answer for an Optional-returning method
            // is Optional.empty(), matching a legacy dispatch path that bypasses the inbound-scope
            // install.

            ServiceAuthorizationInterceptor pep =
                    interceptor(authorizer, registryAllowing(ACTION), emitter, holder, Set.of());

            pep.beforeDispatch(dispatchContext(List.of(requiresActionOn("methodActionHolder")), List.of()));

            assertEquals(
                    InvocationOrigin.unspecified(),
                    requestCaptor.getValue().origin(),
                    "no ambient origin bound must default to InvocationOrigin.unspecified()");
        }
    }

    // --- Startup-scan tests ---

    @Nested
    @DisplayName("startup validation")
    class StartupValidation {

        @Test
        @DisplayName("unparseable @RequiresAction value fails startup")
        void startupValidation_unparseable_throws() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            Set<ServiceMethodMeta> metas =
                    Set.of(metaWith(List.of(requiresActionOn("unparseableActionHolder")), List.of()));

            assertThrows(
                    ServiceRegistrationException.class,
                    () -> interceptor(authorizer, registryAllowing(), emitter, holder, metas));
        }

        @Test
        @DisplayName("an inline-only startup violation still names the contract and method")
        void startupValidation_inlineOnly_violationKeepsContractAndMethod() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            Set<ServiceMethodMeta> metas =
                    Set.of(metaWith(List.of(requiresActionOn("unparseableActionHolder")), List.of()));

            ServiceRegistrationException thrown = assertThrows(
                    ServiceRegistrationException.class,
                    () -> interceptor(authorizer, registryAllowing(), emitter, holder, metas));

            ServiceRegistrationViolation violation = thrown.violations().get(0);
            assertNotNull(violation.contract(), "an inline-only violation names its contract");
            assertNotNull(violation.method(), "an inline-only violation names its method");
        }

        @Test
        @DisplayName("unregistered @RequiresAction action fails startup")
        void startupValidation_unregisteredAction_throws() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            // Registry contains nothing → unknown.x.y is unregistered.
            Set<ServiceMethodMeta> metas =
                    Set.of(metaWith(List.of(requiresActionOn("unregisteredActionHolder")), List.of()));

            assertThrows(
                    ServiceRegistrationException.class,
                    () -> interceptor(authorizer, registryAllowing(), emitter, holder, metas));
        }

        @Test
        @DisplayName("@RequiresAction present but authz engine absent fails startup")
        void startupValidation_engineAbsent_throws() {
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            Set<ServiceMethodMeta> metas = Set.of(metaWith(List.of(requiresActionOn("methodActionHolder")), List.of()));

            // Authorizer and ActionRegistry both absent. The emitter is always present (DispatchModule
            // includes SecurityEventsModule), so only the missing engine triggers the violation.
            assertThrows(
                    ServiceRegistrationException.class,
                    () -> new ServiceAuthorizationInterceptor(
                            Optional.empty(),
                            Optional.empty(),
                            emitter,
                            holder,
                            metas,
                            ServiceAuthorizationConfig.defaults(),
                            TestResilience.shared()));
        }

        @Test
        @DisplayName("no @RequiresAction with the authz engine absent constructs as a pass-through no-op")
        void startupValidation_noGate_engineAbsent_succeeds() {
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            // No @RequiresAction anywhere → the authz engine may be absent (DispatchModule-only graph
            // with no SecurityAuthzModule). The emitter is always present.
            Set<ServiceMethodMeta> metas = Set.of(metaWith(List.of(), List.of()));

            // Must not throw.
            new ServiceAuthorizationInterceptor(
                    Optional.empty(),
                    Optional.empty(),
                    emitter,
                    holder,
                    metas,
                    ServiceAuthorizationConfig.defaults(),
                    TestResilience.shared());
        }

        @Test
        @DisplayName("registered @RequiresAction passes startup validation")
        void startupValidation_registeredAction_succeeds() {
            Authorizer authorizer = mock(Authorizer.class);
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            ContextHolder holder = mock(ContextHolder.class);
            Set<ServiceMethodMeta> metas = Set.of(metaWith(List.of(requiresActionOn("methodActionHolder")), List.of()));

            // Should not throw: action is registered and engine present.
            interceptor(authorizer, registryAllowing(ACTION), emitter, holder, metas);
        }
    }

    // --- Typed policy gate ---

    @RequiresPolicy(UnregisteredActionPolicy.class)
    private void unregisteredActionPolicyHolder() {}

    @RequiresPolicy(MalformedActionPolicy.class)
    private void malformedActionPolicyHolder() {}

    @RequiresPolicy(ExecutorRolePolicy.class)
    @RolesAllowed("admin")
    private void policyMixedWithInlineRoleHolder() {}

    @RequiresPolicy(ExecutorRolePolicy.class)
    private void executorPolicyHolder() {}

    @RequiresPolicy(AuthenticatedOnlyPolicy.class)
    private void authenticatedPolicyHolder() {}

    @RolesAllowed("admin")
    private void inlineRoleOnlyHolder() {}

    /** Returns the {@code @RequiresPolicy} declared on the named private holder of this test class. */
    private static RequiresPolicy requiresPolicyHeldBy(String methodName) {
        try {
            return ServiceAuthorizationInterceptorTest.class
                    .getDeclaredMethod(methodName)
                    .getAnnotation(RequiresPolicy.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the annotations declared on the named private holder of this test class. */
    private static List<Annotation> annotationsHeldBy(String methodName) {
        try {
            return List.of(ServiceAuthorizationInterceptorTest.class
                    .getDeclaredMethod(methodName)
                    .getAnnotations());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("typed policy gate")
    class TypedPolicyGate {

        private static final String LOCAL_ONLY_SCENARIO_ADDRESS = "services/typed/";

        private final SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
        private final Authorizer authorizer = mock(Authorizer.class);
        private final ContextHolder holder = mock(ContextHolder.class);
        private final ActionRegistry registry =
                registryAllowing(ActionRef.parse(TypedPolicyServiceFixtures.ACTION_VALUE));

        TypedPolicyGate() {
            when(emitter.emit(any(AuthorizationDecisionEvent.class))).thenReturn(Future.succeededFuture());
        }

        // --- builders ---

        private ServiceMethodMeta metaOf(String operation) {
            return metaFor(
                    TypedPolicyContract.class,
                    new TypedPolicyService(),
                    operation,
                    LOCAL_ONLY_SCENARIO_ADDRESS + operation);
        }

        private ServiceDispatchContext contextOf(ServiceMethodMeta meta) {
            return new ServiceDispatchContext(
                    meta.address(),
                    meta.stableTargetId(),
                    meta.namespace(),
                    meta.name(),
                    meta.operation(),
                    DispatchEnvelope.of("payload"),
                    false,
                    meta.methodAnnotations(),
                    meta.classAnnotations(),
                    Map.of());
        }

        private ServiceAuthorizationInterceptor gateWithEngine(ServiceMethodMeta... metas) {
            return interceptor(authorizer, registry, emitter, holder, Set.of(metas));
        }

        private ServiceAuthorizationInterceptor gateWithoutEngine(ServiceMethodMeta... metas) {
            return interceptor(null, null, emitter, holder, Set.of(metas));
        }

        private void bind(Caller caller) {
            when(holder.current(SecurityContext.class)).thenReturn(Optional.ofNullable(caller));
        }

        private AuthorizationDecisionEvent onlyEvent() {
            ArgumentCaptor<AuthorizationDecisionEvent> captor =
                    ArgumentCaptor.forClass(AuthorizationDecisionEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            return captor.getValue();
        }

        private void assertDeniedNonRecoverably(Future<ServiceDispatchContext> result) {
            assertTrue(result.failed(), "the gate must refuse the dispatch");
            assertInstanceOf(
                    NonRecoverableDispatchFailure.class,
                    result.cause(),
                    "a typed denial must be non-recoverable so recoverError cannot resurrect it");
        }

        private void assertDeniedLocally(String operation, Caller caller, String reason, PrincipalType actor) {
            ServiceMethodMeta meta = metaOf(operation);
            bind(caller);
            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            AuthorizationDecisionEvent event = onlyEvent();
            assertFalse(event.decision().permitted(), "the event must record the denial");
            assertEquals(reason, event.decision().reasonCode(), "denial reason");
            assertEquals(
                    actor, event.request().securityContext().identity().actor().type(), "event actor");
            assertEquals(operation, event.request().action(), "a local-only event carries the operation label");
            verifyNoInteractions(authorizer);
            verify(registry, never()).contains(any());
            verify(registry, never()).find(any());
        }

        // --- startup ---

        @Test
        @DisplayName(
                "role, scope, authenticated-only, deny and public typed policies boot with no authorization engine")
        void startup_localPolicies_bootWithoutEngine() {
            for (String operation :
                    List.of("rolesOnly", "allScopes", "anyScope", "authenticatedOnly", "denyAll", "publicOp")) {
                assertDoesNotThrow(
                        () -> gateWithoutEngine(metaOf(operation)),
                        operation + " must not need an Authorizer or ActionRegistry");
            }
        }

        @Test
        @DisplayName("typed policies that declare an action require the authorization engine at startup")
        void startup_actionPolicy_needsEngine() {
            for (String operation : List.of("actionOnly", "operatorAndAction")) {
                assertThrows(
                        ServiceRegistrationException.class,
                        () -> gateWithoutEngine(metaOf(operation)),
                        operation + " must be rejected when the engine is absent");
            }
        }

        @Test
        @DisplayName(
                "typed policies that declare an action boot when the engine is installed and the action registered")
        void startup_actionPolicy_bootsWithEngine() {
            assertDoesNotThrow(() -> gateWithEngine(metaOf("actionOnly"), metaOf("operatorAndAction")));
        }

        @Test
        @DisplayName("a typed policy naming an unregistered action fails startup")
        void startup_unregisteredAction_failsStartup() {
            ServiceMethodMeta meta = metaWith(annotationsHeldBy("unregisteredActionPolicyHolder"), List.of());

            assertThrows(ServiceRegistrationException.class, () -> gateWithEngine(meta));
        }

        @Test
        @DisplayName("a typed policy naming a malformed action fails startup")
        void startup_malformedAction_failsStartup() {
            ServiceMethodMeta meta = metaWith(annotationsHeldBy("malformedActionPolicyHolder"), List.of());

            assertThrows(ServiceRegistrationException.class, () -> gateWithEngine(meta));
        }

        @Test
        @DisplayName("a policy reference mixed with an inline security annotation fails startup")
        void startup_policyMixedWithInline_failsStartup() {
            ServiceMethodMeta meta = metaWith(annotationsHeldBy("policyMixedWithInlineRoleHolder"), List.of());

            assertThrows(ServiceRegistrationException.class, () -> gateWithoutEngine(meta));
        }

        @Test
        @DisplayName("distinct policy references in one complete method set fail startup")
        void startup_conflictingMethodReferences_failsStartup() {
            List<Annotation> conflicting = List.of(
                    requiresPolicyHeldBy("executorPolicyHolder"), requiresPolicyHeldBy("authenticatedPolicyHolder"));
            ServiceMethodMeta meta = metaWith(conflicting, List.of());

            assertThrows(ServiceRegistrationException.class, () -> gateWithoutEngine(meta));
        }

        // --- runtime: lookup by address ---

        @Test
        @DisplayName("the gate resolved at startup is looked up by the dispatch address")
        void dispatch_gateIsLookedUpByAddress() {
            ServiceMethodMeta needsRole = metaOf("rolesOnly");
            ServiceMethodMeta needsLogin = metaOf("authenticatedOnly");
            ServiceAuthorizationInterceptor pep = gateWithoutEngine(needsRole, needsLogin);
            bind(Caller.user("plain"));

            Future<ServiceDispatchContext> roleResult = pep.beforeDispatch(contextOf(needsRole));
            Future<ServiceDispatchContext> loginResult = pep.beforeDispatch(contextOf(needsLogin));

            assertDeniedNonRecoverably(roleResult);
            assertTrue(loginResult.succeeded(), "an authenticated caller passes the authenticated-only operation");
        }

        @Test
        @DisplayName("fail-closed: a dispatch carrying a policy reference with no registered gate is refused")
        void dispatch_policyWithoutRegisteredGate_failsClosed() {
            ServiceMethodMeta unregistered = metaOf("publicOp");
            ServiceAuthorizationInterceptor pep = gateWithoutEngine(metaOf("rolesOnly"));
            bind(Caller.user("anyone").withRoles(EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = pep.beforeDispatch(contextOf(unregistered));

            assertDeniedNonRecoverably(result);
            verifyNoInteractions(authorizer);
        }

        // --- runtime: local predicates ---

        @Test
        @DisplayName("role policy: a caller lacking the role is denied with the operation label and no authorizer call")
        void localRole_callerLackingRole_isDeniedWithOperationLabel() {
            assertDeniedLocally(
                    "rolesOnly", Caller.user("viewer").withRoles("viewer"), "ROLE_MISSING", PrincipalType.USER);
        }

        @Test
        @DisplayName("role policy: a caller holding the role is permitted with one permit event")
        void localRole_callerHoldingRole_isPermitted() {
            ServiceMethodMeta meta = metaOf("rolesOnly");
            bind(Caller.user("executor").withRoles(EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertTrue(result.succeeded());
            assertTrue(onlyEvent().decision().permitted());
            verifyNoInteractions(authorizer);
        }

        @Test
        @DisplayName("scope policies: all-scopes default, any-scope opt-out and the scope plus permission union")
        void localScopes_allAnyAndUnion() {
            ServiceMethodMeta all = metaOf("allScopes");
            ServiceMethodMeta any = metaOf("anyScope");
            ServiceAuthorizationInterceptor pep = gateWithoutEngine(all, any);

            bind(Caller.user("one").withScopes(READ_SCOPE));
            Future<ServiceDispatchContext> oneOfTwo = pep.beforeDispatch(contextOf(all));
            Future<ServiceDispatchContext> oneOfAny = pep.beforeDispatch(contextOf(any));
            bind(Caller.user("union").withScopes(READ_SCOPE).withPermissions(WRITE_SCOPE));
            Future<ServiceDispatchContext> union = pep.beforeDispatch(contextOf(all));

            assertAll(
                    () -> assertDeniedNonRecoverably(oneOfTwo),
                    () -> assertTrue(oneOfAny.succeeded(), "any-scope accepts one scope"),
                    () -> assertTrue(union.succeeded(), "a PERMISSION claim counts as a scope"));
        }

        @Test
        @DisplayName("authentication required: no caller is denied with an anonymous actor")
        void local_missingCaller_requiresAuthentication() {
            assertDeniedLocally("rolesOnly", null, AuthzReasonCodes.AUTHENTICATION_REQUIRED, PrincipalType.ANONYMOUS);
        }

        @Test
        @DisplayName("authentication required: a bound anonymous caller is denied even holding the claims")
        void local_anonymousCaller_requiresAuthentication() {
            assertDeniedLocally(
                    "authenticatedOnly",
                    Caller.anonymous().withRoles(EXECUTOR_ROLE),
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                    PrincipalType.ANONYMOUS);
        }

        @Test
        @DisplayName("marked caller: a reconstruction marker is rejected before claims")
        void local_reconstructionMarker_isUnsupported() {
            assertDeniedLocally(
                    "rolesOnly",
                    Caller.user("rebuilt").withRoles(EXECUTOR_ROLE).reconstructed(),
                    UNSUPPORTED_POLICY_CALLER,
                    PrincipalType.USER);
        }

        @Test
        @DisplayName("marked caller: an identity subject is rejected before claims")
        void local_subjectMarker_isUnsupported() {
            assertDeniedLocally(
                    "rolesOnly",
                    Caller.user("on-behalf").withRoles(EXECUTOR_ROLE).withSubject(),
                    UNSUPPORTED_POLICY_CALLER,
                    PrincipalType.USER);
        }

        @Test
        @DisplayName("marked caller: an identity delegation is rejected before claims")
        void local_delegationMarker_isUnsupported() {
            assertDeniedLocally(
                    "rolesOnly",
                    Caller.user("delegated").withRoles(EXECUTOR_ROLE).withDelegation(),
                    UNSUPPORTED_POLICY_CALLER,
                    PrincipalType.USER);
        }

        @Test
        @DisplayName("public policy: no authorization work, no event, even with no caller")
        void local_publicPolicy_doesNothing() {
            ServiceMethodMeta meta = metaOf("publicOp");
            bind(null);

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertTrue(result.succeeded());
            verify(emitter, never()).emit(any(AuthorizationDecisionEvent.class));
            verifyNoInteractions(authorizer);
        }

        @Test
        @DisplayName("deny policy: refused without any caller, with an anonymous event actor and DENY_ALL")
        void local_denyPolicy_refusesWithoutCaller() {
            assertDeniedLocally("denyAll", null, AuthzReasonCodes.DENY_ALL, PrincipalType.ANONYMOUS);
        }

        @Test
        @DisplayName("the real invocation origin is carried onto the local-only decision event")
        void local_denial_carriesInvocationOrigin() {
            InvocationOrigin origin = InvocationOrigin.of("delayed-job");
            ServiceMethodMeta meta = metaOf("rolesOnly");
            bind(Caller.user("viewer").withRoles("viewer"));
            when(holder.current(InvocationOrigin.class)).thenReturn(Optional.of(origin));

            gateWithoutEngine(meta).beforeDispatch(contextOf(meta));

            assertEquals(origin, onlyEvent().invocationOrigin());
        }

        // --- runtime: action and combined policies ---

        @Test
        @DisplayName("combined policy: a local denial precedes the action and never calls the authorizer")
        void combined_localDenial_precedesAction() {
            ServiceMethodMeta meta = metaOf("operatorAndAction");
            bind(Caller.user("executor-only").withRoles(EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            assertEquals("ROLE_MISSING", onlyEvent().decision().reasonCode());
            verifyNoInteractions(authorizer);
        }

        @Test
        @DisplayName("combined policy: a marked caller is rejected even though the action would permit")
        void combined_markedCaller_rejectedBeforeAction() {
            ServiceMethodMeta meta = metaOf("operatorAndAction");
            when(authorizer.authorize(any(AuthorizationRequest.class)))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            bind(Caller.user("rebuilt").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE).reconstructed());

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            assertEquals(UNSUPPORTED_POLICY_CALLER, onlyEvent().decision().reasonCode());
            verify(authorizer, never()).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName(
                "combined policy: when local checks pass the authorizer is called once with only the declared action")
        void combined_localChecksPass_authorizerCalledOnceWithDeclaredAction() {
            ServiceMethodMeta meta = metaOf("operatorAndAction");
            ArgumentCaptor<AuthorizationRequest> requestCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
            when(authorizer.authorize(requestCaptor.capture()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)));
            bind(Caller.user("both").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertTrue(result.succeeded());
            verify(authorizer, times(1)).authorize(any(AuthorizationRequest.class));
            assertEquals(
                    TypedPolicyServiceFixtures.ACTION_VALUE,
                    requestCaptor.getValue().action());
            assertTrue(requestCaptor.getValue().context().isEmpty(), "no claim context leaks into the action request");
            assertTrue(onlyEvent().decision().permitted(), "one composed permit event");
        }

        @Test
        @DisplayName("action-only policy: a throwing evaluator denies non-recoverably with INTERNAL_AUTHZ_ERROR")
        void action_throwingEvaluator_failsClosed() {
            ServiceMethodMeta meta = metaOf("actionOnly");
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenThrow(new RuntimeException("boom"));
            bind(Caller.user("granted").withRoles(EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    onlyEvent().decision().reasonCode());
        }

        @Test
        @DisplayName("action-only policy: no caller is denied with AUTHENTICATION_REQUIRED and no authorizer call")
        void action_noCaller_requiresAuthentication() {
            ServiceMethodMeta meta = metaOf("actionOnly");
            bind(null);

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            AuthorizationDecisionEvent event = onlyEvent();
            assertEquals(
                    AuthzReasonCodes.AUTHENTICATION_REQUIRED, event.decision().reasonCode());
            assertEquals(
                    PrincipalType.ANONYMOUS,
                    event.request().securityContext().identity().actor().type());
            verifyNoInteractions(authorizer);
        }

        // --- characterization ---

        @Test
        @DisplayName("characterization: an inline-only security annotation is not enforced by the service gate")
        void characterization_inlineOnlyDeclaration_isPassThrough() {
            ServiceMethodMeta meta = metaWith(annotationsHeldBy("inlineRoleOnlyHolder"), List.of());
            ServiceAuthorizationInterceptor pep = gateWithoutEngine(meta);
            bind(null);

            Future<ServiceDispatchContext> result =
                    pep.beforeDispatch(dispatchContext(annotationsHeldBy("inlineRoleOnlyHolder"), List.of()));

            assertTrue(result.succeeded(), "inline-only declarations keep their existing pass-through behavior");
            verify(emitter, never()).emit(any(AuthorizationDecisionEvent.class));
            verifyNoInteractions(authorizer);
        }

        // --- fail-closed hardening ---

        /** A bound context whose identity cannot be read: it throws, or returns {@code null}. */
        private SecurityContext unreadableIdentity(boolean throwing) {
            return new SecurityContext() {
                @Override
                public SecurityIdentity identity() {
                    if (throwing) {
                        throw new IllegalStateException("identity unavailable");
                    }
                    return null;
                }

                @Override
                public AuthenticationState authentication() {
                    throw new IllegalStateException("authentication must not be read");
                }

                @Override
                public AuthorizationClaims authorization() {
                    return AuthorizationClaims.empty();
                }

                @Override
                public Optional<RequestOrigin> origin() {
                    return Optional.empty();
                }
            };
        }

        private void assertUnreadableIdentityFailsClosed(boolean throwing) {
            ServiceMethodMeta meta = metaOf("rolesOnly");
            when(holder.current(SecurityContext.class)).thenReturn(Optional.of(unreadableIdentity(throwing)));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    onlyEvent().decision().reasonCode());
            verifyNoInteractions(authorizer);
        }

        @Test
        @DisplayName("fail-closed: a bound context whose identity throws is denied non-recoverably with one event")
        void failClosed_identityThrows_deniesWithOneEvent() {
            assertUnreadableIdentityFailsClosed(true);
        }

        @Test
        @DisplayName("fail-closed: a bound context whose identity is null is denied non-recoverably with one event")
        void failClosed_identityNull_deniesWithOneEvent() {
            assertUnreadableIdentityFailsClosed(false);
        }

        @Test
        @DisplayName("fail-closed: a combined policy whose action decision breaks composition emits one event")
        void failClosed_combinedCompositionThrows_deniesWithOneEvent() {
            ServiceMethodMeta meta = metaOf("operatorAndAction");
            AuthorizationDecision unusable = mock(AuthorizationDecision.class);
            when(unusable.permitted()).thenReturn(true);
            when(unusable.reasonCode()).thenReturn(null);
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(Future.succeededFuture(unusable));
            bind(Caller.user("both").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            assertEquals(
                    AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                    onlyEvent().decision().reasonCode());
            verify(authorizer, times(1)).authorize(any(AuthorizationRequest.class));
        }

        @Test
        @DisplayName("fail-closed: an action decision that cannot be enforced is a non-recoverable denial")
        void failClosed_actionDecisionThrowsWhileEnforced_deniesNonRecoverably() {
            ServiceMethodMeta meta = metaOf("actionOnly");
            AuthorizationDecision unusable = mock(AuthorizationDecision.class);
            when(unusable.permitted()).thenThrow(new IllegalStateException("decision unreadable"));
            when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(Future.succeededFuture(unusable));
            bind(Caller.user("granted").withRoles(EXECUTOR_ROLE));

            Future<ServiceDispatchContext> result = gateWithEngine(meta).beforeDispatch(contextOf(meta));

            assertDeniedNonRecoverably(result);
            verify(emitter, times(1)).emit(any(AuthorizationDecisionEvent.class));
        }

        // --- startup: addresses, diagnostics and unsupported requirements ---

        @Test
        @DisplayName("two operations sharing an address with different typed gates fail startup naming the address")
        void startup_sharedAddressWithDifferentGates_failsStartup() {
            String shared = LOCAL_ONLY_SCENARIO_ADDRESS + "shared";
            ServiceMethodMeta needsRole =
                    metaFor(TypedPolicyContract.class, new TypedPolicyService(), "rolesOnly", shared);
            ServiceMethodMeta needsLogin =
                    metaFor(TypedPolicyContract.class, new TypedPolicyService(), "authenticatedOnly", shared);

            ServiceRegistrationException failure =
                    assertThrows(ServiceRegistrationException.class, () -> gateWithoutEngine(needsRole, needsLogin));

            assertTrue(
                    failure.violations().stream().anyMatch(violation -> violation
                            .message()
                            .contains("address '" + shared + "' resolves to conflicting typed access policies")),
                    "the conflict must name the shared address: " + failure.violations());
        }

        @Test
        @DisplayName("a startup violation names the dispatch identity rather than the declaring class")
        void startup_violation_namesTheDispatchIdentity() {
            ServiceMethodMeta meta = metaWith(annotationsHeldBy("unregisteredActionPolicyHolder"), List.of());

            ServiceRegistrationException failure =
                    assertThrows(ServiceRegistrationException.class, () -> gateWithEngine(meta));

            assertAll(
                    () -> assertEquals(1, failure.violations().size()),
                    () -> assertTrue(
                            failure.violations().get(0).message().contains("address 'services/svc/exec'"),
                            "the violation must carry the dispatch address: " + failure.violations()),
                    () -> assertTrue(
                            failure.violations().get(0).message().contains("service 'svc'"),
                            "the violation must carry the service name: " + failure.violations()));
        }

        @Test
        @DisplayName("an unrecognised policy requirement is rejected instead of being skipped")
        void gate_unsupportedRequirement_isRejected() {
            RequiresPolicy notARequirement = requiresPolicyHeldBy("executorPolicyHolder");

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ServiceAuthorizationInterceptor.TypedGate.of(List.of(notARequirement)));

            assertTrue(
                    failure.getMessage().contains("unsupported policy requirement " + RequiresPolicy.class.getName()),
                    failure.getMessage());
        }
    }
}
