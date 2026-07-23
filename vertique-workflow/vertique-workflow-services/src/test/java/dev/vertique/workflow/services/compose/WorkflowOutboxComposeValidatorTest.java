// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.Future;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
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
    @DisplayName("empty handler set — throws IllegalStateException with documented message")
    void constructor_emptyHandlerSet_throws() {
        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(Set.of(), emptyRegistry(), throwingResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPECTED_HANDLER_MSG)
                .hasMessageContaining("TransactionalMessagingServiceModule");
    }

    @Test
    @DisplayName("only DELAYED_JOB handler — throws IllegalStateException")
    void constructor_onlyDelayedJobHandler_throws() {
        Set<OutboxDestinationHandler> handlers = Set.of(new StubDelayedJobHandler());

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(handlers, emptyRegistry(), throwingResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(EXPECTED_HANDLER_MSG);
    }

    @Test
    @DisplayName("SERVICE handler present, no registered plans — constructor succeeds")
    void constructor_serviceHandlerPresentNoPlans_succeeds() {
        assertThatCode(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), emptyRegistry(), throwingResolver()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both SERVICE and DELAYED_JOB handlers — constructor succeeds")
    void constructor_serviceAndDelayedJobHandlers_succeeds() {
        Set<OutboxDestinationHandler> handlers = Set.of(new StubServiceHandler(), new StubDelayedJobHandler());

        assertThatCode(() -> new WorkflowOutboxComposeValidator(handlers, emptyRegistry(), throwingResolver()))
                .doesNotThrowAnyException();
    }

    // --- Registered-plans target-shape check ---

    @Test
    @DisplayName("ServiceDispatchNode with unknown target id — throws IllegalStateException")
    void constructor_unknownTargetId_throws() {
        WorkflowRegistry registry = registryWith(planWithDispatchTarget("unknown.target"));
        ServiceTargetResolver resolver = throwingResolver();

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compose-test-def")
                .hasMessageContaining("dispatch-step")
                .hasMessageContaining("unknown.target")
                .hasMessageContaining("unknown service target");
    }

    @Test
    @DisplayName("@OneWay target — throws IllegalStateException")
    void constructor_oneWayTarget_throws() {
        WorkflowRegistry registry = registryWith(planWithDispatchTarget("oneway.target"));
        ServiceTargetResolver resolver =
                fixedResolver("oneway.target", buildTarget("oneway.target", true, Void.class, 1));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oneway.target")
                .hasMessageContaining("@OneWay");
    }

    @Test
    @DisplayName("non-Future<Void> return type — throws IllegalStateException")
    void constructor_nonVoidReturnType_throws() {
        WorkflowRegistry registry = registryWith(planWithDispatchTarget("string.target"));
        ServiceTargetResolver resolver =
                fixedResolver("string.target", buildTarget("string.target", false, String.class, 1));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("string.target")
                .hasMessageContaining("must return Future<Void>")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("payload param count != 1 — throws IllegalStateException")
    void constructor_wrongPayloadParamCount_throws() {
        WorkflowRegistry registry = registryWith(planWithDispatchTarget("twopay.target"));
        ServiceTargetResolver resolver =
                fixedResolver("twopay.target", buildTarget("twopay.target", false, Void.class, 2));

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twopay.target")
                .hasMessageContaining("exactly one payload parameter")
                .hasMessageContaining("got 2");
    }

    @Test
    @DisplayName("CompensationNode target validated alongside ServiceDispatchNode targets")
    void constructor_compensationTargetValidated() {
        WorkflowRegistry registry = registryWith(planWithCompensationTarget("comp.unknown"));
        Map<String, ResolvedServiceTarget> targets = Map.of("svc.fwd", buildTarget("svc.fwd", false, Void.class, 1));
        ServiceTargetResolver resolver = mapResolver(targets);

        assertThatThrownBy(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comp-step")
                .hasMessageContaining("comp.unknown");
    }

    @Test
    @DisplayName("happy path — all targets valid → constructor succeeds")
    void constructor_allTargetsValid_succeeds() {
        WorkflowRegistry registry = registryWith(planWithDispatchTarget("ok.target"));
        ServiceTargetResolver resolver = fixedResolver("ok.target", buildTarget("ok.target", false, Void.class, 1));

        assertThatCode(() -> new WorkflowOutboxComposeValidator(serviceHandlers(), registry, resolver))
                .doesNotThrowAnyException();
    }

    // --- Helpers ---

    private static Set<OutboxDestinationHandler> serviceHandlers() {
        return Set.of(new StubServiceHandler());
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

    private static WorkflowRegistry emptyRegistry() {
        return new StaticRegistry(List.of());
    }

    private static WorkflowRegistry registryWith(WorkflowPlan plan) {
        RuntimeWorkflow rw = new RuntimeWorkflow(
                plan, String.class, String.class, Function.identity(), Map.of(), emptyCallbacks(), Map.of(), Map.of());
        return new StaticRegistry(List.of(rw));
    }

    private static WorkflowCallbackRegistry emptyCallbacks() {
        return new WorkflowCallbackRegistry() {
            @Override
            public <S> Function<S, Object> payloadFactory(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> BiFunction<S, Object, S> stateUpdater(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> Function<S, String> decisionResolver(CallbackId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <S> Function<S, String> failMessageFactory(CallbackId id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Minimal {@link WorkflowRegistry} returning a fixed list from {@link #allRegistered()}. */
    private static final class StaticRegistry implements WorkflowRegistry {
        private final Collection<RuntimeWorkflow> all;

        StaticRegistry(Collection<RuntimeWorkflow> all) {
            this.all = all;
        }

        @Override
        public void register(WorkflowDefinition<?, ?> def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolveCurrent(String definitionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeWorkflow resolvePinned(String definitionId, long version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkflowContractMetadata contractMetadata(Class<?> contractInterface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<RuntimeWorkflow> allRegistered() {
            return all;
        }
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
