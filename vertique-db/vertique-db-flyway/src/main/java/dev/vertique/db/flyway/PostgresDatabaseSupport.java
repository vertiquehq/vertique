// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL {@link DatabaseSupport}, selected for {@code jdbc:postgresql:} destinations. It is the
 * only database that named migration supports.
 *
 * <p>This version adds no checks of its own: configuration validation accepts every view, and the
 * session opens no connection and checks nothing.
 */
final class PostgresDatabaseSupport implements DatabaseSupport {

    private static final String URL_PREFIX = "jdbc:postgresql:";

    /** Opens the connections this support owns; none are opened yet. */
    @SuppressWarnings("unused")
    private final JdbcConnector connector;

    /**
     * Creates the PostgreSQL support.
     *
     * @param connector the connector for connections the support owns
     */
    PostgresDatabaseSupport(JdbcConnector connector) {
        this.connector = Objects.requireNonNull(connector, "connector must not be null");
    }

    /**
     * Returns a selector that hands out PostgreSQL support for PostgreSQL destinations and reports
     * every other destination as unsupported.
     *
     * @param connector the connector for connections the support owns
     * @return the selector
     */
    static DatabaseSupportSelector selector(JdbcConnector connector) {
        return jdbcUrl -> jdbcUrl != null && jdbcUrl.startsWith(URL_PREFIX)
                ? Optional.of(new PostgresDatabaseSupport(connector))
                : Optional.empty();
    }

    @Override
    public void validateConfiguration(List<MigrationInstanceView> views) {
        // No PostgreSQL-specific configuration rules yet.
    }

    @Override
    public Session open(String jdbcUrl, String user, String password) {
        return new NoOpSession();
    }

    /** Session that holds no connection and checks nothing. */
    private static final class NoOpSession implements Session {

        @Override
        public void preflight(List<MigrationInstanceView> enabledViews) {
            // Nothing to check yet.
        }

        @Override
        public void beforeInstance(MigrationInstanceView view) {
            // Nothing to check yet.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
