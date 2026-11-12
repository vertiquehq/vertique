// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reads what the database recorded about every statement it ran, for a diagnostic run that asked for it.
 *
 * <p>The figures come from {@code pg_stat_statements}: calls, total and mean execution time, rows and buffer use per
 * normalized statement. The extension is loaded by the server and changes the database setup, which is why a run that
 * records statements must be marked diagnostic and names the extension in its manifest. Statement time is time inside
 * the server; it does not include the wait for a connection or the network.
 */
final class Statements {

    private Statements() {}

    static JsonObject read(Connection c) throws SQLException {
        JsonObject out = new JsonObject();
        try (Statement s = c.createStatement()) {
            try (ResultSet r =
                    s.executeQuery("SELECT sum(calls), sum(total_exec_time), count(*) FROM pg_stat_statements"
                            + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())")) {
                r.next();
                out.put("totalCalls", r.getLong(1))
                        .put("totalExecutionMs", r.getDouble(2))
                        .put("distinctStatements", r.getLong(3));
            }
            JsonArray rows = new JsonArray();
            try (ResultSet r = s.executeQuery("SELECT queryid, calls, total_exec_time, mean_exec_time, rows,"
                    + " shared_blks_hit, shared_blks_read, left(query, 3000) FROM pg_stat_statements"
                    + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"
                    + " ORDER BY total_exec_time DESC LIMIT 400")) {
                while (r.next()) {
                    rows.add(new JsonObject()
                            .put("queryid", r.getLong(1))
                            .put("calls", r.getLong(2))
                            .put("totalExecMs", r.getDouble(3))
                            .put("meanExecMs", r.getDouble(4))
                            .put("rows", r.getLong(5))
                            .put("sharedBlksHit", r.getLong(6))
                            .put("sharedBlksRead", r.getLong(7))
                            .put("query", r.getString(8)));
                }
            }
            out.put("byTotalTime", rows);
        }
        return out;
    }
}
