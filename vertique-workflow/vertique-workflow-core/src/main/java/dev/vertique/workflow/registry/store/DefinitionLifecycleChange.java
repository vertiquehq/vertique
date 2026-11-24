// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.workflow.actor.WorkflowActor;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A change to the definition registry. The set of variants grows additively; a consumer must have a default branch.
 */
public sealed interface DefinitionLifecycleChange {

    /**
     * Identifies this change; minted once per call, so a failure of the same call carries the same id.
     *
     * @return the change id
     */
    UUID changeId();

    /** Who acted, or {@code null} when the call had no actor. */
    @Nullable
    WorkflowActor actor();

    /**
     * When the change was made: stamped when the call was made, together with the actor, and not when the change
     * committed.
     *
     * @return the instant
     */
    Instant occurredAt();

    /** A lifecycle operation on one definition version. */
    enum VersionOperation {
        PUBLISH,
        DEPRECATE,
        RETIRE,
        REINSTATE
    }

    /** An operation on which version is the default of a scope. */
    enum ActivationOperation {
        ACTIVATE,
        DEACTIVATE_DEFAULT,
        FOLLOW
    }

    /**
     * An operation on one definition version.
     *
     * @param changeId    the change id
     * @param operation   what was done
     * @param key         the version
     * @param actor       who acted, or {@code null} when the call had no actor
     * @param occurredAt  when
     */
    record VersionChange(
            UUID changeId,
            VersionOperation operation,
            DefinitionKey key,
            @Nullable WorkflowActor actor,
            Instant occurredAt)
            implements DefinitionLifecycleChange {

        /** Validates the required components. */
        public VersionChange {
            Objects.requireNonNull(changeId, "changeId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * An operation on the default of a scope.
     *
     * @param changeId       the change id
     * @param operation      what was done
     * @param scopeKey       the scope whose default changed
     * @param definitionId   the definition
     * @param targetScopeKey the scope that owns the version for {@link ActivationOperation#ACTIVATE}; the default scope
     *                       for {@link ActivationOperation#FOLLOW}, the scope followed; {@code null} for
     *                       {@link ActivationOperation#DEACTIVATE_DEFAULT}
     * @param version        the version made the default for {@link ActivationOperation#ACTIVATE}, otherwise
     *                       {@code null}
     * @param actor       who acted, or {@code null} when the call had no actor
     * @param occurredAt     when the call was made
     */
    record ActivationChange(
            UUID changeId,
            ActivationOperation operation,
            String scopeKey,
            String definitionId,
            @Nullable String targetScopeKey,
            @Nullable Long version,
            @Nullable WorkflowActor actor,
            Instant occurredAt)
            implements DefinitionLifecycleChange {

        /** Validates the required components. */
        public ActivationChange {
            Objects.requireNonNull(changeId, "changeId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(scopeKey, "scopeKey");
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
