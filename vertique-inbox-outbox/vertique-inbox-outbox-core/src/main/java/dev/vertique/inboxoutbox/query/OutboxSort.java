// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

/**
 * The order of an outbox entry listing. Every order breaks ties by entry id, in the same direction, so a page
 * boundary is always unambiguous.
 */
public enum OutboxSort {

    /** Most recently created first. */
    CREATED_AT_DESC("created_at", false),

    /** Oldest created first. */
    CREATED_AT_ASC("created_at", true),

    /**
     * Most recently changed first: the time an entry entered its current state, so for a dead letter it is
     * when it failed. This is the default.
     */
    UPDATED_AT_DESC("updated_at", false),

    /** Least recently changed first. */
    UPDATED_AT_ASC("updated_at", true);

    private final String column;
    private final boolean ascending;

    OutboxSort(String column, boolean ascending) {
        this.column = column;
        this.ascending = ascending;
    }

    /**
     * Returns the name of the timestamp column the listing orders by.
     *
     * @return the column name, a constant of this module
     */
    public String column() {
        return column;
    }

    /**
     * Returns whether the listing runs from the smallest value to the largest.
     *
     * @return {@code true} for ascending, {@code false} for descending
     */
    public boolean ascending() {
        return ascending;
    }
}
