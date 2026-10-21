// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.ExpectedDefinition;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies static document metadata and compiled cache are reconciled before managed traffic starts. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionRegistryBootstrapIT {

    private static final String DEFINITION_ID = "order-fulfillment-doc";
    private static final SourceMetadata YAML_METADATA = new SourceMetadata(
            "bootstrap-yaml",
            "classpath:definitions/it/order-fulfillment.workflow.yaml",
            "revision-1",
            "test-suite",
            null,
            Instant.parse("2026-10-18T12:00:00.123456Z"));
    private static final SourceMetadata JSON_METADATA = new SourceMetadata(
            "bootstrap-json",
            "classpath:definitions/it/order-fulfillment.workflow.json",
            "revision-2",
            "test-suite",
            null,
            Instant.parse("2026-10-18T12:00:01.654321Z"));

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_bootstrap_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("static document metadata is active and cached before bootstrap deployment")
    void restoresStaticMetadataBeforeTraffic(Vertx vertx, VertxTestContext context) {
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
        pools.add(pool);

        BootObservation observation = new BootObservation(
                DEFINITION_ID,
                List.of(
                        new ExpectedDefinition(1, DefinitionStatus.AVAILABLE, "YAML", YAML_METADATA),
                        new ExpectedDefinition(2, DefinitionStatus.AVAILABLE, "JSON", JSON_METADATA)));
        AppComponent app =
                ManagedDefinitionDatabase.app(vertx, pool, new JsonObject(), observation, staticDocuments(), false);

        Future<Void> work = pool.preparedQuery(
                        "SELECT count(*) AS row_count FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(DEFINITION_ID))
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    assertThat(row.getLong("row_count"))
                            .as("the isolated database starts without preinserted definition rows")
                            .isZero();
                    return VertiqueApplicationBootstrap.start(
                            VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app);
                })
                .compose(handle -> {
                    handles.add(handle);
                    List<String> events = observation.events();
                    int metadataObserved = events.indexOf("metadata-observed-before-deployment");
                    int deploymentObserved = events.indexOf("bootstrap-verticle-deployed");
                    assertThat(metadataObserved).isGreaterThanOrEqualTo(0);
                    assertThat(deploymentObserved).isGreaterThan(metadataObserved);
                    assertThat(observation.metadataObservation()).isNotNull();
                    assertThat(observation.metadataObservation().durable())
                            .extracting(record -> record.key().definitionVersion())
                            .containsExactly(1L, 2L);
                    assertThat(observation.metadataObservation().service())
                            .extracting(record -> record.key().definitionVersion())
                            .containsExactly(1L, 2L);
                    assertThat(observation.metadataObservation().compiled())
                            .extracting(ref -> ref.definitionVersion())
                            .containsExactly(1L, 2L);
                    return Future.succeededFuture();
                });

        finish(context, work, handles, pools);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockWaitCases")
    @DisplayName("an existing registry claim waits are bounded at advisory and row locks")
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    @io.vertx.junit5.Timeout(value = 45, timeUnit = TimeUnit.SECONDS)
    void boundsRegistryMutationLockWaits(LockWaitCase lockCase, Vertx vertx, VertxTestContext context) {
        LockWaitResources resources = new LockWaitResources();
        String definitionId = lockCase.definitionId();
        resources.definitionId = definitionId;
        if (lockCase.mutation() == MutationKind.STARTUP) {
            runStartupLockWait(lockCase, vertx, context, resources);
            return;
        }
        if (lockCase.mutation() != MutationKind.CLAIM) {
            runMutationLockWait(lockCase, vertx, context, resources);
            return;
        }
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        WorkflowDefinitionSqlObserver sqlObserver = new WorkflowDefinitionSqlObserver();

        Pool mutationPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observedMutationPool = sqlObserver.observe(mutationPool);
        Pool holderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool contenderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observerPool = ManagedDefinitionDatabase.openPool(vertx, db, 2);
        resources.mutationPool = mutationPool;
        resources.pools.add(mutationPool);
        resources.pools.add(holderPool);
        resources.pools.add(contenderPool);
        resources.pools.add(observerPool);

        AppComponent app = ManagedDefinitionDatabase.app(
                vertx,
                observedMutationPool,
                new JsonObject(),
                new BootObservation(definitionId, 1, "unused", false),
                List::of);

        Future<Void> work = mutationPool
                .preparedQuery("DELETE FROM workflow_definition_activations WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> mutationPool
                        .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                        .execute(Tuple.of(definitionId)))
                .compose(ignored ->
                        VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), r -> app))
                .compose(handle -> {
                    resources.handle = handle;
                    SourceMetadata metadata = new SourceMetadata(
                            "registry-lock-test",
                            "classpath:definitions/it/order-fulfillment.workflow.yaml",
                            "lock-wait",
                            "test-suite",
                            null,
                            Instant.parse("2026-10-18T12:15:00.000001Z"));
                    return app.definitionService()
                            .load(
                                    WorkflowScopes.DEFAULT,
                                    ManagedDefinitionDatabase.yamlFixture(),
                                    DocumentFormat.YAML,
                                    metadata)
                            .map(loaded -> {
                                assertThat(loaded.definitionId()).isEqualTo(definitionId);
                                assertThat(loaded.definitionVersion()).isEqualTo(1L);
                                assertThat(loaded.status())
                                        .isEqualTo(dev.vertique.workflow.definition.service.ActivationStatus.CANDIDATE);
                                return loaded;
                            });
                })
                .compose(ignored -> backendPid(observedMutationPool))
                .compose(writerPid -> acquireLockHolder(lockCase, definitionId, holderPool, key, resources)
                        .compose(ignored -> app.definitionRegistryStore()
                                .find(key)
                                .map(record -> {
                                    assertThat(record).isPresent();
                                    assertThat(record.orElseThrow().status()).isEqualTo(DefinitionStatus.CANDIDATE);
                                    return writerPid;
                                })))
                .compose(writerPid -> {
                    sqlObserver.shortenNextDefinitionLockTimeout();
                    resources.mutation = app.definitionRegistryStore().claim(key);
                    return awaitLockWait(observerPool, vertx, writerPid, lockCase.kind())
                            .compose(wait -> {
                                resources.writerWait = wait;
                                return startQueuedSharedLock(
                                        contenderPool, observerPool, vertx, definitionId, resources);
                            })
                            .compose(ignored -> awaitOperation(resources.mutation, vertx, 35_000))
                            .compose(outcome -> {
                                assertThat(outcome.completed())
                                        .as("claim must finish within the promised 30-second lock bound")
                                        .isTrue();
                                assertThat(outcome.succeeded()).isFalse();
                                assertThat(sqlObserver.shortenedDefinitionLockTimeouts())
                                        .as("the observer may shorten only an issued production lock timeout")
                                        .isEqualTo(1);
                                assertThat(sqlObserver.statements()).anySatisfy(sql -> assertThat(normalized(sql))
                                        .isEqualTo("set local lock_timeout = '30000ms'"));
                                assertMappedLockTimeout(outcome.failure(), definitionId);
                                return assertCandidateWasNotPartiallyClaimed(observedMutationPool, key)
                                        .compose(ignored ->
                                                assertMutationConnectionWasReused(observedMutationPool, writerPid));
                            })
                            .compose(ignored -> {
                                if (resources.queuedSharedLock == null) return Future.succeededFuture();
                                return awaitFuture(resources.queuedSharedLock.acquisition(), vertx, 2_000)
                                        .map(acquired -> {
                                            assertThat(acquired)
                                                    .as(
                                                            "compatible shared work queued behind the writer must progress after its timeout")
                                                    .isTrue();
                                            return (Void) null;
                                        });
                            })
                            .compose(ignored ->
                                    assertLockTimeoutPrecedesWait(sqlObserver.statements(), lockCase.kind()));
                });

        finish(context, work, resources);
    }

    private static Stream<LockWaitCase> lockWaitCases() {
        List<LockWaitCase> cases = new ArrayList<>();
        cases.add(new LockWaitCase("shared advisory holder", WaitKind.SHARED_ADVISORY));
        cases.add(new LockWaitCase("row holder without advisory", WaitKind.ROW_LOCK));
        for (MutationKind mutation : List.of(
                MutationKind.INSERT_CANDIDATE,
                MutationKind.UPSERT_DOCUMENT,
                MutationKind.COMPLETE,
                MutationKind.RELEASE)) {
            for (WaitKind waitKind : WaitKind.values()) {
                cases.add(new LockWaitCase(
                        mutation.displayName() + " behind " + waitKind.displayName(), waitKind, mutation));
            }
        }
        cases.add(new LockWaitCase(
                "startup reconciliation behind shared advisory holder",
                WaitKind.SHARED_ADVISORY,
                MutationKind.STARTUP));
        return cases.stream();
    }

    private static void runMutationLockWait(
            LockWaitCase lockCase, Vertx vertx, VertxTestContext context, LockWaitResources resources) {
        String definitionId = lockCase.definitionId();
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        WorkflowDefinitionSqlObserver sqlObserver = new WorkflowDefinitionSqlObserver();
        Pool mutationPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observedMutationPool = sqlObserver.observe(mutationPool);
        Pool holderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool contenderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observerPool = ManagedDefinitionDatabase.openPool(vertx, db, 2);
        resources.mutationPool = mutationPool;
        resources.pools.add(mutationPool);
        resources.pools.add(holderPool);
        resources.pools.add(contenderPool);
        resources.pools.add(observerPool);

        AppComponent app = ManagedDefinitionDatabase.app(
                vertx,
                observedMutationPool,
                new JsonObject(),
                new BootObservation(definitionId, 1, "unused", false),
                List::of);

        Future<Void> work = deleteDefinitions(mutationPool, definitionId)
                .compose(ignored -> seedMutationState(mutationPool, lockCase))
                .compose(ignored -> readSnapshots(mutationPool, definitionId))
                .compose(before -> backendPid(observedMutationPool).map(pid -> new MutationWaitSetup(before, pid)))
                .compose(setup -> acquireLockHolder(lockCase, definitionId, holderPool, key, resources)
                        .compose(ignored -> {
                            sqlObserver.shortenNextDefinitionLockTimeout();
                            resources.mutation = invokeMutation(app.definitionRegistryStore(), lockCase, definitionId);
                            return awaitLockWait(observerPool, vertx, setup.writerPid(), lockCase.kind())
                                    .compose(wait -> {
                                        resources.writerWait = wait;
                                        return startQueuedSharedLock(
                                                contenderPool, observerPool, vertx, definitionId, resources);
                                    })
                                    .compose(ignoredQueued -> awaitOperation(resources.mutation, vertx, 5_000))
                                    .compose(outcome -> {
                                        assertThat(outcome.completed())
                                                .as("the actual bounded "
                                                        + lockCase.mutation().displayName()
                                                        + " wait completes after the observed PostgreSQL timeout")
                                                .isTrue();
                                        assertThat(outcome.succeeded()).isFalse();
                                        assertThat(sqlObserver.shortenedDefinitionLockTimeouts())
                                                .as("only an actually issued production lock timeout is shortened")
                                                .isEqualTo(1);
                                        assertThat(sqlObserver.statements())
                                                .anySatisfy(sql -> assertThat(normalized(sql))
                                                        .isEqualTo("set local lock_timeout = '30000ms'"));
                                        assertMappedLockTimeout(outcome.failure(), definitionId);
                                        return readSnapshots(mutationPool, definitionId)
                                                .map(after -> {
                                                    assertThat(after)
                                                            .as(
                                                                    "a failed "
                                                                            + lockCase.mutation()
                                                                                    .displayName()
                                                                            + " leaves all definition rows and claim tokens unchanged")
                                                            .isEqualTo(setup.before());
                                                    return (Void) null;
                                                });
                                    })
                                    .compose(ignoredOutcome ->
                                            assertMutationConnectionWasReused(observedMutationPool, setup.writerPid()))
                                    .compose(ignoredReuse ->
                                            awaitFuture(resources.queuedSharedLock.acquisition(), vertx, 2_000))
                                    .map(progressed -> {
                                        assertThat(progressed)
                                                .as(
                                                        "compatible shared work queued behind the writer progresses after timeout")
                                                .isTrue();
                                        return (Void) null;
                                    })
                                    .compose(ignoredProgress ->
                                            assertLockTimeoutPrecedesWait(sqlObserver.statements(), lockCase.kind()));
                        }));

        finish(context, work, resources);
    }

    private static void runStartupLockWait(
            LockWaitCase lockCase, Vertx vertx, VertxTestContext context, LockWaitResources resources) {
        String definitionId = lockCase.definitionId();
        WorkflowDefinitionSqlObserver sqlObserver = new WorkflowDefinitionSqlObserver();
        Pool mutationPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observedMutationPool = sqlObserver.observe(mutationPool);
        Pool holderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool contenderPool = ManagedDefinitionDatabase.openPool(vertx, db, 1);
        Pool observerPool = ManagedDefinitionDatabase.openPool(vertx, db, 2);
        resources.mutationPool = mutationPool;
        resources.pools.add(mutationPool);
        resources.pools.add(holderPool);
        resources.pools.add(contenderPool);
        resources.pools.add(observerPool);

        BootObservation observation = new BootObservation(definitionId, List.of());
        AppComponent app = ManagedDefinitionDatabase.app(
                vertx, observedMutationPool, new JsonObject(), observation, staticDocuments());
        DefinitionKey lockKey = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
        Future<Void> work = deleteDefinitions(mutationPool, definitionId)
                .compose(ignored -> backendPid(observedMutationPool))
                .compose(writerPid -> acquireLockHolder(lockCase, definitionId, holderPool, lockKey, resources)
                        .compose(ignored -> {
                            sqlObserver.shortenNextDefinitionLockTimeout();
                            Future<VertiqueApplicationHandle<AppComponent>> starting =
                                    VertiqueApplicationBootstrap.start(
                                            VertiqueRuntime.of(vertx, new JsonObject()), runtime -> app);
                            resources.mutation = starting;
                            starting.onSuccess(handle -> resources.handle = handle);
                            return awaitLockWait(observerPool, vertx, writerPid, WaitKind.SHARED_ADVISORY)
                                    .compose(wait -> {
                                        resources.writerWait = wait;
                                        return startQueuedSharedLock(
                                                contenderPool, observerPool, vertx, definitionId, resources);
                                    })
                                    .compose(ignoredQueued -> awaitOperation(starting, vertx, 5_000))
                                    .compose(outcome -> {
                                        assertThat(outcome.completed())
                                                .as("managed startup reconciliation aborts after the real lock timeout")
                                                .isTrue();
                                        assertThat(outcome.succeeded()).isFalse();
                                        assertMappedLockTimeoutInCauseChain(outcome.failure(), definitionId);
                                        assertThat(sqlObserver.shortenedDefinitionLockTimeouts())
                                                .isEqualTo(1);
                                        assertThat(sqlObserver.statements())
                                                .anySatisfy(sql -> assertThat(normalized(sql))
                                                        .isEqualTo("set local lock_timeout = '30000ms'"));
                                        assertThat(observation.events()).doesNotContain("bootstrap-verticle-deployed");
                                        return readSnapshots(mutationPool, definitionId)
                                                .map(rows -> {
                                                    assertThat(rows)
                                                            .as("failed reconciliation commits no static metadata")
                                                            .isEmpty();
                                                    return (Void) null;
                                                });
                                    })
                                    .compose(ignoredOutcome ->
                                            assertMutationConnectionWasReused(observedMutationPool, writerPid))
                                    .compose(ignoredReuse ->
                                            awaitFuture(resources.queuedSharedLock.acquisition(), vertx, 2_000))
                                    .map(progressed -> {
                                        assertThat(progressed)
                                                .as("compatible queued shared work progresses after startup abort")
                                                .isTrue();
                                        return (Void) null;
                                    })
                                    .compose(ignoredProgress -> assertLockTimeoutPrecedesWait(
                                            sqlObserver.statements(), WaitKind.SHARED_ADVISORY));
                        }));

        finish(context, work, resources);
    }

    private static Future<Void> seedMutationState(Pool pool, LockWaitCase lockCase) {
        String definitionId = lockCase.definitionId();
        List<DefinitionSeed> seeds =
                switch (lockCase.mutation()) {
                    case INSERT_CANDIDATE, UPSERT_DOCUMENT ->
                        List.of(definitionSeed(
                                WorkflowScopes.DEFAULT,
                                definitionId,
                                1,
                                DefinitionKind.DOCUMENT,
                                DefinitionStatus.RETIRED,
                                null));
                    case COMPLETE, RELEASE ->
                        List.of(definitionSeed(
                                WorkflowScopes.DEFAULT,
                                definitionId,
                                1,
                                DefinitionKind.DOCUMENT,
                                DefinitionStatus.PUBLISHING,
                                UUID.fromString("30000000-0000-0000-0000-000000000001")));
                    case CLAIM, STARTUP ->
                        throw new IllegalArgumentException("the mutation state is not directly seeded");
                };
        return insertDefinitionSeeds(pool, seeds);
    }

    private static Future<?> invokeMutation(
            WorkflowDefinitionRegistryStore store, LockWaitCase lockCase, String definitionId) {
        long mutationVersion = lockCase.mutation() == MutationKind.INSERT_CANDIDATE
                        || lockCase.mutation() == MutationKind.UPSERT_DOCUMENT
                ? 2
                : 1;
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, mutationVersion);
        return switch (lockCase.mutation()) {
            case INSERT_CANDIDATE ->
                store.insertCandidate(mutationRecord(key, DefinitionStatus.CANDIDATE), mutationDocument(key));
            case UPSERT_DOCUMENT ->
                store.upsertDocument(mutationRecord(key, DefinitionStatus.AVAILABLE), mutationDocument(key));
            case CLAIM -> store.claim(key);
            case COMPLETE ->
                store.complete(new ActivationClaim(key, UUID.fromString("30000000-0000-0000-0000-000000000001")));
            case RELEASE ->
                store.release(new ActivationClaim(key, UUID.fromString("30000000-0000-0000-0000-000000000001")));
            case STARTUP -> throw new IllegalArgumentException("startup is exercised through the managed application");
        };
    }

    private static Future<Void> acquireLockHolder(
            LockWaitCase lockCase,
            String definitionId,
            Pool holderPool,
            DefinitionKey key,
            LockWaitResources resources) {
        if (lockCase.kind() == WaitKind.SHARED_ADVISORY) {
            return beginTransaction(holderPool).compose(connection -> {
                resources.primaryHolder = connection;
                return connection
                        .preparedQuery("SELECT pg_advisory_xact_lock_shared($1)")
                        .execute(Tuple.of(advisoryLockKey(definitionId)))
                        .mapEmpty();
            });
        }
        return beginTransaction(holderPool).compose(connection -> {
            resources.primaryHolder = connection;
            return connection
                    .preparedQuery("""
                                    SELECT definition_id FROM workflow_definitions
                                    WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                                    FOR UPDATE
                                    """)
                    .execute(Tuple.of(key.definitionId(), key.definitionVersion()))
                    .map(rows -> {
                        assertThat(rows.rowCount()).isEqualTo(1);
                        return (Void) null;
                    });
        });
    }

    private static Future<Void> startQueuedSharedLock(
            Pool contenderPool, Pool observerPool, Vertx vertx, String definitionId, LockWaitResources resources) {
        return beginTransaction(contenderPool).compose(connection -> {
            resources.queuedSharedConnection = connection;
            return backendPid(connection).compose(pid -> {
                resources.queuedSharedLock = new QueuedSharedLock(connection
                        .preparedQuery("SELECT pg_advisory_xact_lock_shared($1)")
                        .execute(Tuple.of(advisoryLockKey(definitionId)))
                        .mapEmpty());
                return awaitLockWait(observerPool, vertx, pid, WaitKind.SHARED_ADVISORY)
                        .mapEmpty();
            });
        });
    }

    private static Future<SqlConnection> beginTransaction(Pool pool) {
        return pool.getConnection().compose(connection -> connection
                .query("BEGIN")
                .execute()
                .map(connection)
                .recover(failure -> connection.close().compose(ignored -> Future.failedFuture(failure))));
    }

    private static Future<Integer> backendPid(Pool pool) {
        return pool.query("SELECT pg_backend_pid() AS backend_pid")
                .execute()
                .map(rows -> rows.iterator().next().getInteger("backend_pid"));
    }

    private static Future<Integer> backendPid(SqlConnection connection) {
        return connection
                .query("SELECT pg_backend_pid() AS backend_pid")
                .execute()
                .map(rows -> rows.iterator().next().getInteger("backend_pid"));
    }

    private static Future<ObservedLock> awaitLockWait(Pool observerPool, Vertx vertx, int pid, WaitKind expected) {
        Promise<ObservedLock> result = Promise.promise();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        pollLockWait(observerPool, vertx, pid, expected, deadline, result);
        return result.future();
    }

    private static void pollLockWait(
            Pool observerPool, Vertx vertx, int pid, WaitKind expected, long deadline, Promise<ObservedLock> result) {
        observerPool.preparedQuery("""
                        SELECT l.locktype, a.wait_event_type, a.wait_event
                        FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                        WHERE l.pid = $1 AND NOT l.granted
                        """).execute(Tuple.of(pid)).onComplete(query -> {
            if (query.failed()) {
                result.tryFail(query.cause());
                return;
            }
            for (Row row : query.result()) {
                String lockType = row.getString("locktype");
                if (expected.matches(lockType)) {
                    result.tryComplete(
                            new ObservedLock(lockType, row.getString("wait_event_type"), row.getString("wait_event")));
                    return;
                }
            }
            if (System.nanoTime() >= deadline) {
                result.tryFail("mutation backend did not enter the expected PostgreSQL lock wait: " + expected);
            } else {
                vertx.setTimer(20, ignored -> pollLockWait(observerPool, vertx, pid, expected, deadline, result));
            }
        });
    }

    private static Future<OperationObservation> awaitOperation(Future<?> operation, Vertx vertx, long timeoutMs) {
        Promise<OperationObservation> observed = Promise.promise();
        long timerId = vertx.setTimer(
                timeoutMs, ignored -> observed.tryComplete(new OperationObservation(false, false, null)));
        operation.onComplete(result -> {
            vertx.cancelTimer(timerId);
            observed.tryComplete(new OperationObservation(true, result.succeeded(), result.cause()));
        });
        return observed.future();
    }

    private static Future<Boolean> awaitFuture(Future<?> future, Vertx vertx, long timeoutMs) {
        Promise<Boolean> observed = Promise.promise();
        long timerId = vertx.setTimer(timeoutMs, ignored -> observed.tryComplete(false));
        future.onComplete(result -> {
            vertx.cancelTimer(timerId);
            observed.tryComplete(result.succeeded());
        });
        return observed.future();
    }

    private static Future<Void> withCleanup(Future<Void> work, Supplier<Future<Void>> cleanup) {
        return work.transform(result -> cleanup.get().transform(cleaned -> {
            if (result.failed()) {
                if (cleaned.failed()) result.cause().addSuppressed(cleaned.cause());
                return Future.failedFuture(result.cause());
            }
            if (cleaned.failed()) return Future.failedFuture(cleaned.cause());
            return Future.succeededFuture();
        }));
    }

    private static DefinitionSnapshot snapshot(List<DefinitionSnapshot> rows, long version) {
        return rows.stream()
                .filter(row -> row.definitionVersion() == version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing definition version " + version));
    }

    private static Future<Void> insertDefinitionSeeds(Pool pool, List<DefinitionSeed> seeds) {
        Future<Void> inserted = Future.succeededFuture();
        for (DefinitionSeed seed : seeds) {
            inserted = inserted.compose(ignored -> pool.preparedQuery("""
                            INSERT INTO workflow_definitions (
                                scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                                document, document_format, document_digest, source_type, source_uri,
                                source_revision, source_author, source_upload_id, loaded_at, created_at,
                                status_changed_at, publish_token)
                            VALUES ($1, $2, $3, $4, 'example.Contract', 'example.State', $5, $6, $7, $8,
                                    CASE WHEN $4 = 'DOCUMENT'
                                         THEN encode(sha256(convert_to($7::text, 'UTF8')), 'hex') END,
                                    $9, $10, $11, $12, $13, $14, $15, $16, $17)
                            """)
                    .execute(Tuple.tuple()
                            .addString(seed.scopeKey())
                            .addString(seed.definitionId())
                            .addLong(seed.definitionVersion())
                            .addString(seed.kind().name())
                            .addString(seed.planHash())
                            .addString(seed.status().name())
                            .addString(seed.document())
                            .addString(seed.documentFormat())
                            .addString("author-test")
                            .addString("test://workflow-definition")
                            .addString("matrix-r1")
                            .addString("test-suite")
                            .addString(null)
                            .addValue(seed.loadedAt().atOffset(ZoneOffset.UTC))
                            .addValue(seed.createdAt().atOffset(ZoneOffset.UTC))
                            .addValue(seed.statusChangedAt().atOffset(ZoneOffset.UTC))
                            .addUUID(seed.publishToken()))
                    .mapEmpty());
        }
        return inserted;
    }

    private static DefinitionSeed definitionSeed(
            String scopeKey,
            String definitionId,
            long version,
            DefinitionKind kind,
            DefinitionStatus status,
            UUID publishToken) {
        Instant base = Instant.parse("2026-10-18T10:00:00Z").plusSeconds(version);
        return new DefinitionSeed(
                scopeKey,
                definitionId,
                version,
                kind,
                String.valueOf((char) ('a' + (int) (version % 6))).repeat(64),
                status,
                kind == DefinitionKind.DOCUMENT ? "workflow: {}\n" : null,
                kind == DefinitionKind.DOCUMENT ? "YAML" : null,
                base,
                base.plusSeconds(1),
                base.plusSeconds(2),
                publishToken);
    }

    private static Future<List<DefinitionSnapshot>> readSnapshots(Pool pool, String definitionId) {
        return pool.preparedQuery("""
                        SELECT scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                            document, document_format, source_type, source_uri, source_revision, source_author,
                            source_upload_id, loaded_at, created_at, status_changed_at, publish_token
                        FROM workflow_definitions
                        WHERE definition_id = $1
                        ORDER BY scope_key NULLS FIRST, definition_version
                        """).execute(Tuple.of(definitionId)).map(rows -> {
            List<DefinitionSnapshot> snapshots = new ArrayList<>();
            for (Row row : rows) {
                snapshots.add(new DefinitionSnapshot(
                        row.getString("scope_key"),
                        row.getString("definition_id"),
                        row.getLong("definition_version"),
                        row.getString("kind"),
                        row.getString("plan_hash"),
                        row.getString("status"),
                        row.getString("document"),
                        row.getString("document_format"),
                        row.getString("source_type"),
                        row.getString("source_uri"),
                        row.getString("source_revision"),
                        row.getString("source_author"),
                        row.getString("source_upload_id"),
                        row.getOffsetDateTime("loaded_at"),
                        row.getOffsetDateTime("created_at"),
                        row.getOffsetDateTime("status_changed_at"),
                        row.getUUID("publish_token")));
            }
            return List.copyOf(snapshots);
        });
    }

    private static Future<Void> deleteDefinitions(Pool pool, String... definitionIds) {
        Future<Void> deleted = Future.succeededFuture();
        for (String definitionId : definitionIds) {
            deleted = deleted.compose(ignored -> pool.preparedQuery(
                            "DELETE FROM workflow_definition_activations WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId))
                    .compose(cleared -> pool.preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                            .execute(Tuple.of(definitionId)))
                    .mapEmpty());
        }
        return deleted;
    }

    private static WorkflowDefinitionRecord mutationRecord(DefinitionKey key, DefinitionStatus status) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                "c".repeat(64),
                status,
                new DefinitionProvenance(
                        "author-test", "test://workflow-definition", "mutation-r1", "test-suite", null, now),
                "YAML",
                now,
                now,
                Map.of(),
                null);
    }

    private static WorkflowDefinitionDocument mutationDocument(DefinitionKey key) {
        return new WorkflowDefinitionDocument(key, "workflow: {}\n", "YAML");
    }

    private static void assertMappedLockTimeoutInCauseChain(Throwable failure, String definitionId) {
        WorkflowPersistenceException persistenceFailure = causeOf(failure, WorkflowPersistenceException.class);
        assertThat(persistenceFailure)
                .as("startup propagates the mapped persistence failure in its cause chain")
                .isNotNull();
        assertThat(persistenceFailure.getMessage()).contains(definitionId);
        assertThat(persistenceFailure.retryable()).isTrue();
        PessimisticLockingFailureException lockingFailure =
                causeOf(persistenceFailure.getCause(), PessimisticLockingFailureException.class);
        assertThat(lockingFailure).isNotNull();
        assertThat(lockingFailure.sqlState()).isEqualTo("55P03");
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
    }

    private static boolean causeContainsMessage(Throwable failure, String marker) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) return true;
        }
        return false;
    }

    private static void assertMappedLockTimeout(Throwable failure, String definitionId) {
        assertThat(failure).isInstanceOf(WorkflowPersistenceException.class);
        WorkflowPersistenceException persistenceFailure = (WorkflowPersistenceException) failure;
        assertThat(persistenceFailure.retryable()).isTrue();
        assertThat(persistenceFailure.getMessage()).contains(definitionId);
        assertThat(persistenceFailure.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
        PessimisticLockingFailureException lockingFailure =
                (PessimisticLockingFailureException) persistenceFailure.getCause();
        assertThat(lockingFailure.sqlState()).isEqualTo("55P03");
    }

    private static Future<Void> assertCandidateWasNotPartiallyClaimed(Pool pool, DefinitionKey key) {
        return pool.preparedQuery("""
                        SELECT status, publish_token FROM workflow_definitions
                        WHERE scope_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3
                        """)
                .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion()))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    assertThat(row.getString("status")).isEqualTo("CANDIDATE");
                    assertThat(row.getValue("publish_token")).isNull();
                    return (Void) null;
                });
    }

    private static Future<Void> assertMutationConnectionWasReused(Pool pool, int expectedPid) {
        return pool.query("SELECT pg_backend_pid() AS backend_pid, current_setting('lock_timeout') AS lock_timeout")
                .execute()
                .map(rows -> {
                    Row row = rows.iterator().next();
                    assertThat(row.getInteger("backend_pid")).isEqualTo(expectedPid);
                    assertThat(row.getString("lock_timeout")).isEqualTo("0");
                    assertThat(row.getInteger("backend_pid")).isPositive();
                    return (Void) null;
                })
                .compose(ignored -> pool.query("SELECT 1").execute().mapEmpty());
    }

    private static Future<Void> assertLockTimeoutPrecedesWait(List<String> statements, WaitKind kind) {
        List<String> normalized = statements.stream()
                .map(DefinitionRegistryBootstrapIT::normalized)
                .toList();
        int timeout = normalized.indexOf("set local lock_timeout = '30000ms'");
        assertThat(timeout).isGreaterThanOrEqualTo(0);
        int wait = -1;
        for (int i = timeout + 1; i < normalized.size(); i++) {
            if ((kind == WaitKind.SHARED_ADVISORY && normalized.get(i).contains("pg_advisory_xact_lock($1)"))
                    || (kind == WaitKind.ROW_LOCK && normalized.get(i).contains("for update"))) {
                wait = i;
                break;
            }
        }
        assertThat(wait)
                .as("the actual production timeout precedes the lock acquisition")
                .isGreaterThan(timeout);
        return Future.succeededFuture();
    }

    private static String normalized(String sql) {
        return sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static long advisoryLockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void finish(VertxTestContext context, Future<Void> work, LockWaitResources resources) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        Future<Void> drained = work.eventually(() -> resources.releaseAndDrain(cleanupFailures));
        drained.onComplete(result -> {
            Future<Void> cleanup = Future.succeededFuture();
            if (resources.handle != null) {
                cleanup = cleanup.compose(ignored -> quietly(resources.handle.shutdown(), cleanupFailures));
            }
            if (resources.mutationPool != null) {
                Future<Void> deleteOwnedRow = resources
                        .mutationPool
                        .preparedQuery("DELETE FROM workflow_definition_activations WHERE definition_id = $1")
                        .execute(Tuple.of(resources.definitionId))
                        .compose(ignored -> resources
                                .mutationPool
                                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                                .execute(Tuple.of(resources.definitionId)))
                        .map(ignored -> (Void) null);
                cleanup = cleanup.compose(ignored -> quietly(deleteOwnedRow, cleanupFailures));
            }
            for (int i = resources.pools.size() - 1; i >= 0; i--) {
                Pool ownedPool = resources.pools.get(i);
                cleanup = cleanup.compose(ignored -> quietly(ownedPool.close(), cleanupFailures));
            }
            cleanup.onComplete(cleaned -> {
                if (cleaned.failed()) cleanupFailures.add(cleaned.cause());
                if (result.failed()) {
                    cleanupFailures.forEach(result.cause()::addSuppressed);
                    context.failNow(result.cause());
                } else if (!cleanupFailures.isEmpty()) {
                    Throwable failure = cleanupFailures.get(0);
                    cleanupFailures.stream().skip(1).forEach(failure::addSuppressed);
                    context.failNow(failure);
                } else {
                    context.completeNow();
                }
            });
        });
    }

    private static Future<Void> quietly(Future<Void> action, List<Throwable> failures) {
        return action.transform(result -> {
            if (result.failed()) failures.add(result.cause());
            return Future.succeededFuture((Void) null);
        });
    }

    private enum WaitKind {
        SHARED_ADVISORY("shared advisory holder"),
        ROW_LOCK("row holder without advisory");

        private final String displayName;

        WaitKind(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }

        private boolean matches(String lockType) {
            return this == SHARED_ADVISORY
                    ? "advisory".equals(lockType)
                    : "transactionid".equals(lockType) || "tuple".equals(lockType);
        }
    }

    private enum MutationKind {
        CLAIM("claim"),
        INSERT_CANDIDATE("insertCandidate"),
        UPSERT_DOCUMENT("upsertDocument"),
        COMPLETE("complete"),
        RELEASE("release"),
        STARTUP("startup reconciliation");

        private final String displayName;

        MutationKind(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }

    private record LockWaitCase(String name, WaitKind kind, MutationKind mutation) {

        private LockWaitCase(String name, WaitKind kind) {
            this(name, kind, MutationKind.CLAIM);
        }

        private String definitionId() {
            return mutation == MutationKind.CLAIM || mutation == MutationKind.STARTUP
                    ? DEFINITION_ID
                    : "lock-" + mutation.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record MutationWaitSetup(List<DefinitionSnapshot> before, int writerPid) {}

    private record DefinitionSeed(
            String scopeKey,
            String definitionId,
            long definitionVersion,
            DefinitionKind kind,
            String planHash,
            DefinitionStatus status,
            String document,
            String documentFormat,
            Instant loadedAt,
            Instant createdAt,
            Instant statusChangedAt,
            UUID publishToken) {}

    private record DefinitionSnapshot(
            String scopeKey,
            String definitionId,
            long definitionVersion,
            String kind,
            String planHash,
            String status,
            String document,
            String documentFormat,
            String sourceType,
            String sourceUri,
            String sourceRevision,
            String sourceAuthor,
            String sourceUploadId,
            OffsetDateTime loadedAt,
            OffsetDateTime createdAt,
            OffsetDateTime statusChangedAt,
            UUID publishToken) {}

    private record ObservedLock(String lockType, String waitEventType, String waitEvent) {}

    private record OperationObservation(boolean completed, boolean succeeded, Throwable failure) {}

    private static final class QueuedSharedLock {

        private final Future<Void> acquisition;

        private QueuedSharedLock(Future<Void> acquisition) {
            this.acquisition = acquisition;
        }

        Future<Void> acquisition() {
            return acquisition;
        }
    }

    private static final class LockWaitResources {

        private final List<Pool> pools = new ArrayList<>();
        private String definitionId = DEFINITION_ID;
        private VertiqueApplicationHandle<?> handle;
        private Pool mutationPool;
        private SqlConnection primaryHolder;
        private SqlConnection queuedSharedConnection;
        private QueuedSharedLock queuedSharedLock;
        private Future<?> mutation;
        private ObservedLock writerWait;

        private Future<Void> releaseAndDrain(List<Throwable> cleanupFailures) {
            Future<Void> release = Future.succeededFuture();
            if (primaryHolder != null) {
                release = release.compose(ignored -> rollback(primaryHolder, cleanupFailures));
            }
            if (queuedSharedConnection != null && queuedSharedLock != null) {
                release = release.compose(
                        ignored -> quietly(queuedSharedLock.acquisition().mapEmpty(), cleanupFailures)
                                .compose(ignoredAcquired -> rollback(queuedSharedConnection, cleanupFailures)));
            } else if (queuedSharedConnection != null) {
                release = release.compose(ignored -> rollback(queuedSharedConnection, cleanupFailures));
            }
            if (mutation != null) {
                release = release.compose(
                        ignored -> mutation.transform(ignoredResult -> Future.succeededFuture((Void) null)));
            }
            return release;
        }

        private static Future<Void> rollback(SqlConnection connection, List<Throwable> cleanupFailures) {
            return quietly(connection.query("ROLLBACK").execute().mapEmpty(), cleanupFailures)
                    .compose(ignored -> quietly(connection.close(), cleanupFailures));
        }
    }

    private static WorkflowDefinitionSource staticDocuments() {
        return () -> List.of(
                new DefinitionResource(ManagedDefinitionDatabase.yamlFixture(), DocumentFormat.YAML, YAML_METADATA),
                new DefinitionResource(ManagedDefinitionDatabase.jsonFixture(), DocumentFormat.JSON, JSON_METADATA));
    }

    private static void finish(
            VertxTestContext context, Future<Void> work, List<VertiqueApplicationHandle<?>> handles, List<Pool> pools) {
        work.onComplete(result -> {
            Future<Void> cleanup = Future.succeededFuture();
            List<Throwable> cleanupFailures = new ArrayList<>();
            for (int i = handles.size() - 1; i >= 0; i--) {
                VertiqueApplicationHandle<?> handle = handles.get(i);
                cleanup = cleanup.compose(ignored -> handle.shutdown().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
            }
            for (int i = pools.size() - 1; i >= 0; i--) {
                Pool ownedPool = pools.get(i);
                cleanup = cleanup.compose(ignored -> ownedPool.close().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
            }
            cleanup.onComplete(cleaned -> {
                if (cleaned.failed()) cleanupFailures.add(cleaned.cause());
                if (result.failed()) {
                    cleanupFailures.forEach(result.cause()::addSuppressed);
                    context.failNow(result.cause());
                } else if (!cleanupFailures.isEmpty()) {
                    Throwable failure = cleanupFailures.get(0);
                    cleanupFailures.stream().skip(1).forEach(failure::addSuppressed);
                    context.failNow(failure);
                } else {
                    context.completeNow();
                }
            });
        });
    }
}
