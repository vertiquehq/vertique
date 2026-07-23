// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

/**
 * SQL transaction isolation levels supported by the framework.
 *
 * <p>Used with {@link TransactionBuilder#isolationLevel(IsolationLevel)} to control the visibility
 * of concurrent transaction effects.
 *
 * @see <a href="https://www.postgresql.org/docs/16/transaction-iso.html">PostgreSQL Transaction Isolation</a>
 */
public enum IsolationLevel {

    /** Read Committed — default for most databases. Each statement sees only rows committed before it began. */
    READ_COMMITTED("READ COMMITTED"),

    /** Repeatable Read — snapshot isolation; no non-repeatable reads or phantom reads within the transaction. */
    REPEATABLE_READ("REPEATABLE READ"),

    /** Serializable — strictest level; transactions behave as if executed one at a time sequentially. */
    SERIALIZABLE("SERIALIZABLE");

    private final String sql;

    IsolationLevel(String sql) {
        this.sql = sql;
    }

    /**
     * Returns the SQL fragment used in {@code SET TRANSACTION ISOLATION LEVEL}.
     *
     * @return the SQL isolation level string
     */
    public String sql() {
        return sql;
    }
}
