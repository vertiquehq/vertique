// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.sqlclient.Row;

/**
 * Maps a database row to a domain object.
 *
 * @param <T> the target type
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Maps the given row to an instance of {@code T}.
     *
     * @param row the database row
     * @return the mapped object
     */
    T map(Row row);
}
