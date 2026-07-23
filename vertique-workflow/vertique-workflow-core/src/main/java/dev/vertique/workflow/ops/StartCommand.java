// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;

/**
 * Command object passed to {@link WorkflowOperations#start(StartCommand)} to create a new workflow
 * instance.
 *
 * <p>The {@code idempotencyKey} is required and must be unique within the scope of
 * {@code definitionId}. Callers that retry after a transient failure MUST supply the same key so
 * that the engine returns the existing instance id rather than creating a duplicate.
 *
 * <p>The optional {@code requestedDefinitionVersion} pins the start to an explicit registered
 * version (FR-WF-DEF-069). When non-null the engine resolves that exact version and records the
 * version fingerprint in the dedup row; a retry with a different explicit version is rejected with
 * {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException} (FR-WF-DEF-070). A
 * retry without a requested version always returns the existing instance regardless of the stored
 * fingerprint (lenient semantics — active-version bumps must not retroactively conflict with a
 * successful prior start).
 *
 * @param definitionId id of the workflow definition to instantiate
 * @param payload start payload passed to the definition's {@code init} function to derive initial
 *     state
 * @param idempotencyKey required caller-supplied key that makes start idempotent within the
 *     definition scope; no random fallback
 * @param businessKey optional application-defined unique key within the definition scope; stored
 *     on the instance and used for lookup
 * @param subjectRef optional reference to the domain entity this workflow acts on
 * @param requestedDefinitionVersion optional explicit version pin; when non-null the engine starts
 *     exactly this version and enforces idempotency-conflict detection on version mismatch
 */
public record StartCommand(
        String definitionId,
        Object payload,
        String idempotencyKey,
        @Nullable String businessKey,
        @Nullable WorkflowSubjectRef subjectRef,
        @Nullable Long requestedDefinitionVersion) {

    /**
     * Backward-compatible 5-argument constructor that passes {@code null} for
     * {@code requestedDefinitionVersion}. Existing call sites continue to compile without change;
     * the engine applies current-version semantics.
     *
     * @param definitionId id of the workflow definition to instantiate
     * @param payload start payload
     * @param idempotencyKey caller-supplied idempotency key
     * @param businessKey optional business key
     * @param subjectRef optional subject reference
     */
    public StartCommand(
            String definitionId,
            Object payload,
            String idempotencyKey,
            @Nullable String businessKey,
            @Nullable WorkflowSubjectRef subjectRef) {
        this(definitionId, payload, idempotencyKey, businessKey, subjectRef, null);
    }
}
