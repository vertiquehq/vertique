// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL {@link DatabaseSupport}, selected for {@code jdbc:postgresql:} destinations. It is the
 * only database that named migration supports.
 *
 * <p>Configuration validation applies the PostgreSQL identifier rules of {@link PostgresIdentifiers}
 * to every resolved instance without connecting. A session is a {@link PostgresGuardSession}: a
 * dedicated direct connection with the root credential pair that confirms the destination is
 * PostgreSQL, coordinates migrating runs with an advisory session lock, resolves and freezes unknown
 * default schemas and guards history initialization.
 */
final class PostgresDatabaseSupport implements DatabaseSupport {

    private static final String URL_PREFIX = "jdbc:postgresql:";

    /** Opens the guard connection and the short default-schema connections this support owns. */
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
        PostgresIdentifiers.validate(views);
    }

    @Override
    public Session open(String jdbcUrl, String user, String password, long lockTimeoutMs) throws SQLException {
        return PostgresGuardSession.open(connector, jdbcUrl, user, password, lockTimeoutMs);
    }
}
