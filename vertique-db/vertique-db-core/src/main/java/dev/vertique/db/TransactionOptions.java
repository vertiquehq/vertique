// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import jakarta.annotation.Nullable;

/**
 * Immutable options for a database transaction.
 *
 * <p>Used internally by {@link TransactionBuilder} to carry the isolation level and read-only flag
 * into {@link AbstractSqlRepository#executeTransaction}.
 *
 * @param isolationLevel the desired isolation level, or {@code null} to use the database default
 * @param readOnly       {@code true} to mark the transaction as read-only (prevents writes and
 *                       enables query optimizations in PostgreSQL)
 */
public record TransactionOptions(@Nullable IsolationLevel isolationLevel, boolean readOnly) {

    /**
     * Default options: database-default isolation level and read-write mode.
     */
    public static final TransactionOptions DEFAULTS = new TransactionOptions(null, false);
}
