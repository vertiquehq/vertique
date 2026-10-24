// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.executor;

/**
 * Receives the events of the workflow executor, for metrics and logging.
 *
 * <p>Applications contribute observers to the {@code Set<WorkflowExecutorObserver>} multibinding, which is empty by
 * default. Events carry the command kind, an outcome, counts and durations only, never an instance id or a payload.
 *
 * <p>{@link #onEvent} runs on an event loop, outside any database transaction, and must not block. The executor calls
 * every observer in its own {@code try}: an exception an observer throws is caught and logged at warn level with the
 * observer class and the event type, the remaining observers still receive the event, and command execution,
 * outcomes and transactions are unaffected.
 *
 * <p>The set of event types grows additively in later releases, so an observer must tolerate an event type it does
 * not know, for example with a {@code default} arm that ignores it.
 */
public interface WorkflowExecutorObserver {

    /**
     * Handles one executor event.
     *
     * @param event the event; never {@code null}
     */
    void onEvent(WorkflowExecutorEvent event);
}
