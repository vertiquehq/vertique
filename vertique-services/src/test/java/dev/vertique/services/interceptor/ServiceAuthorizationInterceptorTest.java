// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.UnboundCorrelationContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceRegistrationException;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
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
                Optional.ofNullable(authorizer), Optional.ofNullable(registry), emitter, contextHolder, metas);
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
        @DisplayName("sentinel correlation is used on the emitted event when no correlation is bound")
        void sentinelCorrelation_emitted_whenUnbound() {
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
            assertEquals(
                    UnboundCorrelationContext.SENTINEL_ID_VALUE,
                    captor.getValue().correlation().correlationId().value(),
                    "unbound correlation must use the sentinel id");
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
                            Optional.empty(), Optional.empty(), emitter, holder, metas));
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
            new ServiceAuthorizationInterceptor(Optional.empty(), Optional.empty(), emitter, holder, metas);
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
}
