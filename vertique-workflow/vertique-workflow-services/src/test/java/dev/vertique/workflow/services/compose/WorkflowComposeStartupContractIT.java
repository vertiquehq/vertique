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
import dev.vertique.workflow.contract.WorkflowContractMetadata;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test that verifies the optional early-fail-fast accessor for
 * {@link WorkflowOutboxComposeValidator}.
 *
 * <p>In production the validator is enforced through
 * {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder}'s constructor and
 * runs at Dagger graph construction whenever the workflow engine is built. Applications can
 * additionally expose {@code WorkflowOutboxComposeValidator workflowComposeValidator()} on their
 * {@code AppComponent} and call it during {@code MainVerticle.start()} to fail fast before any
 * verticle deploys; this test exercises that optional accessor surface.
 *
 * <p>The component variants in this test do NOT include the recorder binding (which is what
 * normally forces validation at graph build time), so they isolate the accessor pathway:
 * <ol>
 *   <li>Constructing the component does NOT throw — the validator is a lazy
 *       {@code @Singleton}.</li>
 *   <li>Calling the accessor on a component composed WITHOUT a {@code SERVICE} handler throws
 *       {@link IllegalStateException} with the documented message.</li>
 *   <li>Calling the accessor on a component composed WITH a {@code SERVICE} handler returns the
 *       validator without error.</li>
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
     * {@code SERVICE} handler is registered — simulates a misconfigured application. Also provides
     * empty stubs for the new {@link WorkflowRegistry} and {@link ServiceTargetResolver}
     * dependencies so the validator's plan-target walk has nothing to inspect; the test focus is
     * the SERVICE-handler check.
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
     * Test Dagger component with only a {@link DestinationType#DELAYED_JOB} handler.
     *
     * <p>Exposes {@link #workflowComposeValidator()} as the accessor to invoke for the startup
     * contract check. This mirrors the required {@code AppComponent} accessor pattern documented
     * in {@link WorkflowOutboxComposeValidator}.
     */
    @Singleton
    @Component(modules = DelayedJobOnlyModule.class)
    interface ComponentWithoutServiceHandler {
        WorkflowOutboxComposeValidator workflowComposeValidator();
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
        // The validator is only constructed on first accessor call.
        ComponentWithoutServiceHandler component =
                DaggerWorkflowComposeStartupContractIT_ComponentWithoutServiceHandler.create();
        assertThat(component).isNotNull();
    }

    @Test
    @DisplayName("calling accessor without SERVICE handler throws IllegalStateException with documented message")
    void accessor_withoutServiceHandler_throws() {
        ComponentWithoutServiceHandler component =
                DaggerWorkflowComposeStartupContractIT_ComponentWithoutServiceHandler.create();

        assertThatThrownBy(component::workflowComposeValidator)
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
     * Empty {@link WorkflowRegistry} that has no registered workflows. The validator's plan-target
     * walk iterates an empty collection, which is fine for these tests — the focus is the
     * SERVICE-handler check.
     */
    private static final class EmptyWorkflowRegistry implements WorkflowRegistry {
        @Override
        public void register(dev.vertique.workflow.dsl.WorkflowDefinition<?, ?> def) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public RuntimeWorkflow resolveCurrent(String definitionId) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public RuntimeWorkflow resolvePinned(String definitionId, long version) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public WorkflowContractMetadata contractMetadata(Class<?> contractInterface) {
            throw new UnsupportedOperationException("not used in startup-contract test");
        }

        @Override
        public Collection<RuntimeWorkflow> allRegistered() {
            return List.of();
        }
    }

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
