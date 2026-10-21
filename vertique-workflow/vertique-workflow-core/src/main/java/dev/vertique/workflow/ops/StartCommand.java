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
 * version. When non-null the engine resolves that exact version and records the version
 * fingerprint in the dedup row; a retry with a different explicit version is rejected with
 * {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException}. A retry without a
 * requested version always returns the existing instance regardless of the stored fingerprint
 * (lenient semantics - active-version bumps must not retroactively conflict with a successful
 * prior start).
 *
 * <p>Clients build the command through
 * {@link dev.vertique.workflow.client.WorkflowClientBinding#startCommand}; application code that
 * starts an instance directly passes every component, using {@code null} for the optional ones.
 *
 * @param definitionId id of the workflow definition to instantiate
 * @param contractName logical name of the workflow contract the caller started through; must not
 *     be blank
 * @param payload start payload passed to the definition's {@code init} function to derive initial
 *     state
 * @param idempotencyKey required caller-supplied key that makes start idempotent within the
 *     definition scope; no random fallback
 * @param businessKey optional application-defined unique key within the definition scope; stored
 *     on the instance and used for lookup
 * @param subjectRef optional reference to the domain entity this workflow acts on
 * @param requestedDefinitionVersion optional explicit version pin; when non-null the engine starts
 *     exactly this version and enforces idempotency-conflict detection on version mismatch
 * @param startScope optional scope the instance starts in; {@code null} lets the engine resolve
 *     the start scope
 * @param requestedTargetScope optional scope whose definition version the start targets;
 *     {@code null} lets the engine resolve it
 */
public record StartCommand(
        String definitionId,
        String contractName,
        Object payload,
        String idempotencyKey,
        @Nullable String businessKey,
        @Nullable WorkflowSubjectRef subjectRef,
        @Nullable Long requestedDefinitionVersion,
        @Nullable String startScope,
        @Nullable String requestedTargetScope) {

    /**
     * Validates the contract name.
     *
     * @throws IllegalArgumentException if {@code contractName} is {@code null} or blank
     */
    public StartCommand {
        if (contractName == null || contractName.isBlank()) {
            throw new IllegalArgumentException("contractName must not be blank");
        }
    }
}
