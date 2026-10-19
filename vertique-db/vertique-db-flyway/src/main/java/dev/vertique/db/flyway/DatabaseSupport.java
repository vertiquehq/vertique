// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.sql.SQLException;
import java.util.List;

/**
 * Database-specific part of named migration execution. The ordering and reporting engine of
 * {@link FlywayMigrationRunner} calls this boundary and native Flyway only; every vendor rule, query
 * or session lives behind it.
 */
interface DatabaseSupport {

    /**
     * Applies the database's identifier rules to the resolved instances without opening a
     * connection.
     *
     * @param views the resolved instances in execution order
     * @throws MigrationRunException with failure code {@code CONFIGURATION} when a rule is broken
     */
    void validateConfiguration(List<MigrationInstanceView> views);

    /**
     * Opens the session that brackets one named run.
     *
     * @param jdbcUrl the root JDBC URL
     * @param user the root migration user
     * @param password the root migration password
     * @return the open session; the caller closes it on every exit
     * @throws SQLException if the session cannot be opened
     */
    Session open(String jdbcUrl, String user, String password) throws SQLException;

    /** Database session spanning one named run. */
    interface Session extends AutoCloseable {

        /**
         * Checks the enabled instances as a batch before any instance runs.
         *
         * @param enabledViews the enabled instances in execution order
         * @throws MigrationRunException when the batch must not run
         */
        void preflight(List<MigrationInstanceView> enabledViews);

        /**
         * Rechecks one instance immediately before it runs.
         *
         * @param view the instance about to run
         * @throws MigrationRunException when the instance must not run
         */
        void beforeInstance(MigrationInstanceView view);

        /** Releases every resource of the session. */
        @Override
        void close();
    }
}
