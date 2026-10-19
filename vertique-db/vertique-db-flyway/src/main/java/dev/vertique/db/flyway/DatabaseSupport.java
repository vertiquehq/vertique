// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Database-specific part of named migration execution. The ordering and reporting engine of
 * {@link FlywayMigrationRunner} calls this boundary and native Flyway only; every vendor rule, query,
 * session and coordination lock lives behind it.
 *
 * <p>Every rejection is a {@link MigrationRunException} whose message is a bare, fixed detail: it
 * names the instance where one is to blame, never a schema, table, URL, credential or native text, and
 * carries no cause. The orchestration adds the operation prefix and the report.
 */
interface DatabaseSupport {

    /**
     * Applies the database's identifier rules to the resolved instances without opening a
     * connection.
     *
     * @param views the resolved instances in execution order, disabled ones included
     * @throws MigrationRunException with failure code {@code CONFIGURATION} when a rule is broken
     */
    void validateConfiguration(List<MigrationInstanceView> views);

    /**
     * Opens the session that brackets one named run and confirms that the destination is the
     * database this support handles.
     *
     * @param jdbcUrl the root JDBC URL
     * @param user the root migration user
     * @param password the root migration password
     * @param lockTimeoutMs the upper bound, in milliseconds, on waiting for the coordination lock the
     *     session takes when the batch migrates
     * @return the open session; the caller closes it on every exit
     * @throws SQLException if the session cannot be opened
     * @throws MigrationRunException when the destination cannot be used
     */
    Session open(String jdbcUrl, String user, String password, long lockTimeoutMs) throws SQLException;

    /** Database session spanning one named run. */
    interface Session extends AutoCloseable {

        /**
         * Checks the enabled instances as a batch before any instance runs. When an instance migrates,
         * the session first takes the coordination lock and holds it until it is closed. It then
         * resolves and freezes every unknown default schema and checks the batch.
         *
         * @param enabledInstances the enabled instances in execution order
         * @param rootFirstSchema the first entry of the root schema list, or {@code null} when it has
         *     none
         * @return the frozen views and the instances whose existing history must be validated before any
         *     instance runs
         * @throws MigrationRunException when the batch must not run; failure code {@code COORDINATION}
         *     and operation {@code coordination} when the lock was not acquired in time
         */
        Preflight preflight(List<NamedPlan.Instance> enabledInstances, String rootFirstSchema);

        /**
         * Rechecks one instance immediately before it runs.
         *
         * @param frozenView the frozen view of the instance about to run
         * @throws MigrationRunException when the instance must not run; failure code {@code
         *     COORDINATION} and operation {@code coordination} when the coordination lock was lost or is not held
         */
        void beforeInstance(MigrationInstanceView frozenView);

        /** Releases every resource of the session, the coordination lock included; never throws. */
        @Override
        void close();
    }

    /**
     * Answer of a batch preflight.
     *
     * @param frozenViews the enabled views in the order received, each with its default schema
     *     resolved where it was unknown; the run binds and reports these
     * @param historyOwners names of the instances that keep an existing history beside instances that
     *     initialize theirs, and whose history must pass native validation before any instance runs;
     *     empty when no such validation is needed
     */
    record Preflight(List<MigrationInstanceView> frozenViews, Set<String> historyOwners) {

        public Preflight {
            frozenViews = List.copyOf(frozenViews);
            historyOwners = Set.copyOf(historyOwners);
        }
    }
}
