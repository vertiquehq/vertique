// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A command repository of a database that records no commands, for engine tests that do not read the log. */
public final class EmptyCommandRepository implements WorkflowCommandRepository<SqlClient> {

    @Override
    public Future<Integer> countRunnable(WorkflowInstanceId instance) {
        return Future.succeededFuture(0);
    }

    @Override
    public Future<List<PendingCommand>> findDeferred(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<PendingCommand>> findParked(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<List<PendingCommand>> findNotYetAvailable(WorkflowInstanceId instance) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Void> lockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Boolean> tryLockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(true);
    }

    @Override
    public Future<Long> nextQueueSequence(WorkflowInstanceId instance, SqlClient tx) {
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<Long> recordStart(
            WorkflowInstanceId instance,
            String idempotencyKey,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            SqlClient tx) {
        return Future.succeededFuture(1L);
    }

    @Override
    public Future<List<CommandHead>> peek(int limit) {
        return Future.succeededFuture(List.of());
    }

    @Override
    public Future<Optional<ClaimedCommand>> claim(CommandHead head, String claimedBy, int leaseSeconds) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Optional<SegmentCommand>> fence(ClaimedCommand claim, SqlClient tx) {
        return Future.succeededFuture(Optional.empty());
    }

    @Override
    public Future<Integer> complete(long commandId, UUID token, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, SqlClient tx) {
        return Future.succeededFuture(1);
    }

    @Override
    public Future<Boolean> release(ClaimedCommand claim) {
        return Future.succeededFuture(false);
    }
}
