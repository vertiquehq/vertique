// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.Map;

/**
 * Canonical {@code (kind, value)} map projections for the sealed
 * {@link WorkflowActor} and {@link TaskAssignment} types.
 *
 * <p>Single source of truth for the {@code "kind"}/{@code "value"} string discriminator pair used
 * by event attribute payloads, history payload JSON, and the dedup-fingerprint canonicalizer. All
 * three callers (engine event emission, history persistence, fingerprint hashing) MUST converge on
 * the same kind names so the persisted, hashed, and emitted shapes don't drift.
 *
 * <p>Callers needing a {@code Map<String, Object>} can pass the result directly because
 * {@code Map<String, String>} is assignment-compatible with {@code Map<String, ? extends Object>}.
 * Callers needing a Vert.x {@code JsonObject} can construct one via {@code new JsonObject(map)} at
 * the boundary (kept out of workflow-core to preserve its SQL-/JSON-driver-free public surface).
 */
public final class WorkflowActorMaps {

    private WorkflowActorMaps() {}

    /**
     * Projects a {@link WorkflowActor} to a two-entry map with {@code "kind"} and {@code "value"}
     * keys.
     *
     * <p>Kind values: {@code "USER"} for {@link WorkflowActor.User}, {@code "SERVICE"} for
     * {@link WorkflowActor.Service}, {@code "SYSTEM"} for {@link WorkflowActor.System}. The value
     * is the actor's identifying string ({@code userId} / {@code serviceId} / {@code reason}).
     *
     * @param actor the actor; must not be null
     * @return an immutable map with {@code "kind"} and {@code "value"} entries
     */
    public static Map<String, String> toMap(WorkflowActor actor) {
        return switch (actor) {
            case WorkflowActor.User u -> Map.of("kind", "USER", "value", u.userId());
            case WorkflowActor.Service s -> Map.of("kind", "SERVICE", "value", s.serviceId());
            case WorkflowActor.System sys -> Map.of("kind", "SYSTEM", "value", sys.reason());
        };
    }

    /**
     * Projects a {@link TaskAssignment} to a two-entry map with {@code "kind"} and {@code "value"}
     * keys.
     *
     * <p>Kind values: {@code "USER"} for {@link TaskAssignment.User}, {@code "ROLE"} for
     * {@link TaskAssignment.Role}, {@code "QUEUE"} for {@link TaskAssignment.Queue}.
     *
     * @param assignment the assignment; must not be null
     * @return an immutable map with {@code "kind"} and {@code "value"} entries
     */
    public static Map<String, String> toMap(TaskAssignment assignment) {
        return switch (assignment) {
            case TaskAssignment.User u -> Map.of("kind", "USER", "value", u.userId());
            case TaskAssignment.Role r -> Map.of("kind", "ROLE", "value", r.roleId());
            case TaskAssignment.Queue q -> Map.of("kind", "QUEUE", "value", q.queueName());
        };
    }
}
