// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.timer.TimerStatusTransition;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit-level tests verifying that {@link WorkflowEngineHandle#start} resolves the workflow subject ref
 * according to the ADR-0056 three-branch precedence rule:
 *
 * <ol>
 *   <li><strong>Caller wins</strong> — {@link StartCommand#subjectRef()} is non-null → stored as-is;
 *       the plan resolver is NOT invoked.</li>
 *   <li><strong>Resolver fallback</strong> — {@code StartCommand.subjectRef} is null and the plan
 *       has a {@code .subject(...)} resolver → resolver is invoked; its return value is stored.</li>
 *   <li><strong>Neither</strong> — both are null → instance is stored with a null subject ref.</li>
 *   <li><strong>Resolver returns null</strong> — start fails with
 *       {@link WorkflowDefinitionException}; no instance row is inserted.</li>
 * </ol>
 *
 * <p>Uses Mockito stubs for the database layer (same pattern as
 * {@link PgWorkflowEngineEventEmissionTest}). All assertions are made on the
 * {@link WorkflowInstance} captured from the {@code instanceRepo.insert} mock.
 */
class PgWorkflowEngineSubjectResolverTest {

    // --- Domain types ---

    /**
     * Minimal workflow state carrying fields that the subject resolver reads.
     *
     * @param articleId the article identifier
     * @param version   the article version string
     */
    record ArticleState(String articleId, String version) {}

    // --- Contract markers ---

    /** Contract marker for the plan that has no subject resolver configured. */
    interface NoResolverContract {}

    /** Contract marker for the plan that has a subject resolver configured. */
    interface WithResolverContract {}

    /** Contract marker for the inline spy-resolver definition used by the caller-wins spy test. */
    interface SpyResolverContract {}

    // --- Workflow definitions ---

    /**
     * Minimal instant-complete workflow with NO subject resolver.
     * Used for the "neither" and "caller-wins" cases.
     */
    static final WorkflowDefinition<ArticleState, NoResolverContract> NO_RESOLVER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<NoResolverContract> contract() {
            return NoResolverContract.class;
        }

        @Override
        public Class<ArticleState> stateType() {
            return ArticleState.class;
        }

        @Override
        public String definitionId() {
            return "subj-test-no-resolver";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ArticleState> wf) {
            wf.init(ArticleState.class, s -> s).initialStep("done").complete("done");
        }
    };

    /**
     * Instant-complete workflow that has a {@code .subject(...)} resolver configured.
     * Used for the "resolver-fallback" and "resolver-returns-null" cases.
     */
    static final WorkflowDefinition<ArticleState, WithResolverContract> WITH_RESOLVER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<WithResolverContract> contract() {
            return WithResolverContract.class;
        }

        @Override
        public Class<ArticleState> stateType() {
            return ArticleState.class;
        }

        @Override
        public String definitionId() {
            return "subj-test-with-resolver";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ArticleState> wf) {
            wf.subject(state -> {
                        // The test overrides this via an AtomicBoolean flag trick is not
                        // needed; instead we use a second definition for the null-returns
                        // case. This resolver always derives a ref from the state fields.
                        if (state.articleId() == null) {
                            // Signal: resolver should return null (used by null-return test).
                            return null;
                        }
                        return new WorkflowSubjectRef("Article", state.articleId(), state.version());
                    })
                    .init(ArticleState.class, s -> s)
                    .initialStep("done")
                    .complete("done");
        }
    };

    // --- Mocks, engine, and helpers ---

    PgWorkflowHistoryRepository historyRepo;
    PgWorkflowInstanceRepository instanceRepo;
    PgWorkflowDedupRepository dedupRepo;
    PgTaskStore taskStore;
    PgTimerStore timerStore;
    Pool pool;
    SqlClient tx;
    DefaultWorkflowRegistry registry;
    WorkflowEngineHandle engine;

    final AtomicLong seqCounter = new AtomicLong(0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        seqCounter.set(0);

        historyRepo = mock(PgWorkflowHistoryRepository.class);
        instanceRepo = mock(PgWorkflowInstanceRepository.class);
        dedupRepo = mock(PgWorkflowDedupRepository.class);
        taskStore = mock(PgTaskStore.class);
        timerStore = mock(PgTimerStore.class);
        pool = mock(Pool.class);
        tx = mock(SqlClient.class);

        when(historyRepo.nextSequence(any(WorkflowInstanceId.class), any(SqlClient.class)))
                .thenAnswer(inv -> Future.succeededFuture(seqCounter.incrementAndGet()));
        when(historyRepo.append(any(WorkflowHistoryEntry.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture());
        when(historyRepo.listByInstance(any(WorkflowInstanceId.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(List.of()));
        when(taskStore.incrementRemindersFiredCount(any(UUID.class), any()))
                .thenReturn(Future.succeededFuture(Optional.empty()));
        when(timerStore.findScheduledRemindersForTask(any(UUID.class), any()))
                .thenReturn(Future.succeededFuture(List.of()));
        when(timerStore.markCancelled(any(UUID.class), any(Instant.class), any()))
                .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

        WorkflowSideEffectRecorder<SqlClient> eventRecorder = new WorkflowSideEffectRecorder<>() {
            private final List<WorkflowSideEffectIntent> ignored = new ArrayList<>();

            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_EVENT;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient sqlTx) {
                ignored.add(intent);
                return Future.succeededFuture(RecorderResult.empty());
            }
        };

        registry = new DefaultWorkflowRegistry();
        registry.register(NO_RESOLVER_DEF);
        registry.register(WITH_RESOLVER_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(eventRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                taskStore,
                Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * Stubs the instance repository: insert succeeds, findById returns a running instance at
     * {@code "done"}, and optimistic update returns 1.
     *
     * @param id the instance id to use
     */
    private void stubInstance(WorkflowInstanceId id) {
        when(instanceRepo.insert(any(WorkflowInstance.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture());
        Instant now = Instant.parse("2026-05-09T00:00:00Z");
        WorkflowInstance inst = new WorkflowInstance(
                id,
                "placeholder-def",
                1L,
                "hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "done",
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
        when(instanceRepo.findById(eq(id), any(SqlClient.class))).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(instanceRepo.updateOptimistic(any(WorkflowInstance.class), any(Long.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(1));
    }

    /**
     * Stubs the dedup repository so that {@code claimOrResolveStart} succeeds with the given id.
     *
     * @param id the instance id the dedup winner will return
     */
    private void stubDedup(WorkflowInstanceId id) {
        when(dedupRepo.claimOrResolveStart(
                        any(String.class),
                        any(String.class),
                        any(WorkflowInstanceId.class),
                        any(String.class),
                        any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(new StartDedupResult(id, true, "definitionVersion=1")));
    }

    // --- Tests ---

    @Test
    @DisplayName("Caller wins: cmd.subjectRef is non-null → stored; resolver NOT invoked")
    void callerWins_subjectRefStoredAndResolverNotInvoked() {
        // Arrange: the plan WITH_RESOLVER_DEF has a resolver. The resolver throws if articleId is
        // null; if the caller-supplied ref is used instead the resolver is never reached.
        // We verify "resolver not invoked" by supplying a state that would cause the resolver to
        // return null (articleId=null) — if the resolver were invoked, the start would fail.
        WorkflowSubjectRef callerRef = new WorkflowSubjectRef("Article", "art-1", "v7");
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        stubInstance(id);
        stubDedup(id);

        ArgumentCaptor<WorkflowInstance> insertCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        when(instanceRepo.insert(insertCaptor.capture(), any(SqlClient.class))).thenReturn(Future.succeededFuture());

        // Pass a state with articleId=null — the resolver would return null, but caller ref wins.
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver", new ArticleState(null, null), "caller-wins-key-1", null, callerRef);

        Future<WorkflowInstanceId> result = engine.start(cmd, tx);

        assertTrue(result.succeeded(), "start() must succeed; cause: " + result.cause());
        WorkflowInstance inserted = insertCaptor.getValue();
        assertEquals(callerRef, inserted.subjectRef(), "inserted instance must carry the caller-supplied ref");
        assertEquals("Article", inserted.subjectRef().type());
        assertEquals("art-1", inserted.subjectRef().id());
        assertEquals("v7", inserted.subjectRef().version());
    }

    @Test
    @DisplayName("Resolver fallback: cmd.subjectRef is null, plan has resolver → resolver invoked, ref stored")
    void resolverFallback_resolverInvokedAndRefStored() {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        stubInstance(id);
        stubDedup(id);

        ArgumentCaptor<WorkflowInstance> insertCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        when(instanceRepo.insert(insertCaptor.capture(), any(SqlClient.class))).thenReturn(Future.succeededFuture());

        // articleId is non-null → resolver returns WorkflowSubjectRef("Article", "art-42", "v3")
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver",
                new ArticleState("art-42", "v3"),
                "resolver-fallback-key-1",
                null,
                null /* no caller ref */);

        Future<WorkflowInstanceId> result = engine.start(cmd, tx);

        assertTrue(result.succeeded(), "start() must succeed; cause: " + result.cause());
        WorkflowInstance inserted = insertCaptor.getValue();
        WorkflowSubjectRef ref = inserted.subjectRef();
        assertEquals("Article", ref.type(), "subject type must match resolver output");
        assertEquals("art-42", ref.id(), "subject id must match resolver output");
        assertEquals("v3", ref.version(), "subject version must match resolver output");
    }

    @Test
    @DisplayName("Neither: cmd.subjectRef is null and plan has no resolver → instance stored with null subjectRef")
    void neither_subjectRefIsNull() {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        stubInstance(id);
        stubDedup(id);

        ArgumentCaptor<WorkflowInstance> insertCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        when(instanceRepo.insert(insertCaptor.capture(), any(SqlClient.class))).thenReturn(Future.succeededFuture());

        StartCommand cmd = new StartCommand(
                "subj-test-no-resolver",
                new ArticleState("art-99", "v1"),
                "neither-key-1",
                null,
                null /* no caller ref */);

        Future<WorkflowInstanceId> result = engine.start(cmd, tx);

        assertTrue(result.succeeded(), "start() must succeed; cause: " + result.cause());
        WorkflowInstance inserted = insertCaptor.getValue();
        assertNull(inserted.subjectRef(), "subject ref must be null when neither caller nor resolver is provided");
    }

    @Test
    @DisplayName("Resolver returns null → start fails with WorkflowDefinitionException; no instance inserted")
    void resolverReturnsNull_startFailsNoInstanceInserted() {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        // Do NOT stub instanceRepo.insert — if insert is called the test fails on verify(never).
        stubDedup(id);

        // articleId=null causes the resolver in WITH_RESOLVER_DEF to return null.
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver",
                new ArticleState(null, null),
                "resolver-null-key-1",
                null,
                null /* no caller ref */);

        Future<WorkflowInstanceId> result = engine.start(cmd, tx);

        assertTrue(result.failed(), "start() must fail when resolver returns null");
        assertInstanceOf(
                WorkflowDefinitionException.class,
                result.cause(),
                "failure must be WorkflowDefinitionException; was: " + result.cause());
        assertTrue(
                result.cause().getMessage().contains("subj-test-with-resolver"),
                "exception message must name the definition id; was: "
                        + result.cause().getMessage());

        // No instance row must have been inserted.
        verify(instanceRepo, never()).insert(any(WorkflowInstance.class), any(SqlClient.class));
    }

    @Test
    @DisplayName(
            "Caller wins: resolver configured but caller ref non-null → resolver never invoked (verified via AtomicBoolean)")
    void callerWins_resolverNotInvokedVerifiedViaSpy() {
        // This test uses a definition built inline with a resolver that records when it is called.
        AtomicBoolean resolverCalled = new AtomicBoolean(false);

        WorkflowDefinition<ArticleState, SpyResolverContract> spyDef = new WorkflowDefinition<>() {
            @Override
            public Class<SpyResolverContract> contract() {
                return SpyResolverContract.class;
            }

            @Override
            public Class<ArticleState> stateType() {
                return ArticleState.class;
            }

            @Override
            public String definitionId() {
                return "subj-test-spy-resolver";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<ArticleState> wf) {
                wf.subject(state -> {
                            resolverCalled.set(true);
                            return new WorkflowSubjectRef("Article", state.articleId(), state.version());
                        })
                        .init(ArticleState.class, s -> s)
                        .initialStep("done")
                        .complete("done");
            }
        };

        registry.register(spyDef);

        WorkflowSubjectRef callerRef = new WorkflowSubjectRef("Article", "art-1", "v7");
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        stubInstance(id);
        stubDedup(id);

        StartCommand cmd = new StartCommand(
                "subj-test-spy-resolver", new ArticleState("art-1", "v7"), "spy-caller-wins-key-1", null, callerRef);

        Future<WorkflowInstanceId> result = engine.start(cmd, tx);

        assertTrue(result.succeeded(), "start() must succeed; cause: " + result.cause());
        assertTrue(!resolverCalled.get(), "resolver must NOT be invoked when caller supplies a subject ref");
    }
}
