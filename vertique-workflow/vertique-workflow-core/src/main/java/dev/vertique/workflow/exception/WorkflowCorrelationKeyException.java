// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when the correlation key a signal wait computes on entry cannot be used: it is {@code null}, blank, longer
 * than 255 characters, holds a NUL character, or the key accessor itself threw.
 *
 * <p>The failure belongs to the segment that moves the instance into the wait; no wait record is written. When the wait
 * was reached while applying a signal, the signal is rejected and the instance stays where it was.
 *
 * <p>The message names the definition, the step and the reason. It never repeats the key, and the exception carries no
 * cause, because the message of a failing accessor may hold the key.
 */
public final class WorkflowCorrelationKeyException extends WorkflowDefinitionException {

    /**
     * Creates the exception.
     *
     * @param definitionId the id of the definition whose wait computed the key
     * @param stepId the step id of the wait
     * @param reason a fixed description of why the key is unusable; must not repeat the key
     */
    public WorkflowCorrelationKeyException(String definitionId, String stepId, String reason) {
        super("Correlation key of step '" + stepId + "' in definition '" + definitionId + "' is unusable: " + reason);
    }
}
