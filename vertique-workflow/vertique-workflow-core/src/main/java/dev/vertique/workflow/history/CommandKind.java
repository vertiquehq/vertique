// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

/** The kinds of command the engine records for an instance. Applications cannot add one. */
public enum CommandKind {
    /** Creates the instance and runs it to its first wait. */
    START,
    /** Delivers a named signal. */
    SIGNAL,
    /** Completes a human task with a decision. */
    COMPLETION,
    /** Re-executes the current step of a failed instance. */
    RETRY,
    /** Moves the instance to another definition version. */
    MIGRATION,
    /** Fires a timer. */
    TIMER
}
