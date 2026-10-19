// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens the JDBC connections the runner owns itself, as opposed to the connections native Flyway
 * opens for migrating. Kept as a seam so connection use can be observed.
 */
@FunctionalInterface
interface JdbcConnector {

    /**
     * Opens a connection.
     *
     * @param url the JDBC URL
     * @param user the user
     * @param password the password
     * @return the open connection; the caller closes it
     * @throws SQLException if the connection cannot be opened
     */
    Connection connect(String url, String user, String password) throws SQLException;
}
