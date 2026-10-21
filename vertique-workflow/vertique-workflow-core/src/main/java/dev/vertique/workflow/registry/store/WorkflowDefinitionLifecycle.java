// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;

/** Durable lifecycle operations, supplied by the workflow engine. */
public interface WorkflowDefinitionLifecycle {
    /**
     * Compiles the stored candidate, verifies its hash and publishes it through a fenced claim, so that the
     * version becomes available. A version that is already available is accepted without a change, and publishing
     * does not change any other version or scope of the id.
     */
    Future<Void> publish(DefinitionKey key);

    /**
     * Deprecates an available definition. The durable status is committed first; only after that
     * commit does the local registry mark the version deprecated, so current resolution skips it and explicit
     * new starts and migration targets are refused while pinned instances keep running. A definition that is
     * already deprecated is accepted, which re-applies the local mark. Any other status fails typed.
     * The local mark is a no-op when the version is not registered on this node, and a registry that does
     * not support deprecation fails the call after the durable commit, leaving the committed status in
     * place. Deprecating a version does not change any other version.
     */
    Future<Void> deprecate(DefinitionKey key);

    /**
     * Retires a candidate, or a deprecated definition once it has cooled and no instance is pinned to it. The
     * durable status is committed first, atomically with the cooling and pin checks; a refusal fails the future
     * typed and changes nothing, durably or locally. Only after the commit does the local registry drop the
     * version, and only when the registration there is the one this lifecycle published
     * for the same key. A registration that the lifecycle did not publish, or that a direct registry call has
     * since replaced, makes the call fail as an invalid transition before any durable change, and is never
     * removed.
     */
    Future<Void> retire(DefinitionKey key);

    /**
     * Reinstates a retired or deprecated version, which becomes available. A retired code version needs the code
     * the application deployed; a retired document version is recompiled from its stored text, whose hash must
     * equal the recorded one. The executable registration is published locally first, and then the durable change
     * is committed as one transaction that returns the row to service. When the durable change fails, only the
     * registration this call published is removed, and a registration that existed before the call stays. A
     * deprecated version is still registered, so its durable status is committed and the local deprecation mark
     * is cleared without publishing the source again. The future fails typed with {@code CODE_UNAVAILABLE} when
     * the code is not deployed, {@code DRIFT} when the source no longer compiles to the recorded hash, and
     * {@code LOADER_MISSING} when no single loader supports a document's format or its stored text is missing,
     * and {@code INVALID_TRANSITION} when the version is neither retired nor deprecated or a registration this
     * lifecycle did not publish stands in the way.
     */
    Future<Void> reinstate(DefinitionKey key);

    /**
     * Makes a version the current version of the definition id in a scope: a start in that scope without a version
     * then runs it, replacing the scope's earlier record. The target scope is the scope itself or the default
     * scope, and the default scope may target only itself; any other combination fails the future with
     * {@link IllegalArgumentException} before storage is read. The target version must exist and be
     * {@code AVAILABLE} in the target scope, checked in the same transaction as the write under the definition id
     * lock, otherwise the future fails with {@code INVALID_TRANSITION} naming the target key. Activating changes no
     * definition row. Without a durable registry the record lives only in this node's view and is lost on restart.
     *
     * @param scopeKey the scope whose current version is set
     * @param definitionId the definition id
     * @param targetScopeKey the scope that owns the version: {@code scopeKey} itself or the default scope
     * @param version the version to run
     * @return a future completed after the record is written
     */
    Future<Void> activate(String scopeKey, String definitionId, String targetScopeKey, long version);

    /**
     * Clears the default scope's record of the definition id, which is the only way to remove it: activating
     * another version replaces it, and a version it names cannot be deprecated or retired meanwhile. Afterwards
     * the version can be deprecated and retired, and scopes that follow the default start without a version only
     * while the default scope still holds a startable version. An absent record is accepted without a change. No
     * definition row changes and the records of other scopes are left alone. It takes no scope because only the
     * default scope's record can be cleared; a non-default scope returns to following the default with
     * {@link #follow(String, String)}.
     *
     * @param definitionId the definition id
     * @return a future completed after the record is removed
     */
    Future<Void> deactivateDefault(String definitionId);

    /**
     * Removes a non-default scope's record of the definition id, so the scope follows the default scope's current
     * version again. An absent record is accepted without a change. Following the default scope is meaningless and
     * fails the future with {@link IllegalArgumentException}.
     *
     * @param scopeKey the non-default scope that returns to following the default
     * @param definitionId the definition id
     * @return a future completed after the record is removed
     */
    Future<Void> follow(String scopeKey, String definitionId);

    /**
     * Lists the activation records, ordered by definition id and then scope. With a durable registry the records are
     * read from it; without one they are this node's view.
     *
     * @param scopeKey the scope whose records to list, or {@code null} for every scope
     * @return a future holding the matching records
     */
    Future<List<DefinitionActivation>> activations(@Nullable String scopeKey);
}
