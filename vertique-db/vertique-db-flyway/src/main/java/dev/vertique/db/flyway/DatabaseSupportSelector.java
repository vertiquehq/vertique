// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import java.util.Optional;

/** Chooses the {@link DatabaseSupport} for the JDBC destination of a named migration run. */
@FunctionalInterface
interface DatabaseSupportSelector {

    /**
     * Selects the support for a destination.
     *
     * @param jdbcUrl the root JDBC URL of the run
     * @return the support, or empty when named migration does not support the destination
     */
    Optional<DatabaseSupport> select(String jdbcUrl);
}
