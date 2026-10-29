// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowOutboxComposeValidator}.
 *
 * <p>Two validation passes are exercised: the {@code SERVICE} handler presence check, and the
 * registered-plans target-shape check (unknown id, {@code @OneWay}, non-{@code Future<Void>},
 * wrong payload param count).
 */
class WorkflowOutboxComposeValidatorTest {

    private static final String EXPECTED_HANDLER_MSG =
            "vertique-workflow-services requires a SERVICE OutboxDestinationHandler";

    // --- SERVICE handler presence check ---

    @Test
    @DisplayName("plan that dispatches to a service, empty handler set — throws IllegalStateException")
    void validate_emptyHandlerSet_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("ok.target"));
        WorkflowOutboxComposeValidator validator = new WorkflowOutboxComposeValidator(Set.of(), okResolver());

        assertThatThrownBy(() -> validator.validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPECTED_HANDLER_MSG)
                .hasMessageContaining("TransactionalMessagingServiceModule");
    }

    @Test
    @DisplayName("plan that dispatches to a service, only DELAYED_JOB handler — throws IllegalStateException")
    void validate_onlyDelayedJobHandler_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("ok.target"));
        WorkflowOutboxComposeValidator validator =
                new WorkflowOutboxComposeValidator(Set.of(new StubDelayedJobHandler()), okResolver());

        assertThatThrownBy(() -> validator.validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPECTED_HANDLER_MSG);
    }

    @Test
    @DisplayName("plan that dispatches to no service, no handler — passes")
    void validate_noServiceDispatchNoHandler_passes() {
        RegisteredWorkflowDefinition candidate = candidate(planWithoutService());

        assertThatCode(() -> new WorkflowOutboxComposeValidator(Set.of(), throwingResolver()).validate(candidate))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both SERVICE and DELAYED_JOB handlers — plan that dispatches to a service passes")
    void validate_serviceAndDelayedJobHandlers_passes() {
        Set<OutboxDestinationHandler> handlers = Set.of(new StubServiceHandler(), new StubDelayedJobHandler());
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("ok.target"));

        assertThatCode(() -> new WorkflowOutboxComposeValidator(handlers, okResolver()).validate(candidate))
                .doesNotThrowAnyException();
    }

    // --- Registered-plans target-shape check ---

    @Test
    @DisplayName("ServiceDispatchNode with unknown target id — throws IllegalStateException")
    void validate_unknownTargetId_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("unknown.target"));
        ServiceTargetResolver resolver = throwingResolver();

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compose-test-def")
                .hasMessageContaining("dispatch-step")
                .hasMessageContaining("unknown.target")
                .hasMessageContaining("unknown service target");
    }

    @Test
    @DisplayName("@OneWay target — throws IllegalStateException")
    void validate_oneWayTarget_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("oneway.target"));
        ServiceTargetResolver resolver =
                fixedResolver("oneway.target", buildTarget("oneway.target", true, Void.class, 1));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oneway.target")
                .hasMessageContaining("@OneWay");
    }

    @Test
    @DisplayName("non-Future<Void> return type — throws IllegalStateException")
    void validate_nonVoidReturnType_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("string.target"));
        ServiceTargetResolver resolver =
                fixedResolver("string.target", buildTarget("string.target", false, String.class, 1));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("string.target")
                .hasMessageContaining("must return Future<Void>")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("payload param count != 1 — throws IllegalStateException")
    void validate_wrongPayloadParamCount_throws() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("twopay.target"));
        ServiceTargetResolver resolver =
                fixedResolver("twopay.target", buildTarget("twopay.target", false, Void.class, 2));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twopay.target")
                .hasMessageContaining("exactly one payload parameter")
                .hasMessageContaining("got 2");
    }

    @Test
    @DisplayName("CompensationNode target validated alongside ServiceDispatchNode targets")
    void validate_compensationTargetValidated() {
        RegisteredWorkflowDefinition candidate = candidate(planWithCompensationTarget("comp.unknown"));
        Map<String, ResolvedServiceTarget> targets = Map.of("svc.fwd", buildTarget("svc.fwd", false, Void.class, 1));
        ServiceTargetResolver resolver = mapResolver(targets);

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comp-step")
                .hasMessageContaining("comp.unknown");
    }

    @Test
    @DisplayName("happy path — all targets valid → constructor succeeds")
    void validate_allTargetsValid_succeeds() {
        RegisteredWorkflowDefinition candidate = candidate(planWithDispatchTarget("ok.target"));
        ServiceTargetResolver resolver = fixedResolver("ok.target", buildTarget("ok.target", false, Void.class, 1));

        assertThatCode(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), resolver).validate(candidate))
                .doesNotThrowAnyException();
    }

    // --- Per-plan validation ---

    @Test
    @DisplayName("each plan is validated alone: an unknown target and a missing SERVICE handler refuse that plan only")
    void validatesOnePlanAtATime() {
        // Given a validator built without a registry, a resolver that knows ok.target only, and plans that dispatch to
        // an unknown target, to a known target, and to no service at all
        ServiceTargetResolver resolver = fixedResolver("ok.target", buildTarget("ok.target", false, Void.class, 1));
        WorkflowOutboxComposeValidator withHandler = new WorkflowOutboxComposeValidator(serviceHandlers(), resolver);
        WorkflowOutboxComposeValidator withoutHandler =
                new WorkflowOutboxComposeValidator(Set.of(new StubDelayedJobHandler()), resolver);
        RegisteredWorkflowDefinition unknown = candidate(planWithDispatchTarget("unknown.target"));
        RegisteredWorkflowDefinition known = candidate(planWithDispatchTarget("ok.target"));
        RegisteredWorkflowDefinition noService = candidate(planWithoutService());

        // When each plan is validated alone
        // Then the plan with the unknown target is refused with the offender text
        assertThatThrownBy(() -> withHandler.validate(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow definition 'compose-test-def' v1 step 'dispatch-step'"
                        + " references unknown service target id 'unknown.target'");

        // And the plan with the known target passes
        assertThatCode(() -> withHandler.validate(known)).doesNotThrowAnyException();

        // And a plan that dispatches to a service is refused when no SERVICE handler is bound
        assertThatThrownBy(() -> withoutHandler.validate(known))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPECTED_HANDLER_MSG)
                .hasMessageContaining("TransactionalMessagingServiceModule");

        // And a plan that dispatches to no service passes with or without a SERVICE handler
        assertThatCode(() -> withHandler.validate(noService)).doesNotThrowAnyException();
        assertThatCode(() -> withoutHandler.validate(noService)).doesNotThrowAnyException();
    }

    // --- Helpers ---

    private static Set<OutboxDestinationHandler> serviceHandlers() {
        return Set.of(new StubServiceHandler());
    }

    private static ServiceTargetResolver okResolver() {
        return fixedResolver("ok.target", buildTarget("ok.target", false, Void.class, 1));
    }

    private static ServiceTargetResolver throwingResolver() {
        return mapResolver(Map.of());
    }

    private static ServiceTargetResolver fixedResolver(String targetId, ResolvedServiceTarget target) {
        return mapResolver(Map.of(targetId, target));
    }

    private static ServiceTargetResolver mapResolver(Map<String, ResolvedServiceTarget> targets) {
        return new ServiceTargetResolver() {
            @Override
            public ResolvedServiceTarget resolve(String targetId) {
                ResolvedServiceTarget t = targets.get(targetId);
                if (t == null) {
                    throw new IllegalArgumentException("not registered: " + targetId);
                }
                return t;
            }

            @Override
            public ResolvedServiceTarget resolve(Class<?> contract, java.lang.reflect.Method method) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedServiceTarget resolve(Class<?> contract, String operationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> supportedTargetIds() {
                return targets.keySet();
            }
        };
    }

    private static ResolvedServiceTarget buildTarget(
            String targetId, boolean oneWay, Class<?> returnType, int payloadParamCount) {
        ServiceMethodDescriptor descriptor;
        try {
            descriptor = ServiceMethodDescriptor.of(StubContract.class.getMethod("op", Object.class));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        ParamMeta payload = new ParamMeta("payload", ParamSource.PAYLOAD, Object.class, null);
        List<ParamMeta> params;
        if (payloadParamCount == 1) {
            params = List.of(payload);
        } else if (payloadParamCount == 2) {
            params = List.of(payload, payload);
        } else {
            params = List.of(new ParamMeta("ctx", ParamSource.DISPATCH_CONTEXT, Object.class, "ctx"));
        }
        ServiceMethodMeta meta = ServiceMethodMeta.ofDirect(
                new Object(),
                descriptor,
                "test/address",
                targetId,
                "test",
                "stub",
                "op",
                Object.class,
                returnType,
                params,
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                oneWay);
        return new ResolvedServiceTarget(targetId, StubContract.class, "test", "stub", "op", meta, "test/address");
    }

    /** Marker interface used only to construct {@link ServiceMethodDescriptor} test fixtures. */
    private interface StubContract {
        Object op(Object payload);
    }

    private static WorkflowPlan planWithDispatchTarget(String targetId) {
        return new WorkflowPlan(
                "compose-test-def",
                1L,
                "00000000",
                "java.lang.String",
                "dispatch-step",
                List.of(
                        new ServiceDispatchNode("dispatch-step", targetId, new CallbackId("p"), null, "done"),
                        new CompleteNode("done")),
                null);
    }

    private static WorkflowPlan planWithoutService() {
        return new WorkflowPlan(
                "no-service-def", 1L, "00000000", "java.lang.String", "done", List.of(new CompleteNode("done")), null);
    }

    private static RegisteredWorkflowDefinition candidate(WorkflowPlan plan) {
        return new RegisteredWorkflowDefinition(plan, String.class);
    }

    private static WorkflowPlan planWithCompensationTarget(String compTargetId) {
        return new WorkflowPlan(
                "compose-test-def",
                1L,
                "00000000",
                "java.lang.String",
                "fwd",
                List.of(
                        new ServiceDispatchNode("fwd", "svc.fwd", new CallbackId("p"), "comp-step", "done"),
                        new CompensationNode("comp-step", "fwd", compTargetId, new CallbackId("cp")),
                        new CompleteNode("done")),
                null);
    }

    // --- Stub destination handlers ---

    private static final class StubServiceHandler implements OutboxDestinationHandler {
        @Override
        public DestinationType destinationType() {
            return DestinationType.SERVICE;
        }

        @Override
        public ClaimScope claimScope() {
            return ClaimScope.all();
        }

        @Override
        public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
            return Future.succeededFuture(OutboxPublishResult.success());
        }
    }

    private static final class StubDelayedJobHandler implements OutboxDestinationHandler {
        @Override
        public DestinationType destinationType() {
            return DestinationType.DELAYED_JOB;
        }

        @Override
        public ClaimScope claimScope() {
            return ClaimScope.all();
        }

        @Override
        public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
            return Future.succeededFuture(OutboxPublishResult.success());
        }
    }
}
