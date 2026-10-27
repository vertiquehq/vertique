// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import io.vertx.core.Future;

/**
 * INTERNAL: the settlement of a segment's command updated no row, so the claim no longer stands. It rolls the segment
 * back; the executor writes nothing further and lets the claim lapse, as after a crash.
 */
public final class ClaimLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the failure. */
    public ClaimLostException() {
        super("the command's settlement updated no row");
    }

    /**
     * Checks the row count of a settlement write: a write that updated no row means the claim no longer stands, and the
     * segment rolls back.
     *
     * @param rows the number of command rows the write updated
     * @return a succeeded future when a row was updated, otherwise a future failed with this exception
     */
    public static Future<Void> requireRow(Integer rows) {
        return rows != null && rows > 0 ? Future.succeededFuture() : Future.failedFuture(new ClaimLostException());
    }
}
