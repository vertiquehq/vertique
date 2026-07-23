// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * A step that creates a human task and waits for a user decision before advancing.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code taskType} — logical type of the human task (e.g., {@code "approval"}).
 *   <li>{@code assignment} — who the task is assigned to.
 *   <li>{@code decisions} — list of possible decisions the assignee can make, each leading to a
 *       different next step.
 *   <li>{@code due} — optional due date configuration; {@code null} if no due date is defined.
 *   <li>{@code reminders} — optional reminder schedule; {@code null} if no reminders are
 *       configured.
 *   <li>{@code requireVersionStability} — when {@code true}, the workflow engine must reject
 *       decision submissions if the definition version has been superseded while the task was
 *       open. {@code null} in the deserialized record when the field is absent in the source
 *       document; callers treat {@code null} as {@code false}. Using {@link Boolean} (boxed)
 *       rather than primitive {@code boolean} allows Jackson to represent an absent field as
 *       {@code null} without triggering {@code FAIL_ON_NULL_FOR_PRIMITIVES}.
 * </ul>
 */
public record HumanTaskStep(
        String id,
        String taskType,
        AssignmentBlock assignment,
        List<TaskDecisionBlock> decisions,
        @Nullable DueBlock due,
        @Nullable ReminderBlock reminders,
        @Nullable Boolean requireVersionStability)
        implements StepNode {

    /**
     * Describes who a human task is assigned to.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code mode} — assignment mode: one of {@code "user"}, {@code "role"},
     *       {@code "queue"}, {@code "user-from-state"}, {@code "role-from-state"},
     *       {@code "queue-from-state"}. For literal modes ({@code "user"}, {@code "role"},
     *       {@code "queue"}), {@code value} is set. For state-derived modes, {@code resolver}
     *       is set. The parser does NOT enforce this constraint — it is the validator's job.
     *   <li>{@code value} — literal assignment target (e.g., a username, role name, or queue
     *       name); {@code null} for state-derived modes.
     *   <li>{@code resolver} — registered task assignment resolver id for state-derived modes;
     *       {@code null} for literal modes.
     * </ul>
     */
    public record AssignmentBlock(
            String mode, @Nullable String value, @Nullable String resolver) {}

    /**
     * Describes a decision option that a task assignee can take.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code name} — decision name (e.g., {@code "approve"}, {@code "reject"}).
     *   <li>{@code payloadType} — fully-qualified class name of the decision payload type.
     *   <li>{@code applicator} — registered decision-applicator id that applies the decision
     *       payload to the current workflow state (distinct from a generic state reducer —
     *       decision applicators have explicit business semantics).
     *   <li>{@code next} — id of the step to transition to after this decision is applied.
     * </ul>
     */
    public record TaskDecisionBlock(String name, String payloadType, String applicator, String next) {}

    /**
     * Optional due-date configuration for a {@link HumanTaskStep}.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code at} — when the task is due, expressed as an ISO-8601 Duration
     *       (relative to step entry), an ISO-8601 Instant (absolute wall-clock time), or
     *       {@code "ref:<resolverId>"}. Value-level parsing is the validator's responsibility.
     *   <li>{@code onDueMutator} — registered state mutator id applied when the due date passes.
     *   <li>{@code next} — id of the step to transition to when the due date fires.
     * </ul>
     */
    public record DueBlock(String at, String onDueMutator, String next) {}

    /**
     * Optional reminder schedule for a {@link HumanTaskStep}.
     *
     * <p>In v1, exactly one of {@code offsets} or {@code interval} should be set; the parser
     * allows both to be {@code null} (no reminders). The validator enforces the "exactly one"
     * constraint.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code offsets} — list of ISO-8601 Duration strings representing specific relative
     *       times before/after due date at which reminders are sent (e.g.,
     *       {@code ["-PT48H", "-PT24H", "-PT2H"]}).
     *   <li>{@code interval} — a single ISO-8601 Duration string for periodic reminders at a
     *       fixed interval (e.g., {@code "PT6H"}).
     * </ul>
     */
    public record ReminderBlock(
            @Nullable List<String> offsets, @Nullable String interval) {}
}
