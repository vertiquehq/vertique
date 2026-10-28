// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests verifying that the first segment of a recorded start resolves the workflow subject ref
 * according to the three-branch precedence rule:
 *
 * <ol>
 *   <li><strong>Caller wins</strong> — {@link StartCommand#subjectRef()} is non-null → stored as-is;
 *       the plan resolver is NOT invoked.</li>
 *   <li><strong>Resolver fallback</strong> — {@code StartCommand.subjectRef} is null and the plan
 *       has a {@code .subject(...)} resolver → resolver is invoked; its return value is stored.</li>
 *   <li><strong>Neither</strong> — both are null → instance is stored with a null subject ref.</li>
 *   <li><strong>Resolver returns null</strong> — the segment fails, rolls back, and the instance stays
 *       {@code PENDING} with no state and no subject.</li>
 * </ol>
 *
 * <p>A start only records: the subject is resolved by the executor, so each test awaits the instance before it reads
 * the subject.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgWorkflowEngineSubjectResolverIT {

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

    // --- Engine and helpers ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_subject_resolver_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static Pool pool;
    static PgWorkflowInstanceRepository instances;
    static DefaultWorkflowRegistry registry;
    static WorkflowEngineHandle engine;
    static final AtomicBoolean spyResolverCalled = new AtomicBoolean();

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        instances = new PgWorkflowInstanceRepository(pool, ex);

        registry = new DefaultWorkflowRegistry();
        registry.register(NO_RESOLVER_DEF);
        registry.register(WITH_RESOLVER_DEF);
        registry.register(spyDefinition());

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                new PgWorkflowHistoryRepository(pool, ex),
                new PgWorkflowDedupRepository(pool, ex),
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @BeforeEach
    void truncate() {
        spyResolverCalled.set(false);
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Records a start in a transaction of its own and returns once the executor ran it. */
    private static WorkflowInstanceId startAndAwait(StartCommand command) throws Exception {
        WorkflowInstanceId id =
                engine.start(command).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine.executionProbe()
                .awaitIdle(id, IDLE_TIMEOUT)
                .toCompletionStage()
                .toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        return id;
    }

    private static WorkflowInstance instanceOf(WorkflowInstanceId id) throws Exception {
        return instances
                .findById(id)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .orElseThrow();
    }

    private static WorkflowDefinition<ArticleState, SpyResolverContract> spyDefinition() {
        return new WorkflowDefinition<>() {
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
                            spyResolverCalled.set(true);
                            return new WorkflowSubjectRef("Article", state.articleId(), state.version());
                        })
                        .init(ArticleState.class, s -> s)
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    // --- Tests ---

    @Test
    @DisplayName("Caller wins: cmd.subjectRef is non-null → stored; resolver NOT invoked")
    void callerWins_subjectRefStoredAndResolverNotInvoked() throws Exception {
        WorkflowSubjectRef callerRef = new WorkflowSubjectRef("Article", "art-1", "v7");
        // A state with articleId=null would make the resolver of WITH_RESOLVER_DEF return null and fail the start, so
        // the start succeeds only if the caller's ref wins.
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver",
                WithResolverContract.class.getName(),
                new ArticleState(null, null),
                "caller-wins-key-1",
                null,
                callerRef,
                null,
                null,
                null);

        WorkflowInstance instance = instanceOf(startAndAwait(cmd));

        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertEquals(callerRef, instance.subjectRef(), "the instance must carry the caller-supplied ref");
    }

    @Test
    @DisplayName("Resolver fallback: cmd.subjectRef is null, plan has resolver → resolver invoked, ref stored")
    void resolverFallback_resolverInvokedAndRefStored() throws Exception {
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver",
                WithResolverContract.class.getName(),
                new ArticleState("art-42", "v3"),
                "resolver-fallback-key-1",
                null,
                null /* no caller ref */,
                null,
                null,
                null);

        WorkflowSubjectRef ref = instanceOf(startAndAwait(cmd)).subjectRef();

        assertNotNull(ref, "the resolver's ref must be stored");
        assertEquals("Article", ref.type(), "subject type must match resolver output");
        assertEquals("art-42", ref.id(), "subject id must match resolver output");
        assertEquals("v3", ref.version(), "subject version must match resolver output");
    }

    @Test
    @DisplayName("Neither: cmd.subjectRef is null and plan has no resolver → instance stored with null subjectRef")
    void neither_subjectRefIsNull() throws Exception {
        StartCommand cmd = new StartCommand(
                "subj-test-no-resolver",
                NoResolverContract.class.getName(),
                new ArticleState("art-99", "v1"),
                "neither-key-1",
                null,
                null /* no caller ref */,
                null,
                null,
                null);

        WorkflowInstance instance = instanceOf(startAndAwait(cmd));

        assertEquals(WorkflowStatus.COMPLETED, instance.status());
        assertNull(instance.subjectRef(), "subject ref must be null when neither caller nor resolver is provided");
    }

    @Test
    @DisplayName("Resolver returns null → the first segment fails and rolls back; the start and the instance fail")
    void resolverReturnsNull_segmentFailsAndInstanceFails() throws Exception {
        // articleId=null causes the resolver in WITH_RESOLVER_DEF to return null.
        StartCommand cmd = new StartCommand(
                "subj-test-with-resolver",
                WithResolverContract.class.getName(),
                new ArticleState(null, null),
                "resolver-null-key-1",
                null,
                null /* no caller ref */,
                null,
                null,
                null);

        // The start is recorded: the resolver runs in the executor, not in the caller
        WorkflowInstanceId id =
                engine.start(cmd).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // The executor claims the command and its segment fails on the resolver
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String commandState = "PENDING";
        while (System.nanoTime() < deadline && !"FAILED".equals(commandState)) {
            commandState = commandState(id);
            Thread.sleep(50);
        }

        // Then nothing of the segment committed: no state, no subject, and the only history entry is the failure
        WorkflowInstance instance = instanceOf(id);
        assertEquals(WorkflowStatus.FAILED, instance.status());
        assertNull(instance.stateJson());
        assertNull(instance.subjectRef());
        assertEquals("FAILED", commandState(id), "the failed segment settles its command as failed");
        assertEquals(1L, historyEntries(id), "the failed segment leaves only the entry of the failure");
    }

    @Test
    @DisplayName("Caller wins: resolver configured but caller ref non-null → resolver never invoked")
    void callerWins_resolverNotInvokedVerifiedViaSpy() throws Exception {
        WorkflowSubjectRef callerRef = new WorkflowSubjectRef("Article", "art-1", "v7");
        StartCommand cmd = new StartCommand(
                "subj-test-spy-resolver",
                SpyResolverContract.class.getName(),
                new ArticleState("art-1", "v7"),
                "spy-caller-wins-key-1",
                null,
                callerRef,
                1L,
                null,
                null);

        WorkflowInstance instance = instanceOf(startAndAwait(cmd));

        assertEquals(callerRef, instance.subjectRef());
        assertFalse(spyResolverCalled.get(), "resolver must NOT be invoked when caller supplies a subject ref");
        assertTrue(instance.status() == WorkflowStatus.COMPLETED);
    }

    private static String commandState(WorkflowInstanceId id) throws Exception {
        return pool.preparedQuery("SELECT state FROM workflow_commands WHERE workflow_id = $1")
                .execute(Tuple.of(id.value()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .iterator()
                .next()
                .getString("state");
    }

    private static long historyEntries(WorkflowInstanceId id) throws Exception {
        return pool.preparedQuery("SELECT count(*) FROM workflow_history WHERE workflow_id = $1")
                .execute(Tuple.of(id.value()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .iterator()
                .next()
                .getLong(0);
    }
}
