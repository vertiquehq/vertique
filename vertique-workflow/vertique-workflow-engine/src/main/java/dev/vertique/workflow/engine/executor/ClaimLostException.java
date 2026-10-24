// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

/**
 * INTERNAL: the settlement of a segment's command updated no row, so the claim no longer stands. It rolls the segment
 * back; the executor writes nothing further and lets the claim lapse, as after a crash.
 */
final class ClaimLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ClaimLostException() {
        super("the command's settlement updated no row");
    }
}
