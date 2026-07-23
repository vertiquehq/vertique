// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * Sealed sum type representing the identity of the actor who initiated a state-changing workflow
 * task operation.
 *
 * <p>Every mutating task command ({@link dev.vertique.workflow.ops.TaskCompletionCommand},
 * {@link dev.vertique.workflow.ops.TaskReassignmentCommand}) requires a non-null {@code WorkflowActor}
 * so the append-only workflow history can answer "who initiated this change?". The actor is
 * strictly audit metadata — the runtime never gates a call on the actor identity; the
 * application's own authorization layer decides whether a given actor is permitted to act.
 *
 * <p>Persisted as a {@code (kind, value)} pair on the {@code workflow_tasks} row's actor columns
 * ({@code completed_by}, {@code cancelled_by}, {@code reassigned_by}); that shape is built and
 * parsed by hand in {@code PgTaskStore} and is independent of this interface's Jackson mapping.
 * When a {@code WorkflowActor} is embedded as a field inside a history payload record (e.g.
 * {@code TaskCompletedHistoryPayload.completedBy}), Jackson serializes it as the permit's bare
 * record shape — {@code {"userId":"..."}} / {@code {"serviceId":"..."}} / {@code {"reason":"..."}}
 * — with no wrapper or type discriminator. {@link JsonTypeInfo.Id#DEDUCTION} deserialization picks
 * the correct permit by which field is present, so this wire shape is preserved byte-for-byte
 * while still allowing {@code WorkflowActor} to round-trip through Jackson (ADR-0048).
 *
 * <p>Each permit's compact constructor rejects both {@code null} and blank strings — an empty
 * {@code User("")} or whitespace-only {@code System("   ")} is a programmer error, not a usable
 * audit identity.
 *
 * <p>Three permits are defined:
 * <ul>
 *   <li>{@link User} — a human user acting through a UI</li>
 *   <li>{@link Service} — an automated service or integration acting on the workflow</li>
 *   <li>{@link System} — an engine-internal actor for system-driven actions</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
    @JsonSubTypes.Type(WorkflowActor.User.class),
    @JsonSubTypes.Type(WorkflowActor.Service.class),
    @JsonSubTypes.Type(WorkflowActor.System.class)
})
public sealed interface WorkflowActor permits WorkflowActor.User, WorkflowActor.Service, WorkflowActor.System {

    /**
     * A human user driving the action through a UI.
     *
     * @param userId the application's stable identifier for this user; must not be null or blank
     */
    record User(String userId) implements WorkflowActor {
        /**
         * Validates that {@code userId} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code userId} is null
         * @throws IllegalArgumentException if {@code userId} is blank
         */
        public User {
            Objects.requireNonNull(userId, "userId");
            if (userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
        }
    }

    /**
     * Another service, cron job, or external automation acting on the workflow.
     *
     * @param serviceId the application's stable identifier for this service; must not be null or
     *     blank
     */
    record Service(String serviceId) implements WorkflowActor {
        /**
         * Validates that {@code serviceId} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code serviceId} is null
         * @throws IllegalArgumentException if {@code serviceId} is blank
         */
        public Service {
            Objects.requireNonNull(serviceId, "serviceId");
            if (serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
        }
    }

    /**
     * Engine-internal actor for system-driven actions such as workflow-cascade cancel or recovery
     * flows. {@code reason} documents the trigger (e.g., {@code "due-date-expired"},
     * {@code "workflow-cancelled"}).
     *
     * @param reason human-readable description of why the system performed this action; must not be
     *     null or blank
     */
    record System(String reason) implements WorkflowActor {
        /**
         * Validates that {@code reason} is non-null and non-blank.
         *
         * @throws NullPointerException if {@code reason} is null
         * @throws IllegalArgumentException if {@code reason} is blank
         */
        public System {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}
