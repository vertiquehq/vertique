// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;
import java.util.List;
import java.util.Optional;

/** SQL-free transactional registry. Mutations serialize by definition id and fence activation tokens. */
public interface WorkflowDefinitionRegistryStore {
    /**
     * Inserts a candidate definition and its source document together.
     *
     * @param record metadata for the candidate definition
     * @param document source text associated with the definition
     * @return a future completed after the candidate and document are persisted atomically
     */
    Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document);

    /**
     * Finds definition metadata by key without loading document source text.
     *
     * @param key scoped definition key to find
     * @return a future containing the metadata record, or an empty value when no definition has that key
     */
    Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key);

    /**
     * Finds source text for a document definition by key.
     *
     * @param key scoped definition key to find
     * @return a future containing the document, or an empty value when the key has no document definition
     */
    Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key);

    /**
     * Lists metadata records selected by optional scope, definition id and statuses; source text is not included.
     *
     * @param filter optional scope, definition id and definition statuses to select
     * @return a future containing the matching metadata records
     */
    Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter);

    /**
     * Reconciles a document definition and its source text with the persisted registry. An absent key is
     * inserted as a superseded definition. For an existing document definition with a matching plan hash,
     * provenance is refreshed; hash drift, a kind mismatch, or an owner conflict is reported by the outcome.
     *
     * @param record metadata and provenance for the document definition
     * @param document source text associated with the definition
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    Future<ReconcileOutcome> upsertDocument(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document);

    /**
     * Reconciles a definition that the deployed code provides with the persisted registry, as startup does. The
     * record must be a default-scope {@code CODE} record without document text. An absent key is inserted as a
     * superseded definition, so that the active membership is decided afterwards by
     * {@link #recomputeActive}. For an existing code definition with the same plan hash, provenance is refreshed
     * and the outcome is {@code UNCHANGED}. When the plan hash differs, the store decides inside its own
     * transaction, under the definition id lock, whether any instance pinned to exactly this version is still
     * running, waiting or compensating, archived instances included: with none the stored hash is replaced
     * ({@code REHASHED}), otherwise the outcome is {@code DRIFT} and nothing changes. There is no way for the
     * caller to force a replacement. A row of the same key that is a document definition is reported as
     * {@code KIND_CONFLICT}, and another owner that holds the definition id as {@code OWNER_CONFLICT}. The
     * status of an existing row is never changed.
     *
     * @param record metadata and provenance for the code definition
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record);

    /**
     * Claims an eligible candidate for activation, or reclaims this key's stale activation attempt, and
     * returns a unique token identifying the attempt.
     *
     * @param key scoped definition key to claim
     * @return a future containing the token-fenced activation claim
     */
    Future<ActivationClaim> claim(DefinitionKey key);

    /**
     * Completes the current activation claim when its token still matches and no other owner holds the
     * definition id. The result is active unless a higher version for the same owner is already active,
     * in which case the result is superseded.
     *
     * @param claim token-fenced activation claim to complete
     * @return a future containing the resulting membership
     */
    Future<ActivationOutcome> complete(ActivationClaim claim);

    /**
     * Returns a matching activation attempt to candidate status. A claim with an obsolete or mismatched
     * token has no effect.
     *
     * @param claim token-fenced activation claim to release
     * @return a future completed when the matching claim is released
     */
    Future<Void> release(ActivationClaim claim);

    /**
     * Conditionally moves one definition from an expected status to a new status under the definition id
     * lock. The row must exist, be owned consistently with the other rows of the id, and currently have the
     * expected {@code from} status; otherwise the returned future fails with a
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException} naming the key and no row
     * changes. Only the transitions the store supports are accepted: {@code ACTIVE} to {@code DEPRECATED},
     * {@code SUPERSEDED} to {@code DEPRECATED} and {@code RETIRED} to {@code SUPERSEDED}. Any other pair is
     * refused as an invalid transition, and activation changes use only the claim, complete and release
     * operations. A change to {@code DEPRECATED} does not recompute or promote any other row, and every change
     * refreshes the status change time.
     *
     * <p>{@code RETIRED} to {@code SUPERSEDED} is the reinstatement primitive; the {@link WorkflowDefinitionLifecycle}
     * enforces the preconditions that an executable source is available and that its hash equals the recorded
     * one, and the store does not. In one exclusive transaction the
     * store checks that the row exists and is retired and that no other owner holds the id, marks the row
     * superseded, and then settles the active version of that owner and id over its active and superseded rows:
     * lower active rows are demoted before the highest version is promoted, so the reinstated row becomes
     * active when it is the highest and stays superseded otherwise. Deprecated rows stay excluded. A failure at
     * any point rolls back every change of the transaction, leaving the retired row and the previous active row
     * as they were. The failure is typed as an owner conflict when another owner holds the id.
     *
     * @param key scoped definition key to change
     * @param from the status the row is required to have
     * @param to the status to store
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to);

    /**
     * Retires one definition under the definition id lock. A {@code CANDIDATE} row is retired directly. A
     * {@code DEPRECATED} row is retired only after its cooling window, the configured staleness bound measured
     * from the status change time by the database clock, and only while no instance pinned to exactly this
     * definition version is still running, waiting or compensating, archived instances included. Every other
     * status is refused as an invalid transition. The checks run in that order and in the same transaction as the
     * change: a missing or ineligible row fails with
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException} reason
     * {@code INVALID_TRANSITION}, a row that is still cooling fails with reason {@code COOLING}, and a row that
     * is still pinned fails with reason {@code PINNED_INSTANCES}. A refusal changes no row, and a success changes
     * only this row's status and status change time.
     *
     * @param key scoped definition key to retire
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> retire(DefinitionKey key);

    /**
     * Resets the stale activation attempts of one definition id to candidate status, as startup does. Under the
     * definition id lock, every row of the id, whatever its owner, that is {@code ACTIVATING} and whose last
     * status change is older than twice the configured staleness bound, measured by the database clock after the
     * lock is held, becomes {@code CANDIDATE} with its activation token cleared and its status change time
     * refreshed. Younger activation attempts and rows in other statuses are not touched.
     *
     * @param definitionId the definition id whose stale activation attempts are reset
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> resetStaleActivating(String definitionId);

    /**
     * Recomputes active membership for one exact scope and definition id. Only ACTIVE and
     * SUPERSEDED rows participate; the highest version becomes ACTIVE and lower eligible versions
     * become SUPERSEDED. Other statuses, scopes, and definition ids are left unchanged. The
     * returned future fails with {@link IllegalArgumentException} for an invalid scope key or
     * definition id, before any storage access.
     *
     * @param scopeKey the exact scope; {@link WorkflowScopes#DEFAULT} for the default scope
     * @param definitionId the definition id to recompute
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> recomputeActive(String scopeKey, String definitionId);
}
