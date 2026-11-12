// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
import io.vertx.sqlclient.SqlClient;

public final class ReplySignalsTestBridge {
    private ReplySignalsTestBridge() {}

    public static RelayedSignals<SqlClient> relayedSignals(WorkflowEngineHandle handle) {
        return ((WorkflowEngine) ((WorkflowTestTables.WrappedEngine) handle).underlyingEngine()).relayedSignals();
    }
}
