// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private collaborator responsible for emitting {@code WORKFLOW_EVENT} side-effect intents
 * and building attribute maps for lifecycle events.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C1). It is a leaf in the dependency graph: it depends only on
 * {@link RecorderRouter} and {@link Clock}, and has no reference to the transition driver or
 * fork/join coordinator.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class WorkflowEventEmitter {

    // --- Dependencies ---

    private final RecorderRouter recorders;
    private final Clock clock;

    /**
     * Constructs a new emitter.
     *
     * @param recorders the recorder router used to dispatch {@code WORKFLOW_EVENT} intents
     * @param clock the clock used to timestamp emitted events
     */
    @Inject
    WorkflowEventEmitter(RecorderRouter recorders, Clock clock) {
        this.recorders = recorders;
        this.clock = clock;
    }

    // --- Event emission ---

    /**
     * Emits a {@code WORKFLOW_EVENT} side-effect intent for a lifecycle event. If no
     * {@code WORKFLOW_EVENT} recorder is registered (the kind is optional), the call is a silent
     * no-op that returns a succeeded future.
     *
     * @param inst the workflow instance that produced the event
     * @param type the event type
     * @param sequence the {@code workflow_history} sequence number at which the event occurred
     * @param taskId the task UUID for task-lifecycle events; {@code null} for workflow events
     * @param stepId the plan step id that produced the event; {@code null} when not step-bound
     * @param attributes arbitrary key-value metadata; must not contain {@code null} values
     * @param tx the active transaction
     * @return a {@link Future} that completes when the intent has been durably recorded (or is
     *     silently skipped because no event recorder is registered)
     */
    Future<Void> emitEvent(
            WorkflowInstance inst,
            WorkflowEventType type,
            long sequence,
            UUID taskId,
            String stepId,
            Map<String, Object> attributes,
            SqlClient tx) {
        WorkflowEventIntent eventIntent = new WorkflowEventIntent(
                type,
                inst.id(),
                inst.definitionId(),
                inst.definitionVersion(),
                inst.subjectRef(),
                inst.businessKey(),
                clock.instant(),
                sequence,
                taskId,
                stepId,
                attributes,
                null,
                null);
        WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                IntentKind.WORKFLOW_EVENT,
                type.name(),
                eventIntent,
                Map.of(),
                WorkflowSideEffectIntent.Correlation.singlePath(
                        inst.id(), sequence, inst.definitionId(), stepId != null ? stepId : inst.currentStepId()));
        return recorders.route(intent, tx).mapEmpty();
    }

    // --- Attribute helpers ---

    /**
     * Builds a string-keyed attribute map, omitting entries whose value is {@code null}.
     * Use this instead of {@link Map#of} when any value may be null.
     *
     * @param pairs alternating key, value pairs; must be an even number of elements
     * @return a {@link LinkedHashMap} containing only the non-null entries
     */
    static Map<String, Object> attrs(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            String key = (String) pairs[i];
            Object val = pairs[i + 1];
            if (val != null) {
                m.put(key, val);
            }
        }
        return m;
    }
}
