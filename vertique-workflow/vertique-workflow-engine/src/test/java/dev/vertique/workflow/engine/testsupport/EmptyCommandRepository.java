// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.List;

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
}
