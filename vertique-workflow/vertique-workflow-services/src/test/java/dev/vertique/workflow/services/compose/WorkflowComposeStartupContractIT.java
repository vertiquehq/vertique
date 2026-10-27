// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RegisteredDefinitionClass;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import io.vertx.core.Future;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test that verifies the startup contract of {@link WorkflowOutboxComposeValidator}.
 *
 * <p>In production the validator is contributed into the {@code Set<WorkflowDefinitionValidator>}
 * multibinding, so the workflow registry runs it once for every plan it builds. A code plan that
 * dispatches to a service aborts startup with an {@link IllegalStateException} when no
 * {@code SERVICE} handler is bound; a graph whose plans dispatch to no service starts without one.
 *
 * <p>The components in this test use a real {@link DefaultWorkflowRegistry} that resolves the
 * validators per build, as the production module does:
 * <ol>
 *   <li>Constructing the component does NOT throw — the registry is a lazy {@code @Singleton}.</li>
 *   <li>Requesting the registry of a component composed WITHOUT a {@code SERVICE} handler, whose
 *       code definition dispatches to a service, throws {@link IllegalStateException} with the
 *       documented message.</li>
 *   <li>Requesting the validator of a component composed WITH a {@code SERVICE} handler returns it
 *       without error.</li>
 * </ol>
 *
 * <p>This naming suffix {@code IT} reflects that the test exercises Dagger annotation-processor
 * code generation (a runtime composition concern), not just unit logic. Dagger generates
 * implementations for the {@code @Component} interfaces defined in this test class during the
 * same annotation-processing phase as the test sources. The test does not require a database or
 * network.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowComposeStartupContractIT {

    // --- Component: no SERVICE handler ---

    /**
     * Dagger module that contributes only a {@link DestinationType#DELAYED_JOB} handler. No
     * {@code SERVICE} handler is registered — simulates a misconfigured application. It provides a
     * real registry that resolves the contributed validators per build and registers a code
     * definition that dispatches to a service, plus a {@link ServiceTargetResolver} stub; the test
     * focus is the SERVICE-handler check.
     */
    @Module
    abstract static class DelayedJobOnlyModule {
        @Provides
        @Singleton
        @IntoSet
        static OutboxDestinationHandler delayedJobHandler() {
            return new StubDelayedJobHandler();
        }

        @Provides
        @Singleton
        @IntoSet
        static WorkflowDefinitionValidator outboxValidator(WorkflowOutboxComposeValidator impl) {
            return impl;
        }

        @Provides
        @Singleton
        static WorkflowRegistry registry(Provider<Set<WorkflowDefinitionValidator>> validators) {
            DefaultWorkflowRegistry registry =
                    new DefaultWorkflowRegistry(null, validators, new DefaultWorkflowTypeNames(), Optional.empty());
            registry.register(new ServiceDispatchDefinition());
            return registry;
        }

        @Provides
        @Singleton
        static ServiceTargetResolver targetResolver() {
            return new ThrowingTargetResolver();
        }
    }

    /**
     * Test Dagger component with only a {@link DestinationType#DELAYED_JOB} handler.
     *
     * <p>Exposes {@link #workflowRegistry()}: building the registry registers the code definition
     * that dispatches to a service, which the contributed validator refuses.
     */
    @Singleton
    @Component(modules = DelayedJobOnlyModule.class)
    interface ComponentWithoutServiceHandler {
        WorkflowRegistry workflowRegistry();
    }

    // --- Component: with SERVICE handler ---

    /**
     * Dagger module that contributes a {@link DestinationType#SERVICE} handler. Also provides
     * empty stubs for the {@link WorkflowRegistry} and {@link ServiceTargetResolver} dependencies
     * — there are no registered workflows in this test, so the validator's plan-target walk is a
     * no-op.
     */
    @Module
    abstract static class ServiceHandlerModule {
        @Provides
        @Singleton
        @IntoSet
        static OutboxDestinationHandler serviceHandler() {
            return new StubServiceHandler();
        }

        @Provides
        @Singleton
        static WorkflowRegistry registry() {
            return new EmptyWorkflowRegistry();
        }

        @Provides
        @Singleton
        static ServiceTargetResolver targetResolver() {
            return new ThrowingTargetResolver();
        }
    }

    /**
     * Test Dagger component that includes a {@link DestinationType#SERVICE} handler.
     */
    @Singleton
    @Component(modules = ServiceHandlerModule.class)
    interface ComponentWithServiceHandler {
        WorkflowOutboxComposeValidator workflowComposeValidator();
    }

    // --- Tests ---

    @Test
    @DisplayName("component builds successfully even without SERVICE handler (lazy @Singleton)")
    void buildingComponent_withoutServiceHandler_doesNotThrow() {
        // This documents the key invariant: component construction is safe.
        // The registry, and with it the validation, is only built on first accessor call.
        ComponentWithoutServiceHandler component =
                DaggerWorkflowComposeStartupContractIT_ComponentWithoutServiceHandler.create();
        assertThat(component).isNotNull();
    }

    @Test
    @DisplayName(
            "building the registry with a service-dispatching plan and no SERVICE handler throws the documented message")
    void registry_withoutServiceHandler_throws() {
        ComponentWithoutServiceHandler component =
                DaggerWorkflowComposeStartupContractIT_ComponentWithoutServiceHandler.create();

        assertThatThrownBy(component::workflowRegistry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vertique-workflow-services requires a SERVICE OutboxDestinationHandler")
                .hasMessageContaining("TransactionalMessagingServiceModule");
    }

    @Test
    @DisplayName("calling accessor with SERVICE handler returns validator without error")
    void accessor_withServiceHandler_returnsValidator() {
        ComponentWithServiceHandler component =
                DaggerWorkflowComposeStartupContractIT_ComponentWithServiceHandler.create();

        WorkflowOutboxComposeValidator validator = component.workflowComposeValidator();
        assertThat(validator).isNotNull();
    }

    // --- Stub handlers ---

    /** Stub that returns {@link DestinationType#SERVICE}. */
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

    /** Stub that returns {@link DestinationType#DELAYED_JOB}. */
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

    // --- Stub registry / resolver ---

    /**
     * Empty {@link WorkflowRegistry} that has no registered workflows, for the component that has a
     * SERVICE handler and only exposes the validator.
     */
    private static final class EmptyWorkflowRegistry implements WorkflowRegistry {
        @Override
        public void register(dev.vertique.workflow.dsl.WorkflowDefinition<?, ?> def) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public RuntimeWorkflow resolve(DefinitionKey key) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public Optional<RuntimeWorkflow> find(DefinitionKey key) {
            return Optional.empty();
        }

        @Override
        public io.vertx.core.Future<RuntimeWorkflow> load(DefinitionKey key) {
            return find(key)
                    .map(io.vertx.core.Future::succeededFuture)
                    .orElseGet(() -> io.vertx.core.Future.failedFuture(
                            new dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException(key)));
        }

        @Override
        public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
            return Optional.empty();
        }

        @Override
        public Collection<RuntimeWorkflow> codeDefinitions() {
            return List.of();
        }
    }

    /** Code definition with one service-dispatch step. */
    private static final class ServiceDispatchDefinition
            implements WorkflowDefinition<DispatchState, DispatchContract> {
        @Override
        public Class<DispatchContract> contract() {
            return DispatchContract.class;
        }

        @Override
        public Class<DispatchState> stateType() {
            return DispatchState.class;
        }

        @Override
        public String definitionId() {
            return "startup-contract-dispatch";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<DispatchState> wf) {
            wf.init(DispatchState.class, s -> s)
                    .initialStep("charge")
                    .dispatch("charge", "billing.charge", state -> state, "done")
                    .complete("done");
        }
    }

    /** State of {@link ServiceDispatchDefinition}. */
    record DispatchState(String id) {}

    /** Contract of {@link ServiceDispatchDefinition}. */
    interface DispatchContract {}

    /**
     * Stub {@link ServiceTargetResolver} that throws on every call. The empty registry ensures
     * the validator never invokes the resolver, so this throwing implementation is safe.
     */
    private static final class ThrowingTargetResolver implements ServiceTargetResolver {
        @Override
        public ResolvedServiceTarget resolve(String targetId) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public ResolvedServiceTarget resolve(Class<?> contract, java.lang.reflect.Method method) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public ResolvedServiceTarget resolve(Class<?> contract, String operationId) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public java.util.Set<String> supportedTargetIds() {
            return java.util.Set.of();
        }
    }
}
