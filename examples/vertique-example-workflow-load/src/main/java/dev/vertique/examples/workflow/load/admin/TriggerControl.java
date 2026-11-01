// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import dev.vertique.examples.workflow.load.config.LoadArm;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and sets the state of the two notification triggers, and refuses to set it while anything listens.
 *
 * <p>The trigger state is the only database change an arm makes. It is set once, in the quiet step before any node
 * starts: a node that is already connected has issued {@code LISTEN} for a state that would then change under it,
 * and the run would measure a mixture. {@link #apply(Connection, LoadArm)} therefore refuses while another session's
 * last statement is a {@code LISTEN}, and it reads the state back after the change and fails if it differs from the
 * arm.
 *
 * <p>The listener check reads the last statement of other sessions in {@code pg_stat_activity}. PostgreSQL shows
 * that text only for sessions of the same role or to a superuser; a run uses one role for everything.
 */
final class TriggerControl {

    /**
     * The state of the two triggers.
     *
     * @param workflowEnabled whether the trigger on {@code workflow_commands} fires
     * @param outboxEnabled whether the trigger on {@code outbox} fires
     */
    record State(boolean workflowEnabled, boolean outboxEnabled) {

        /**
         * Returns the state an arm asks for.
         *
         * @param arm the arm
         * @return the trigger state of the arm
         */
        static State of(LoadArm arm) {
            return new State(arm.workflowTriggerEnabled(), arm.outboxTriggerEnabled());
        }

        @Override
        public String toString() {
            return "workflow-trigger=" + word(workflowEnabled) + " outbox-trigger=" + word(outboxEnabled);
        }

        private static String word(boolean enabled) {
            return enabled ? "enabled" : "disabled";
        }
    }

    private static final String LISTENERS = "SELECT pid, application_name FROM pg_stat_activity"
            + " WHERE datname = current_database() AND pid <> pg_backend_pid() AND query ILIKE 'LISTEN %'";

    private static final String TRIGGER_STATE =
            "SELECT t.tgenabled FROM pg_trigger t WHERE t.tgrelid = to_regclass(?) AND t.tgname = ? AND NOT t.tgisinternal";

    private TriggerControl() {}

    /**
     * Reads the state of the two triggers.
     *
     * @param connection a connection to the load database
     * @return the state
     * @throws SQLException if a query fails
     * @throws IllegalStateException if a trigger does not exist, which means the migrations have not run
     */
    static State read(Connection connection) throws SQLException {
        return new State(
                isEnabled(connection, LoadArm.WORKFLOW_TABLE, LoadArm.WORKFLOW_TRIGGER),
                isEnabled(connection, LoadArm.OUTBOX_TABLE, LoadArm.OUTBOX_TRIGGER));
    }

    /**
     * Puts the two triggers into the state of an arm.
     *
     * @param connection a connection to the load database; its auto-commit mode is left as it is
     * @param arm the arm
     * @return the state read back after the change
     * @throws SQLException if a statement fails
     * @throws IllegalStateException if another session is listening, a trigger does not exist, or the state read back
     *     differs from the arm
     */
    static State apply(Connection connection, LoadArm arm) throws SQLException {
        requireNoListeners(connection);
        // Fails before any change when the triggers do not exist yet, that is, before the migrations ran.
        read(connection);
        try (Statement statement = connection.createStatement()) {
            for (String sql : arm.triggerStatements()) {
                statement.execute(sql);
            }
        }
        State after = read(connection);
        if (!after.equals(State.of(arm))) {
            throw new IllegalStateException(
                    "Arm " + arm + " asks for " + State.of(arm) + " but the database reports " + after);
        }
        return after;
    }

    /**
     * Fails while another session in this database is listening for notifications.
     *
     * @param connection a connection to the load database
     * @throws SQLException if the query fails
     * @throws IllegalStateException naming the application names of the listening sessions
     */
    static void requireNoListeners(Connection connection) throws SQLException {
        List<String> listeners = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(LISTENERS)) {
            while (rows.next()) {
                String name = rows.getString("application_name");
                listeners.add(name == null || name.isEmpty() ? "pid " + rows.getInt("pid") : name);
            }
        }
        if (!listeners.isEmpty()) {
            throw new IllegalStateException("Refusing to change trigger state while a session is listening: "
                    + listeners + ". Set the arm in the quiet step, before any node starts.");
        }
    }

    private static boolean isEnabled(Connection connection, String table, String trigger) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRIGGER_STATE)) {
            statement.setString(1, table);
            statement.setString(2, trigger);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException(
                            "Trigger " + trigger + " on " + table + " does not exist; run the migrations first");
                }
                return !"D".equals(rows.getString(1));
            }
        }
    }
}
