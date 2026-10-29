// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.engine.spi.TaskReservations;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

/** Task reservations of a database that grants every reservation, for engine tests that run without a database. */
public final class AcceptingTaskReservations implements TaskReservations<SqlClient> {

    @Override
    public Future<Boolean> markCompleting(String taskId, long commandId, SqlClient tx) {
        return Future.succeededFuture(true);
    }

    @Override
    public Future<Boolean> reopen(String taskId, long commandId, SqlClient tx) {
        return Future.succeededFuture(true);
    }
}
