// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that an engine without a registry store rebuilds the default scope's current versions by the same rule that
 * decides them with a store: the highest registered, non-deprecated version that does not opt out, and never a
 * document that was loaded through the definition service.
 *
 * <p>The engine and the lifecycle are assembled over a real registry, as the application graph assembles them when no
 * store is bound; only the persistence-facing collaborators are mocks.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NoStoreActivationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    private static final String ORDER_ID = "ns-order";
    private static final String DOCUMENT_ID = "ns-doc";

    @WorkflowContract
    public interface NoStoreContract {}

    public record NoStoreState(String value) {}

    public record NoStoreStart(String value) {}

    /** The assembled engine and lifecycle over one registry, and the repository that records the started instances. */
    private record Rig(
            WorkflowEngineHandle engine,
            DefaultWorkflowDefinitionLifecycle lifecycle,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances) {}

    @SuppressWarnings("unchecked")
    private static Rig rig(WorkflowDefinition<?, ?>... startupDefinitions) {
        WorkflowRegistry registry = new DefaultWorkflowRegistry();
        for (WorkflowDefinition<?, ?> definition : startupDefinitions) registry.register(definition);

        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        when(dedup.findStart(any(), any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.claimOrResolveStart(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Future.succeededFuture(
                        new StartDedupResult(new WorkflowInstanceId(UUID.randomUUID()), true, "unused")));
        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        WorkflowEngineHandle engine = WorkflowEngineFactory.create(
                mock(WorkflowTransactionRunner.class),
                registry,
                instances,
                history,
                dedup,
                mock(BranchTokenRepository.class),
                mock(JoinStateRepository.class),
                mock(TimerStore.class),
                mock(TaskStore.class),
                FIXED_CLOCK,
                Set.<WorkflowSideEffectRecorder<SqlClient>>of(),
                WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
                Optional.empty(),
                null);
        DefaultWorkflowDefinitionLifecycle lifecycle = new DefaultWorkflowDefinitionLifecycle(
                Optional.empty(), () -> registry, Set.of(), new DefinitionApplyQueue());
        return new Rig(engine, lifecycle, registry, instances);
    }

    private static <T> T onContext(Vertx vertx, Supplier<Future<T>> operation) throws Exception {
        CompletableFuture<T> settled = new CompletableFuture<>();
        ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicated.runOnContext(ignored -> operation.get().onComplete(result -> {
            if (result.succeeded()) settled.complete(result.result());
            else settled.completeExceptionally(result.cause());
        }));
        return settled.get(5, TimeUnit.SECONDS);
    }

    private static Future<WorkflowInstanceId> start(Rig rig, String id) {
        String key = "start-" + UUID.randomUUID();
        return rig.engine()
                .start(
                        new StartCommand(
                                id,
                                NoStoreContract.class.getName(),
                                new NoStoreStart("order"),
                                key,
                                null,
                                null,
                                null,
                                null,
                                null),
                        ActiveTransactionClient.create());
    }

    /** The definition id and version of every instance the engine has inserted so far. */
    private static List<org.assertj.core.groups.Tuple> insertedPins(Rig rig) {
        ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(rig.instances(), atLeast(0)).insert(inserted.capture(), any());
        return inserted.getAllValues().stream()
                .map(instance -> tuple(instance.definitionId(), instance.definitionVersion()))
                .toList();
    }

    @Test
    @DisplayName("without a store the startup rebuilds the default activations by the auto-activation rule")
    void noStoreStartupRebuildsDefaultActivationsByTheAutoActivationRule(Vertx vertx) throws Exception {
        // Given an engine without a store that registered versions 1 and 2 of ns-order and a version 3 that opts out
        Rig rig = rig(code(ORDER_ID, 1, true), code(ORDER_ID, 2, true), code(ORDER_ID, 3, false));

        // And a document of ns-doc that the definition service loaded and published after the startup
        rig.registry().register(WorkflowScopes.DEFAULT, code(DOCUMENT_ID, 1, true));

        // When the default scope's activations are listed and each id starts without a version
        List<DefinitionActivation> activations =
                onContext(vertx, () -> rig.lifecycle().activations(WorkflowScopes.DEFAULT));
        onContext(vertx, () -> start(rig, ORDER_ID));
        Throwable documentStart = onContext(vertx, () -> start(rig, DOCUMENT_ID)
                .transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null)));

        // Then ns-order is listed as current on version 2, never on the version that opts out
        assertThat(activations)
                .filteredOn(activation -> activation.definitionId().equals(ORDER_ID))
                .extracting(
                        DefinitionActivation::scopeKey,
                        DefinitionActivation::targetScopeKey,
                        DefinitionActivation::version)
                .containsExactly(tuple(WorkflowScopes.DEFAULT, WorkflowScopes.DEFAULT, 2L));

        // And ns-order started on version 2
        assertThat(insertedPins(rig)).containsExactly(tuple(ORDER_ID, 2L));

        // And ns-doc has no record and its start failed as having no current version
        assertThat(activations)
                .noneMatch(activation -> activation.definitionId().equals(DOCUMENT_ID));
        assertThat(documentStart).isInstanceOfSatisfying(WorkflowNoCurrentVersionException.class, refusal -> {
            assertThat(refusal.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(refusal.definitionId()).isEqualTo(DOCUMENT_ID);
        });
    }

    @Test
    @DisplayName("without a store a restart rebuilds the default activations and forgets every other scope's")
    void restartRebuildsDefaultActivationsAndForgetsScopeActivations(Vertx vertx) throws Exception {
        // Given a component without a store whose code has versions 1 and 2, so version 2 is current
        Rig first = rig(code(ORDER_ID, 1, true), code(ORDER_ID, 2, true));

        // And version 1 activated in the default scope and in qa
        onContext(vertx, () -> first.lifecycle().activate(WorkflowScopes.DEFAULT, ORDER_ID, WorkflowScopes.DEFAULT, 1));
        onContext(vertx, () -> first.lifecycle().activate("qa", ORDER_ID, WorkflowScopes.DEFAULT, 1));
        List<DefinitionActivation> beforeRestart =
                onContext(vertx, () -> first.lifecycle().activations(null));

        // When a second component is built from the same code
        Rig second = rig(code(ORDER_ID, 1, true), code(ORDER_ID, 2, true));
        List<DefinitionActivation> defaultAfterRestart =
                onContext(vertx, () -> second.lifecycle().activations(WorkflowScopes.DEFAULT));
        List<DefinitionActivation> qaAfterRestart =
                onContext(vertx, () -> second.lifecycle().activations("qa"));

        // Then the first component held both choices
        assertThat(beforeRestart)
                .extracting(
                        DefinitionActivation::scopeKey,
                        DefinitionActivation::targetScopeKey,
                        DefinitionActivation::version)
                .containsExactlyInAnyOrder(
                        tuple(WorkflowScopes.DEFAULT, WorkflowScopes.DEFAULT, 1L),
                        tuple("qa", WorkflowScopes.DEFAULT, 1L));

        // And the second has the default scope's current on version 2 again and no activation of qa
        assertThat(defaultAfterRestart)
                .extracting(
                        DefinitionActivation::scopeKey,
                        DefinitionActivation::targetScopeKey,
                        DefinitionActivation::version)
                .containsExactly(tuple(WorkflowScopes.DEFAULT, WorkflowScopes.DEFAULT, 2L));
        assertThat(qaAfterRestart).isEmpty();
    }

    private static WorkflowDefinition<?, ?> code(String definitionId, long version, boolean activateOnDeploy) {
        return new CodeWorkflow(definitionId, version, activateOnDeploy);
    }

    /** A definition whose only step is its end step, optionally opting out of activation on deploy. */
    private static final class CodeWorkflow implements WorkflowDefinition<NoStoreState, Object> {

        private final String definitionId;
        private final long version;
        private final boolean activateOnDeploy;

        private CodeWorkflow(String definitionId, long version, boolean activateOnDeploy) {
            this.definitionId = definitionId;
            this.version = version;
            this.activateOnDeploy = activateOnDeploy;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) NoStoreContract.class;
        }

        @Override
        public Class<NoStoreState> stateType() {
            return NoStoreState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public boolean activateOnDeploy() {
            return activateOnDeploy;
        }

        @Override
        public void define(WorkflowBuilder<NoStoreState> workflow) {
            workflow.init(NoStoreStart.class, start -> new NoStoreState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
