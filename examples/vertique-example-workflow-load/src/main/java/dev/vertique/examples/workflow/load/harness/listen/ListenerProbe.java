// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the dedicated listeners of a database from outside the nodes.
 *
 * <p>It reads {@code pg_stat_activity}, which shows each session's last statement, and keeps the sessions whose last
 * statement is a plain {@code LISTEN}. It does not use {@code pg_listening_channels()}, which reports only the
 * calling session. The probe sees the statement text of sessions of the same role, or of any role when it runs as a
 * superuser, so a run connects every process as one role.
 */
public final class ListenerProbe {

    private static final String SQL = "SELECT pid, application_name, (extract(epoch from backend_start) * 1000)::bigint"
            + " AS start_ms, query FROM pg_stat_activity WHERE datname = current_database()"
            + " AND pid <> pg_backend_pid() AND query ILIKE 'LISTEN%' ORDER BY pid";

    private ListenerProbe() {}

    /**
     * Reads the listeners now.
     *
     * @param connection a connection to the run's database
     * @return the listeners, ordered by PID
     * @throws SQLException if the query fails
     */
    public static List<ListenerSession> probe(Connection connection) throws SQLException {
        List<ListenerSession> sessions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SQL)) {
            while (rows.next()) {
                String channel = ListenerSession.channelOf(rows.getString("query"));
                if (channel != null) {
                    String app = rows.getString("application_name");
                    sessions.add(new ListenerSession(
                            rows.getInt("pid"), rows.getLong("start_ms"), app == null ? "" : app, channel));
                }
            }
        }
        return sessions;
    }
}
