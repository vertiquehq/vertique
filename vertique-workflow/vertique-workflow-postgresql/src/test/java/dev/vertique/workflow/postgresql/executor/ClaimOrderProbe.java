// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.IsolationLevel;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Observes every successful claim of the executors it is installed on, at the moment the claim returns: which command
 * was claimed, and whether an earlier command of the same instance was still unsettled when it was. It also holds the
 * start of each segment back, so that the window between a command's claim and its segment taking the instance's
 * execution lock is wide enough for an executor that is allowed to claim past it to do so.
 */
final class ClaimOrderProbe {

    private static final String EARLIER_UNSETTLED = "SELECT count(*)::int AS earlier FROM workflow_commands b"
            + " JOIN workflow_commands c ON c.id = $1"
            + " WHERE b.workflow_id = c.workflow_id AND b.seq < c.seq AND b.state IN ('PENDING','DEFERRED','CLAIMED')";

    private final Pool observer;
    private final Vertx vertx;
    private final long segmentDelayMs;
    private final List<Long> claims = new CopyOnWriteArrayList<>();
    private final List<Long> claimedPastAnUnsettledEarlierCommand = new CopyOnWriteArrayList<>();

    /**
     * Creates a probe.
     *
     * @param observer       a pool of the test, to read the commands with
     * @param vertx          the Vert.x instance that times the segment delay
     * @param segmentDelayMs how long each segment is held back before its transaction begins
     */
    ClaimOrderProbe(Pool observer, Vertx vertx, long segmentDelayMs) {
        this.observer = observer;
        this.vertx = vertx;
        this.segmentDelayMs = segmentDelayMs;
    }

    /** The ids of the commands claimed, in the order the claims returned; a re-claim appears again. */
    List<Long> claims() {
        return List.copyOf(claims);
    }

    /** The ids of the commands that were claimed while an earlier command of their instance was unsettled. */
    List<Long> claimedPastAnUnsettledEarlierCommand() {
        return List.copyOf(claimedPastAnUnsettledEarlierCommand);
    }

    /** The decorator for the engine's command repository. */
    @SuppressWarnings("unchecked")
    UnaryOperator<WorkflowCommandRepository<SqlClient>> commands() {
        return ExecutorFixture.intercepting("claim", (call, member, arguments, real) -> {
            Future<Optional<ClaimedCommand>> answer = (Future<Optional<ClaimedCommand>>) real.call();
            if (!(arguments[0] instanceof CommandHead)) {
                return answer;
            }
            return answer.compose(claim -> {
                if (claim.isEmpty() || !claim.get().isClaim()) {
                    return Future.succeededFuture(claim);
                }
                long id = claim.get().commandId();
                claims.add(id);
                return observer.preparedQuery(EARLIER_UNSETTLED)
                        .execute(Tuple.of(id))
                        .map(rows -> {
                            if (rows.iterator().next().getInteger("earlier") > 0) {
                                claimedPastAnUnsettledEarlierCommand.add(id);
                            }
                            return claim;
                        });
            });
        });
    }

    /** The decorator for the engine's transaction runner: every segment begins after the delay. */
    UnaryOperator<WorkflowTransactionRunner<SqlClient>> segments() {
        return real -> new WorkflowTransactionRunner<>() {
            @Override
            public <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
                return real.inTransaction(level, work);
            }

            @Override
            public <R> Future<R> inTransaction(
                    @Nullable IsolationLevel level, Duration deadline, Function<SqlClient, Future<R>> work) {
                Promise<R> result = Promise.promise();
                vertx.setTimer(segmentDelayMs, timer -> real.inTransaction(level, deadline, work)
                        .onComplete(result));
                return result.future();
            }
        };
    }
}
