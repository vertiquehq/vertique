// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import java.time.Duration;
import java.util.UUID;

/**
 * Lets tests of other packages hold an instance's execution lock and produce instances whose start was dead-lettered by
 * a crashing executor, without exposing the package-private fixtures of this package.
 *
 * <p>One executor runs with a lease of five seconds and one attempt per command. Its first segment of a chosen start is
 * abandoned as a crash would abandon it, and the start is dead-lettered when its lease has ended, which fails the
 * instance without state.
 */
public final class RetentionTestSupport implements AutoCloseable {

    private static final Duration DEAD_LETTERED = Duration.ofSeconds(40);

    private final ExecutorFixture fixture;
    private final CommandFaults faults = new CommandFaults();
    private final Node node;

    private RetentionTestSupport(DbPoolConfig db) {
        this.fixture = ExecutorFixture.open(db);
        this.node = fixture.node("retention")
                .leaseSeconds(5)
                .maxAttempts(1)
                .commands(faults.decorator())
                .build();
    }

    /**
     * Opens the support over the migrated database and empties every workflow table.
     *
     * @param db the database under test
     * @return the support
     */
    public static RetentionTestSupport open(DbPoolConfig db) {
        return new RetentionTestSupport(db);
    }

    /**
     * The two integers of the instance's execution lock.
     *
     * @param instance the instance
     * @return the lock key
     */
    public static int[] lockKeyOf(UUID instance) {
        return ExecutionLock.keyOf(new WorkflowInstanceId(instance));
    }

    /**
     * Records a start of the skeleton workflow and runs the executor until the start is dead-lettered and its instance
     * failed without state. The executor is stopped afterwards.
     *
     * @param ref the label of the instance
     * @return the instance
     */
    public UUID deadLetteredStart(String ref) {
        WorkflowInstanceId id = node.record(startOf(ref));
        faults.crashEverySegmentOf(id);
        node.startExecutor();
        try {
            awaitCondition("the start to be dead-lettered", DEAD_LETTERED, () -> fixture.commandOf(id)
                    .state()
                    .equals("DEAD"));
            node.awaitIdle(id);
        } finally {
            faults.crashSegmentsOf(id, 0);
            node.stopExecutor();
        }
        return id.value();
    }

    /**
     * Records a retry of the instance in a transaction of its own and commits it. The executor stays stopped.
     *
     * @param instance the instance
     */
    public void recordRetry(UUID instance) {
        WorkflowInstanceId id = new WorkflowInstanceId(instance);
        await(node.pool.withTransaction(tx -> node.engine.retry(id, tx)));
    }

    /** Starts the executor and waits until it is deployed. */
    public void startExecutor() {
        node.startExecutor();
    }

    /**
     * Waits until the instance has no command in flight and none an executor could claim.
     *
     * @param instance the instance
     */
    public void awaitIdle(UUID instance) {
        node.awaitIdle(new WorkflowInstanceId(instance));
    }

    /** Stops the executor and waits until its in-flight segments ended. */
    public void stopExecutor() {
        node.stopExecutor();
    }

    @Override
    public void close() {
        fixture.close();
    }
}
