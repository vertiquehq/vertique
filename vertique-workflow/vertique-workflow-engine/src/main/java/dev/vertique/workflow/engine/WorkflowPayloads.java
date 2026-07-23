// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Package-private collection of static payload and guard utilities used by {@link WorkflowEngine}
 * and its package-sibling collaborators.
 *
 * <p>All methods are pure static helpers — no mutable state, no Dagger injection. This class is
 * a leaf in the dependency graph: it references only domain model types and the Vert.x JSON codec;
 * it has no dependency on repositories, recorders, or the transition driver.
 *
 * <p>Extracted from {@code WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C1). Call sites in the engine and recovery service are updated to use the
 * {@code WorkflowPayloads.} prefix.
 */
final class WorkflowPayloads {

    /** Utility class — no instances. */
    private WorkflowPayloads() {}

    // --- Payload coercion ---

    /**
     * Coerces {@code payload} to {@code targetType} using {@code Json.encode → Json.decodeValue}
     * when the payload is not already an instance of the target type.
     *
     * @param payload the incoming payload; may be null
     * @param targetType the target class to coerce to; if null, returns payload unchanged
     * @return the coerced payload, or the original payload if no coercion is needed
     */
    static Object coercePayload(Object payload, Class<?> targetType) {
        if (targetType == null || payload == null || targetType.isInstance(payload)) {
            return payload;
        }
        // Coerce via JSON round-trip.
        return Json.decodeValue(Json.encodeToBuffer(payload), targetType);
    }

    /**
     * Validates the payload against the decision's declared {@code payloadTypeName} and coerces it
     * if necessary.
     *
     * <p>Payload-nullability rule:
     * <ul>
     *   <li>If {@code payloadTypeName} is {@code "java.lang.Void"} or {@code "void"}, the payload
     *       MUST be {@code null}.</li>
     *   <li>Otherwise the payload MUST be non-null and is coerced via JSON round-trip if it is not
     *       already an instance of the declared class.</li>
     * </ul>
     *
     * @param payload the raw payload from the command
     * @param payloadTypeName the fully-qualified class name declared in the plan decision
     * @return the coerced payload (may be {@code null} for Void decisions)
     * @throws WorkflowDefinitionException if the payload violates the nullability rule
     */
    static Object validateAndCoerceTaskPayload(Object payload, String payloadTypeName) {
        // Special-case the Void / void markers BEFORE Class.forName: the primitive `void.class`
        // returns the literal name "void", which Class.forName rejects with ClassNotFoundException.
        // Without this short-circuit, a developer-declared `decision("approve", void.class)` would
        // build, register, and dispatch — but every completion attempt would fail trying to
        // resolve "void" as a class name. Both `void.class` ("void") and `Void.class`
        // ("java.lang.Void") are accepted as no-payload decisions.
        if ("void".equals(payloadTypeName) || "java.lang.Void".equals(payloadTypeName)) {
            if (payload != null) {
                throw new WorkflowDefinitionException(
                        "Decision declared payloadType Void but a non-null payload was provided");
            }
            return null;
        }

        Class<?> payloadClass;
        try {
            payloadClass = Class.forName(payloadTypeName);
        } catch (ClassNotFoundException e) {
            throw new WorkflowDefinitionException(
                    "Decision payloadTypeName '" + payloadTypeName + "' cannot be resolved: " + e.getMessage());
        }

        if (payload == null) {
            throw new WorkflowDefinitionException(
                    "Decision declared payloadType '" + payloadTypeName + "' but payload is null");
        }
        return coercePayload(payload, payloadClass);
    }

    /**
     * Resolves the declared payload type name for a named decision on a task record.
     *
     * @param task the task record whose decisions list is searched
     * @param decisionName the name of the decision to look up
     * @return the payload type name declared for the matching decision
     * @throws WorkflowDefinitionException if no decision with {@code decisionName} exists on
     *     {@code task}
     */
    static String resolveDecisionPayloadType(TaskRecord task, String decisionName) {
        return task.decisions().stream()
                .filter(d -> d.name().equals(decisionName))
                .map(TaskDecisionDescriptor::payloadTypeName)
                .findFirst()
                .orElseThrow(() -> new WorkflowDefinitionException(
                        "Unknown decision '" + decisionName + "' for branch task '" + task.taskId() + "'"));
    }

    // --- History payload decoding guards ---

    /**
     * Decodes {@code payloadJson} as an instance of {@code type}, throwing
     * {@link WorkflowDefinitionException} if the JSON is null, malformed, or cannot be mapped to
     * the target type.
     *
     * <p>This is the fail-loud companion to the history-payload reader used in compensation
     * matching. Required history payloads — {@code SIGNAL_RECEIVED} and
     * {@code SIDE_EFFECT_RECORDED} — are used as execution input for compensation. Silently
     * skipping a decode failure would cause under-compensation; instead we surface the corruption
     * immediately with context about which entry is affected.
     *
     * @param <T> the target payload record type
     * @param payloadJson the JSON string to decode; must not be {@code null}
     * @param type the class of the target payload record
     * @param entry the history entry whose payload is being decoded (for error context)
     * @param entryType the entry type name (for error messages)
     * @return the decoded payload instance; never {@code null}
     * @throws WorkflowDefinitionException if the payload is null, malformed, or cannot be decoded
     */
    static <T> T decodeOrFail(String payloadJson, Class<T> type, WorkflowHistoryEntry entry, String entryType) {
        try {
            T result = Json.decodeValue(payloadJson, type);
            if (result == null) {
                throw new WorkflowDefinitionException(
                        "Malformed history payload: " + entryType + " entry at sequence " + entry.sequence()
                                + " of instance " + entry.instanceId().value() + " decoded to null.");
            }
            return result;
        } catch (WorkflowDefinitionException wde) {
            throw wde;
        } catch (Exception ex) {
            throw new WorkflowDefinitionException(
                    "Malformed history payload: " + entryType + " entry at sequence " + entry.sequence()
                            + " of instance " + entry.instanceId().value() + " could not be decoded as "
                            + type.getSimpleName() + " — compensation cannot proceed safely. Cause: " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Asserts that a required field decoded from a history payload is non-null, throwing
     * {@link WorkflowDefinitionException} if it is absent.
     *
     * <p>Jackson maps missing JSON fields to {@code null} for record components rather than
     * throwing. This guard ensures that fields which are logically required for compensation
     * matching are treated as structural failures rather than acceptable absent values.
     *
     * @param value the decoded field value to check
     * @param fieldName the name of the field (for the error message)
     * @param entry the history entry whose payload is being validated (for error context)
     * @param entryType the entry type name (for error messages)
     * @throws WorkflowDefinitionException if {@code value} is {@code null}
     */
    static void requireField(Object value, String fieldName, WorkflowHistoryEntry entry, String entryType) {
        if (value == null) {
            throw new WorkflowDefinitionException("Malformed history payload: " + entryType + " entry at sequence "
                    + entry.sequence() + " of instance " + entry.instanceId().value() + " has null or missing '"
                    + fieldName + "' — compensation cannot proceed safely.");
        }
    }

    // --- Plan-hash and row-update guards ---

    /**
     * Returns a successful {@code Future<Void>} when {@code rowCount > 0}, or a failed future with
     * {@link WorkflowConflictException} when {@code rowCount == 0}.
     *
     * @param rowCount the number of rows updated by the optimistic update
     * @param context human-readable description of the operation (for the exception message)
     * @return a completed or failed {@link Future}
     */
    static Future<Void> requireRowUpdated(int rowCount, String context) {
        return rowCount == 0
                ? Future.failedFuture(new WorkflowConflictException("Optimistic concurrency conflict: " + context))
                : Future.succeededFuture();
    }

    /**
     * Verifies that the plan hash stored on the instance matches the hash of the currently-resolved
     * plan.
     *
     * <p>A mismatch indicates that the plan content changed between deployments without a version
     * bump — a packaging error. The method throws {@link WorkflowPlanHashDriftException} so the
     * caller can wrap it in a {@link Future#failedFuture(Throwable)} and propagate it.
     *
     * @param inst the workflow instance whose {@code planHash} is to be checked
     * @param rw the resolved runtime workflow whose {@code planHash} is compared to the stored hash
     * @throws WorkflowPlanHashDriftException if the hashes do not match
     */
    static void requirePlanHashMatches(WorkflowInstance inst, RuntimeWorkflow rw) {
        String stored = inst.planHash();
        String current = rw.plan().planHash();
        if (!stored.equals(current)) {
            throw new WorkflowPlanHashDriftException(
                    inst.definitionId(), inst.definitionVersion(), inst.id(), stored, current);
        }
    }

    /**
     * Package-visible bridge so a dialect recovery service can apply the same
     * plan-hash drift guard as the engine's own entry points.
     *
     * <p>Delegates to {@link #requirePlanHashMatches(WorkflowInstance, RuntimeWorkflow)}.
     *
     * @param inst the workflow instance whose {@code planHash} is to be checked
     * @param rw the resolved runtime workflow whose {@code planHash} is compared to the stored hash
     * @throws WorkflowPlanHashDriftException if the hashes do not match
     */
    static void requirePlanHashMatchesForRecovery(WorkflowInstance inst, RuntimeWorkflow rw) {
        requirePlanHashMatches(inst, rw);
    }

    // --- Command history correlation stitching (Contract Appendix C5) ---

    /**
     * Resolves the {@code commandCorrelationId} to stitch onto a command history payload
     * constructed inside a bound drive.
     *
     * <p>Reads whatever {@link CorrelationContext} is currently bound to the ambient execution
     * context at the call site — the same value the binder ({@link WorkflowContextBinder}) or the
     * branch-transition carrier seam installed for this drive. There is no special-casing between
     * instance-path binder drives and branch/engine-internal construction sites: whichever
     * correlation is bound at the point of construction is the truthful value (PRD
     * FR-WF-CTX-050/051).
     *
     * @return the bound correlation id value, or {@code null} when no {@link CorrelationContext} is
     *     bound to the current execution context
     */
    @Nullable
    static String commandCorrelationId() {
        return ContextValues.current(CorrelationContext.class)
                .map(c -> c.correlationId().value())
                .orElse(null);
    }

    /**
     * Resolves the {@code commandCorrelationId} to stitch onto a branch-owned task-completion
     * history payload, falling back to the owning {@link WorkflowInstance}'s captured start-time
     * metadata when no correlation is bound to the ambient execution context.
     *
     * <p>Branch-owned task completion (unlike the instance-path binder drives {@link
     * #commandCorrelationId()} serves) does not bind a {@link CorrelationContext} for the duration
     * of the drive — the C2 bind-once rule keeps binding exclusive to the instance path. Without a
     * fallback, a branch-owned completion recorded with no ambient correlation would always record
     * {@code null} even when the instance was started with a durable correlation context. This
     * overload closes that gap: it first tries the ambient value (identical to
     * {@link #commandCorrelationId()} — ambient, when present, is authoritative), and only when that
     * is absent does it read the {@code correlation} namespace of {@code inst.metadata()} and
     * extract the {@code correlationId.value} field (mirroring the field the correlation module's
     * durable envelope writes under the {@code correlation} namespace).
     *
     * <p>The nested read is best-effort: {@link DurableMetadata#fromJson} only validates that the
     * {@code correlation} namespace <em>body</em> is a JSON object, not the shape of fields nested
     * within it — so a corrupted or hand-edited persisted row can carry a {@code correlationId}
     * field that is not a JSON object (or a {@code value} field that is not a string). Each nested
     * read is type-checked before use; a malformed nested shape returns {@code null} rather than
     * throwing, matching the fail-soft posture of this fallback (a stitched correlation id is a
     * best-effort convenience, not a required field for completion to proceed).
     *
     * @param inst the owning workflow instance, whose {@link WorkflowInstance#metadata()} is
     *     consulted as the fallback source when no correlation is bound ambiently
     * @return the ambient bound correlation id value; if absent, the correlation id value captured
     *     in the instance's start-time metadata; or {@code null} if neither is present or the
     *     nested shape is malformed
     */
    @Nullable
    static String commandCorrelationId(WorkflowInstance inst) {
        String ambient = commandCorrelationId();
        if (ambient != null) {
            return ambient;
        }
        DurableMetadata metadata = inst.metadata();
        if (metadata == null) {
            return null;
        }
        return metadata.body("correlation")
                .map(body -> body.getValue("correlationId"))
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .map(id -> id.getValue("value"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
    }

    // --- Branch-token helpers ---

    /**
     * Builds a branch-token snapshot with the wait fields cleared and the current step advanced to
     * {@code nextStepId} with status {@link BranchStatus#RUNNING}.
     *
     * <p>This is the common {@code RUNNING}-resume case shared by
     * {@link TimerLifecycleService}'s branch timer-fire paths and
     * {@link TaskLifecycleService}'s branch task paths.
     *
     * @param branch     the current branch token
     * @param nextStepId the step id to advance to
     * @param now        the update timestamp
     * @return a new {@link BranchToken} with status {@code RUNNING}, wait fields null, and
     *     {@code currentStepId} set to {@code nextStepId}
     */
    static BranchToken clearBranchWait(BranchToken branch, String nextStepId, Instant now) {
        return branch.withClearedWait(BranchStatus.RUNNING, nextStepId, now);
    }
}
