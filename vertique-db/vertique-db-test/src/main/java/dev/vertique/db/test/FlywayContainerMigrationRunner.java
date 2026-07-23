// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.test;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Flyway MIGRATE synchronously for test container setup. Package-private — called only from
 * {@link DatabaseContainer}.
 */
final class FlywayContainerMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayContainerMigrationRunner.class);

    private FlywayContainerMigrationRunner() {}

    /**
     * Runs Flyway migration against the given JDBC URL.
     *
     * @param jdbcUrl   the JDBC URL
     * @param user      the database user
     * @param password  the database password
     * @param locations the migration file locations
     */
    static void run(String jdbcUrl, String user, String password, String locations) {
        log.info("Running Flyway migration for test container: {}", jdbcUrl);
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(locations)
                .load();
        var result = flyway.migrate();
        log.info("Flyway test migration complete: {} migrations applied", result.migrationsExecuted);
    }
}
