// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.types.ScopeKeyRules;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * The current version of one definition id in one scope: a start in {@code scopeKey} without a version runs
 * version {@code version} of the definition that {@code targetScopeKey} owns. A scope without a record follows the
 * default scope.
 *
 * <p>The scope key, the target scope key and the definition id follow the rules of {@link DefinitionKey}; the
 * version must be positive. The target scope is the scope itself or the default scope, and the default scope may
 * target only itself. A missing component is rejected with {@link NullPointerException} and any other value that
 * breaks a rule with {@link IllegalArgumentException}; the messages name the field, never its value.
 *
 * @param scopeKey the scope whose current version this record names; {@link WorkflowScopes#DEFAULT} for the default
 *     scope
 * @param definitionId the workflow definition id
 * @param targetScopeKey the scope that owns the named version: {@code scopeKey} itself or the default scope
 * @param version the positive version, in the target scope, that a start without a version runs
 * @param activatedAt the time the record was written
 * @param activatedBy who wrote the record, which {@code activatedAt} dates; {@code null} when it was not attributed or
 *     when the read that produced this record does not select it (the start selection does not).
 *     The registry keeps only the current record, never a history of activations.
 */
public record DefinitionActivation(
        String scopeKey,
        String definitionId,
        String targetScopeKey,
        long version,
        Instant activatedAt,
        @Nullable DefinitionChangeActor activatedBy) {

    /**
     * Constructs a record with no attribution of who wrote it.
     *
     * @param scopeKey the scope whose current version this record names
     * @param definitionId the workflow definition id
     * @param targetScopeKey the scope that owns the named version
     * @param version the positive version that a start without a version runs
     * @param activatedAt the time the record was written
     */
    public DefinitionActivation(
            String scopeKey, String definitionId, String targetScopeKey, long version, Instant activatedAt) {
        this(scopeKey, definitionId, targetScopeKey, version, activatedAt, null);
    }

    public DefinitionActivation {
        ScopeKeyRules.require(scopeKey, "scopeKey");
        DefinitionValues.identifier(definitionId, "definitionId", 255);
        ScopeKeyRules.require(targetScopeKey, "targetScopeKey");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (!targetScopeKey.equals(scopeKey) && !targetScopeKey.equals(WorkflowScopes.DEFAULT))
            throw new IllegalArgumentException("targetScopeKey must be the scope itself or the default scope");
        if (scopeKey.equals(WorkflowScopes.DEFAULT) && !targetScopeKey.equals(WorkflowScopes.DEFAULT))
            throw new IllegalArgumentException("the default scope may target only itself");
    }
}
