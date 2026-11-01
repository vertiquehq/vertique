// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a start or a signal from outside is refused because the cluster holds more due commands than its
 * executors have run: the node reads the number of due commands at or above the high mark of
 * {@code workflow.recording.intake} and has not yet seen it fall to the low mark.
 *
 * <p>The refusal writes nothing: no instance, command, history entry, deduplication key or open-signal count, so the
 * same request can be sent again and the refused key stays free. A start or a signal that was recorded before the
 * pressure rose is not refused: it is answered with its original outcome, and so is a terminal refusal such as a
 * missing or ended instance. The failure is transient and retryable; it maps to HTTP 503. A caller waits and sends
 * the request again, and a message source pauses and keeps its records. The message names no payload, instance or
 * key.
 */
public class WorkflowIntakeRefusedException extends WorkflowUnavailableException {

    /** Creates the refusal. */
    public WorkflowIntakeRefusedException() {
        super("Workflow intake is paused while the command queue drains; send the request again later");
    }
}
