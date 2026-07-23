// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

/**
 * A step that performs a compensating action for a previously executed forward step.
 *
 * <p>Compensation steps are referenced by forward steps (e.g., {@link ServiceStep#compensation()})
 * and are invoked during saga rollback to undo side effects.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code forwardStep} — id of the forward step this compensation undoes.
 *   <li>{@code target} — registered service-call target id for the compensating action
 *       (e.g., {@code "inventory.release"}).
 *   <li>{@code payloadMapper} — registered payload mapper id that maps the current state to the
 *       outgoing compensation payload.
 * </ul>
 */
public record CompensationStep(String id, String forwardStep, String target, String payloadMapper)
        implements StepNode {}
