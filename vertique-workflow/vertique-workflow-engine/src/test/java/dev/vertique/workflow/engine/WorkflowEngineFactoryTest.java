// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that the engine is assembled from {@link WorkflowEngineParts}, that the parts cannot be built without a
 * definition store, and that the parts type offers the builder as its only way in.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowEngineFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "factory-flow";

    @WorkflowContract
    public interface FactoryContract {}

    public record FactoryState(String value) {}

    public record FactoryStart(String value) {}

    /** A code definition whose only step is its end step. */
    private static final class FactoryWorkflow implements WorkflowDefinition<FactoryState, Object> {
        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) FactoryContract.class;
        }

        @Override
        public Class<FactoryState> stateType() {
            return FactoryState.class;
        }

        @Override
        public String definitionId() {
            return DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<FactoryState> workflow) {
            workflow.init(FactoryStart.class, start -> new FactoryState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    /** The registry and the repository that records started instances, shared by the builders of one test. */
    private record Fixture(
            DefaultWorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup) {}

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new FactoryWorkflow());

        WorkflowInstanceRepository<SqlClient> instances = mock(WorkflowInstanceRepository.class);
        WorkflowHistoryRepository<SqlClient> history = mock(WorkflowHistoryRepository.class);
        WorkflowDedupRepository<SqlClient> dedup = mock(WorkflowDedupRepository.class);
        when(dedup.findStart(any(), any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.claimOrResolveStart(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Future.succeededFuture(
                        new StartDedupResult(new WorkflowInstanceId(UUID.randomUUID()), true, "definitionVersion=1")));
        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());
        return new Fixture(registry, instances, history, dedup);
    }

    /** A builder holding every required part except the named one. */
    @SuppressWarnings("unchecked")
    private static WorkflowEngineParts.Builder partsWithout(Fixture fixture, String omitted) {
        WorkflowEngineParts.Builder builder = WorkflowEngineParts.builder()
                .txRunner(mock(WorkflowTransactionRunner.class))
                .registry(fixture.registry())
                .instances(fixture.instances())
                .history(fixture.history())
                .dedup(fixture.dedup())
                .branchTokens(mock(BranchTokenRepository.class))
                .joinStates(mock(JoinStateRepository.class))
                .timerStore(mock(TimerStore.class))
                .taskStore(mock(TaskStore.class))
                .clock(CLOCK)
                .typeNames(fixture.registry().typeNames());
        if (!"definitionStore".equals(omitted)) {
            throw new IllegalArgumentException("only definitionStore can be omitted: " + omitted);
        }
        return builder;
    }

    /** A store that holds the code row of the registered definition. */
    private static InMemoryWorkflowDefinitionRegistryStore seededStore(Fixture fixture) {
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, 1);
        RuntimeWorkflow runtime = fixture.registry().resolve(key);
        InMemoryWorkflowDefinitionRegistryStore store = new InMemoryWorkflowDefinitionRegistryStore();
        store.upsertCode(new WorkflowDefinitionRecord(
                        key,
                        DefinitionKind.CODE,
                        runtime.contractName(),
                        FactoryState.class.getName(),
                        runtime.plan().planHash(),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance("code", null, null, null, null, NOW),
                        null,
                        NOW,
                        NOW,
                        Map.of(),
                        null))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return store;
    }

    @Test
    @DisplayName("the engine parts refuse a missing definition store and the factory runs the engine over a seeded one")
    void createRequiresTheDefinitionStore(Vertx vertx, VertxTestContext ctx) {
        // Given mocked repositories, a registry with one code definition and parts that lack the definition store
        Fixture fixture = fixture();
        WorkflowEngineParts.Builder withoutStore = partsWithout(fixture, "definitionStore");

        // When the parts are built without the store
        // Then the build fails naming the missing component
        assertThatThrownBy(withoutStore::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("definitionStore");

        // And the parts type is final, is not a record and offers no public or protected constructor
        assertThat(Modifier.isFinal(WorkflowEngineParts.class.getModifiers())).isTrue();
        assertThat(WorkflowEngineParts.class.isRecord()).isFalse();
        assertThat(WorkflowEngineParts.class.getDeclaredConstructors()).allSatisfy(constructor -> assertThat(
                        constructor.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED))
                .isZero());

        // When the same builder gets a store seeded with the definition's code row and the factory assembles the engine
        WorkflowEngineHandle handle = WorkflowEngineFactory.create(
                withoutStore.definitionStore(seededStore(fixture)).build());

        // And a start of the definition runs
        StartCommand command = new StartCommand(
                DEFINITION_ID,
                FactoryContract.class.getName(),
                new FactoryStart("order"),
                "start-" + UUID.randomUUID(),
                null,
                null,
                1L,
                null,
                null);
        ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicated.runOnContext(ignored -> handle.start(command, ActiveTransactionClient.create())
                .onComplete(result -> ctx.verify(() -> {
                    // Then the engine inserted one instance of the definition
                    assertThat(result.succeeded())
                            .as("start result: %s", result.cause())
                            .isTrue();
                    ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
                    verify(fixture.instances()).insert(inserted.capture(), any());
                    assertThat(inserted.getValue().definitionId()).isEqualTo(DEFINITION_ID);
                    ctx.completeNow();
                })));
    }
}
