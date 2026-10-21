// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

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

    /** Rejection message for a decision payload. */
    static final String DECISION_REJECTION = "invalid decision payload";

    /** Rejection message for a start payload. */
    static final String START_REJECTION = "invalid start payload";

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
     * <p>Caller mistakes are reported as {@link WorkflowInvalidPayloadException} whose message and
     * details never carry a payload value, a payload type name, or a binder message. A payload
     * that cannot bind to the declared class retains the {@link DecodeException} as the cause.
     *
     * <p>This overload resolves the type name by class-loader lookup only, without initializing the
     * class.
     *
     * @param payload the raw payload from the command
     * @param payloadTypeName the type name declared in the plan decision
     * @return the coerced payload (may be {@code null} for Void decisions)
     * @throws WorkflowInvalidPayloadException if the payload violates the nullability rule or does
     *     not bind to the declared class
     * @throws WorkflowDefinitionException if the declared class cannot be resolved
     */
    static Object validateAndCoerceTaskPayload(Object payload, String payloadTypeName) {
        return validateAndCoerceTaskPayload(payload, payloadTypeName, new DefaultWorkflowTypeNames());
    }

    /**
     * Validates and coerces a decision payload, resolving the plan's type name through the type-name
     * index first.
     *
     * <p>The name comes from a registered plan, so it is either a logical name or the binary class
     * name of a row written before the class was named. When the index does not know it, the name is
     * resolved as a binary class name, exactly as before logical names existed, without initializing the
     * class; a name that resolves nowhere fails with a {@link WorkflowDefinitionException}. The nullability rules
     * and error reporting are those of {@link #validateAndCoerceTaskPayload(Object, String)}.
     *
     * @param payload the raw payload from the command
     * @param payloadTypeName the type name declared in the plan decision
     * @param typeNames the type-name index to resolve through
     * @return the coerced payload (may be {@code null} for Void decisions)
     * @throws WorkflowInvalidPayloadException if the payload violates the nullability rule or does
     *     not bind to the declared class
     * @throws WorkflowDefinitionException if the declared class cannot be resolved
     */
    static Object validateAndCoerceTaskPayload(Object payload, String payloadTypeName, WorkflowTypeNames typeNames) {
        // Special-case the Void / void markers BEFORE Class.forName: the primitive `void.class`
        // returns the literal name "void", which Class.forName rejects with ClassNotFoundException.
        // Without this short-circuit, a developer-declared `decision("approve", void.class)` would
        // build, register, and dispatch — but every completion attempt would fail trying to
        // resolve "void" as a class name. Both `void.class` ("void") and `Void.class`
        // ("java.lang.Void") are accepted as no-payload decisions.
        if ("void".equals(payloadTypeName) || "java.lang.Void".equals(payloadTypeName)) {
            if (payload != null) {
                throw invalidPayload(
                        DECISION_REJECTION,
                        "payload is not accepted for a decision without a payload type",
                        "payload",
                        null);
            }
            return null;
        }

        Class<?> payloadClass = typeNames.find(payloadTypeName).orElse(null);
        if (payloadClass == null) {
            // Lookup only: the class is resolved without running its static initializer.
            try {
                payloadClass = Class.forName(payloadTypeName, false, WorkflowPayloads.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                throw new WorkflowDefinitionException(
                        "Decision payloadTypeName '" + payloadTypeName + "' cannot be resolved: " + e.getMessage());
            }
        }

        if (payload == null) {
            throw invalidPayload(DECISION_REJECTION, "payload is required", "payload", null);
        }
        try {
            return coercePayload(payload, payloadClass);
        } catch (DecodeException e) {
            throw bindingFailure(e);
        }
    }

    /**
     * Validates the start payload against the definition's declared start payload type and coerces
     * it if necessary.
     *
     * <p>Payload-nullability rule:
     * <ul>
     *   <li>If {@code startPayloadType} is {@code void.class} or {@code Void.class}, the payload
     *       MUST be {@code null}.</li>
     *   <li>If {@code startPayloadType} is {@code null}, the payload is handled exactly as
     *       {@link #coercePayload(Object, Class)} does.</li>
     *   <li>Otherwise the payload MUST be non-null and is coerced via JSON round-trip if it is not
     *       already an instance of the declared class.</li>
     * </ul>
     *
     * <p>Caller mistakes are reported as {@link WorkflowInvalidPayloadException} whose message and
     * details never carry a payload value, a payload type name, or a binder message. A payload that
     * cannot bind to the declared class retains the {@link DecodeException} as the cause.
     *
     * @param payload the raw payload from the start command; may be null
     * @param startPayloadType the declared start payload type; may be null
     * @return the coerced payload (may be {@code null} for void start types)
     * @throws WorkflowInvalidPayloadException if the payload violates the nullability rule or does
     *     not bind to the declared class
     */
    static Object validateAndCoerceStartPayload(Object payload, Class<?> startPayloadType) {
        if (startPayloadType == null) {
            return coercePayload(payload, null);
        }
        if (startPayloadType == void.class || startPayloadType == Void.class) {
            if (payload != null) {
                throw invalidPayload(
                        START_REJECTION, "payload is not accepted for a start type without a payload", "payload", null);
            }
            return null;
        }
        if (payload == null) {
            throw invalidPayload(START_REJECTION, "payload is required", "payload", null);
        }
        try {
            return coercePayload(payload, startPayloadType);
        } catch (DecodeException e) {
            throw invalidPayload(START_REJECTION, "payload does not bind to the declared type", "binding", e);
        }
    }

    /**
     * Builds the value-free rejection for a decision name that the pinned plan does not declare for
     * the task step. Neither the message nor the detail carries the decision name.
     *
     * @return the exception to fail the completion with
     */
    static WorkflowInvalidPayloadException unknownDecision() {
        return invalidPayload(DECISION_REJECTION, "decision is not declared for this task step", "decision", null);
    }

    /**
     * Builds the value-free rejection for a payload that does not bind to its declared class. The
     * binder's exception is retained as the cause only; its message never reaches the result.
     *
     * @param cause the binder failure
     * @return the exception to fail the command with
     */
    static WorkflowInvalidPayloadException bindingFailure(DecodeException cause) {
        return invalidPayload(DECISION_REJECTION, "payload does not bind to the declared type", "binding", cause);
    }

    private static WorkflowInvalidPayloadException invalidPayload(
            String message, String detailMessage, String detailType, @Nullable Throwable cause) {
        List<ViolationDetail> details = List.of(ViolationDetail.unlocated(detailMessage, detailType));
        return cause == null
                ? new WorkflowInvalidPayloadException(message, details)
                : new WorkflowInvalidPayloadException(message, details, cause);
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
