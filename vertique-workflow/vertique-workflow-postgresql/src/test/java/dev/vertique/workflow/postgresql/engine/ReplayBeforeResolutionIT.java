// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ACME;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.DEFAULT;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ID;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.PURCHASE_CONTRACT;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.await;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.failureOf;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.instanceRows;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.startDedupRows;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Harness;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Purchase;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.PurchaseContract;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.StartPurchase;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that a start looks for the instance of its idempotency key before it resolves any
 * version: a replay returns the original instance whatever the registry looks like by then, a replay that names
 * another pin conflicts, and concurrent first starts of one key create one instance.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ReplayBeforeResolutionIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_replay_before_resolution_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    @BeforeAll
    static void setUp(Vertx vertx) {
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
    }

    @BeforeEach
    void truncateTables() throws Exception {
        ScopedStartFixture.truncate(pool);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a replay returns the original instance after current moves and its version is deprecated")
    void replayReturnsOriginalAfterCurrentMovesAndVersionDeprecated() throws Exception {
        // Given: version 1 registered, instance A started with K1 and no version, instance B with K2 and version 1
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done-1"));
        WorkflowInstanceId a = startWith(harness, "K1", null);
        WorkflowInstanceId b = startWith(harness, "K2", 1L);
        assertThat(b).isNotEqualTo(a);

        // When: version 2 is registered so that current moves, and version 1 is deprecated
        harness.registry().register(new Purchase(ID, 2L, "done-2"));
        deprecate(harness, 1L);

        // Then: K1 replays to A with no version and with version 1, and K2 replays to B with version 1
        assertThat(startWith(harness, "K1", null)).isEqualTo(a);
        assertThat(startWith(harness, "K1", 1L)).isEqualTo(a);
        assertThat(startWith(harness, "K2", 1L)).isEqualTo(b);

        // and: no instance row was added, and every pin stayed on version 1
        assertThat(instanceRows(pool)).isEqualTo(2L);
        assertThat(await(harness.engine().query(a)).instance().definitionVersion())
                .isEqualTo(1L);
        assertThat(await(harness.engine().query(b)).instance().definitionVersion())
                .isEqualTo(1L);

        // and: a new key naming deprecated version 1 is refused as deprecated
        Throwable refused = failureOf(() -> harness.engine().start(command("K3", 1L, null, null)));
        assertThat(refused).isInstanceOf(WorkflowDefinitionLifecycleException.class);
        assertThat(((WorkflowDefinitionLifecycleException) refused).reason())
                .isEqualTo(WorkflowDefinitionLifecycleException.Reason.DEPRECATED);
        assertThat(instanceRows(pool)).isEqualTo(2L);
    }

    @Test
    @DisplayName("an explicit-version replay naming another pin conflicts")
    void explicitReplayNamingAnotherPinConflicts() throws Exception {
        // Given: version 1 in the default scope and in acme, version 2 in the default scope, and an instance
        // started in acme with K and version 1, pinned to the acme definition
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done-default"));
        harness.registry().register(ACME, new Purchase(ID, 1L, "done-acme"));
        harness.registry().register(new Purchase(ID, 2L, "done-default-2"));
        WorkflowInstanceId original = await(harness.engine().start(command("K", 1L, ACME, null)));
        await(harness.engine().executionProbe().awaitIdle(original, IDLE_TIMEOUT));

        // When: K replays in acme with version 2, with version 1 of the default scope, and with version 1 of acme
        Throwable otherVersion = failureOf(() -> harness.engine().start(command("K", 2L, ACME, null)));
        Throwable otherScope = failureOf(() -> harness.engine().start(command("K", 1L, ACME, DEFAULT)));
        WorkflowInstanceId samePin = await(harness.engine().start(command("K", 1L, ACME, ACME)));
        await(harness.engine().executionProbe().awaitIdle(samePin, IDLE_TIMEOUT));

        // Then: the first two conflict as a start, and the third returns the original instance
        assertStartConflict(otherVersion, "K");
        assertStartConflict(otherScope, "K");
        assertThat(samePin).isEqualTo(original);
        assertThat(instanceRows(pool)).isEqualTo(1L);

        // and: the original is still pinned to the acme definition
        assertThat(await(harness.engine().query(original)).instance().definitionScopeKey())
                .isEqualTo(ACME);
    }

    @Test
    @DisplayName("a replay through a client names its pin the same way")
    void explicitReplayThroughClientNamesItsPin() throws Exception {
        // Given: version 1 in the default scope and in acme, and an instance started in acme with K and version 1
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done-default"));
        harness.registry().register(ACME, new Purchase(ID, 1L, "done-acme"));
        PurchaseContract sameVersion = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(ACME)
                .version(1)
                .create();
        PurchaseContract defaultVersion = harness.factory()
                .client(PurchaseContract.class, ID)
                .scope(ACME)
                .version(DEFAULT, 1)
                .create();
        WorkflowInstanceId original = await(sameVersion.start("K", null, new StartPurchase("a")));
        await(harness.engine().executionProbe().awaitIdle(original, IDLE_TIMEOUT));

        // When: the replay names version 1 of acme through a client, and version 1 of the default scope
        WorkflowInstanceId replayed = await(sameVersion.start("K", null, new StartPurchase("a")));
        await(harness.engine().executionProbe().awaitIdle(replayed, IDLE_TIMEOUT));
        Throwable conflict = failureOf(() -> defaultVersion.start("K", null, new StartPurchase("a")));

        // Then: the first returns the original, and the second conflicts as a start
        assertThat(replayed).isEqualTo(original);
        assertStartConflict(conflict, "K");
        assertThat(instanceRows(pool)).isEqualTo(1L);
    }

    @Test
    @DisplayName("concurrent first starts of one key create one instance")
    void concurrentFirstStartsOfOneKeyCreateOneInstance() throws Exception {
        // Given: start A runs inside a transaction the test holds open, so its start dedup row is claimed and not
        // committed
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));
        StartCommand command = command("K-race", null, ACME, null);
        try (HeldStart held = HeldStart.begin(pool)) {
            WorkflowInstanceId a = await(harness.engine().start(command, held.connection()));

            // When: start B with the same scope, definition id and key runs on another connection; its replay
            // read misses the uncommitted row, so its claim waits on A's row
            var b = harness.engine().start(command);
            awaitBlockedClaim();
            assertThat(b.isComplete()).as("B must wait for A").isFalse();

            // and: A commits
            held.commit();

            // Then: B returns A's instance id
            assertThat(await(b)).isEqualTo(a);
            await(harness.engine().executionProbe().awaitIdle(a, IDLE_TIMEOUT));

            // and: exactly one instance row and one start dedup row exist
            assertThat(instanceRows(pool)).isEqualTo(1L);
            assertThat(startDedupRows(pool)).isEqualTo(1L);
        }
    }

    // --- Helpers ---

    private static StartCommand command(String idempotencyKey, Long version, String startScope, String targetScope) {
        return new StartCommand(
                ID,
                PURCHASE_CONTRACT,
                new StartPurchase("p"),
                idempotencyKey,
                null,
                null,
                version,
                startScope,
                targetScope);
    }

    /** Starts in the default scope with the key, naming the version when it is given. */
    private static WorkflowInstanceId startWith(Harness harness, String idempotencyKey, Long version) throws Exception {
        WorkflowInstanceId started = await(harness.engine().start(command(idempotencyKey, version, null, null)));
        await(harness.engine().executionProbe().awaitIdle(started, IDLE_TIMEOUT));
        return started;
    }

    private static void deprecate(Harness harness, long version) {
        harness.registry().markDeprecated(new DefinitionKey(DEFAULT, ID, version));
    }

    private static void assertStartConflict(Throwable failure, String idempotencyKey) {
        assertThat(failure).isInstanceOf(WorkflowIdempotencyConflictException.class);
        WorkflowIdempotencyConflictException conflict = (WorkflowIdempotencyConflictException) failure;
        assertThat(conflict.kind()).isEqualTo("start");
        assertThat(conflict.idempotencyKey()).isEqualTo(idempotencyKey);
    }

    /** Waits until some other statement waits for a lock, observed through the server, within a deadline. */
    private static void awaitBlockedClaim() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            long waiting = await(pool.query("SELECT count(*) AS c FROM pg_stat_activity"
                            + " WHERE datname = current_database() AND wait_event_type = 'Lock'")
                    .execute()
                    .map(rows -> rows.iterator().next().getLong("c")));
            if (waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("start B never waited for the lock held by start A");
    }

    /** A connection with an open transaction that the test commits or rolls back; closing it rolls back. */
    private static final class HeldStart implements AutoCloseable {
        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean finished;

        private HeldStart(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        static HeldStart begin(Pool pool) throws Exception {
            SqlConnection connection = await(pool.getConnection());
            try {
                return new HeldStart(connection, await(connection.begin()));
            } catch (Exception | Error e) {
                await(connection.close());
                throw e;
            }
        }

        SqlConnection connection() {
            return connection;
        }

        void commit() throws Exception {
            finished = true;
            await(transaction.commit());
        }

        @Override
        public void close() throws Exception {
            try {
                if (!finished) {
                    finished = true;
                    await(transaction.rollback());
                }
            } finally {
                await(connection.close());
            }
        }
    }
}
