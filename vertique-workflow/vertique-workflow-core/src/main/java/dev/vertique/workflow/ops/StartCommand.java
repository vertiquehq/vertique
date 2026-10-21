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
 * version. When non-null the engine resolves that exact version; a retry whose explicit version, or
 * whose requested target scope, differs from the definition the existing instance is pinned to is
 * rejected with {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException}. A retry
 * without a requested version always returns the existing instance (lenient semantics - active-version
 * bumps must not retroactively conflict with a successful prior start). The engine looks for the
 * existing instance by start scope, definition id and idempotency key before it resolves any version.
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
 *     start scope and definition id; no random fallback
 * @param businessKey optional application-defined unique key within the start scope and definition id;
 *     stored on the instance and used for lookup
 * @param subjectRef optional reference to the domain entity this workflow acts on
 * @param requestedDefinitionVersion optional explicit version pin; when non-null the engine starts
 *     exactly this version and enforces idempotency-conflict detection on version mismatch
 * @param startScope optional scope the instance starts in; {@code null} lets the engine resolve
 *     the start scope, which is the default scope
 * @param requestedTargetScope optional scope whose definition version the start targets together with
 *     {@code requestedDefinitionVersion}; only the start scope and the default scope can be named;
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

    private static final int MAX_SCOPE_KEY_CODE_POINTS = 255;

    /**
     * Validates the contract name and the scope keys.
     *
     * <p>A non-null {@code startScope} or {@code requestedTargetScope} must be nonblank, at most 255 code points
     * long and free of control characters and unpaired surrogates, the rules of a scope key. The message names
     * the field and never repeats the value.
     *
     * @throws IllegalArgumentException if {@code contractName} is {@code null} or blank, or a non-null scope key
     *     breaks the scope key rules
     */
    public StartCommand {
        if (contractName == null || contractName.isBlank()) {
            throw new IllegalArgumentException("contractName must not be blank");
        }
        requireScopeKey(startScope, "startScope");
        requireScopeKey(requestedTargetScope, "requestedTargetScope");
    }

    private static void requireScopeKey(@Nullable String scopeKey, String name) {
        if (scopeKey == null) {
            return;
        }
        if (scopeKey.isBlank() || scopeKey.codePointCount(0, scopeKey.length()) > MAX_SCOPE_KEY_CODE_POINTS) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most " + MAX_SCOPE_KEY_CODE_POINTS + " code points");
        }
        boolean unusable = scopeKey.codePoints()
                .anyMatch(cp ->
                        Character.isISOControl(cp) || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE));
        if (unusable) {
            throw new IllegalArgumentException(
                    name + " must not contain control characters or unpaired surrogate characters");
        }
    }
}
