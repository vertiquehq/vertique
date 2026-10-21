// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/** SQL-free transactional registry. Mutations serialize by definition id and fence publication tokens. */
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
     * Reconciles a document definition and its source text with the persisted registry, without activating it.
     * Equivalent to {@link #upsertDocument(WorkflowDefinitionRecord, WorkflowDefinitionDocument, boolean)} with
     * {@code activateDefault} false.
     *
     * @param record metadata and provenance for the document definition
     * @param document source text associated with the definition
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    default Future<ReconcileOutcome> upsertDocument(
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        return upsertDocument(record, document, false);
    }

    /**
     * Reconciles a document definition and its source text with the persisted registry. An absent key is
     * inserted as an available definition. For an existing document definition with a matching plan hash,
     * provenance is refreshed; hash drift or a kind mismatch is reported by the outcome.
     *
     * <p>When {@code activateDefault} is true and, only then, the outcome is {@code INSERTED}, the same transaction
     * and the same definition id lock also write the default scope's activation record of the id to this version,
     * unless that record already names a higher version. A failure of either write rolls back both, so the version
     * is never available without the activation its first publication owed. Any other outcome writes no
     * activation record.
     *
     * @param record metadata and provenance for the document definition
     * @param document source text associated with the definition
     * @param activateDefault whether a first publication also becomes the default scope's current version
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    Future<ReconcileOutcome> upsertDocument(
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault);

    /**
     * Reconciles a definition that the deployed code provides with the persisted registry, without activating it.
     * Equivalent to {@link #upsertCode(WorkflowDefinitionRecord, boolean)} with {@code activateDefault} false.
     *
     * @param record metadata and provenance for the code definition
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    default Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
        return upsertCode(record, false);
    }

    /**
     * Reconciles a definition that the deployed code provides with the persisted registry, as startup does. The
     * record must be a default-scope {@code CODE} record without document text. An absent key is inserted as an
     * available definition. For an existing code definition with the same plan hash, provenance is refreshed
     * and the outcome is {@code UNCHANGED}. When the plan hash differs, the store decides inside its own
     * transaction, under the definition id lock, whether any instance pinned to exactly this version is still
     * running, waiting or compensating, archived instances included: with none the stored hash is replaced
     * ({@code REHASHED}), otherwise the outcome is {@code DRIFT} and nothing changes. There is no way for the
     * caller to force a replacement. A row of the same key that is a document definition is reported as
     * {@code KIND_CONFLICT}. The status of an existing row is never changed.
     *
     * <p>When {@code activateDefault} is true and, only then, the outcome is {@code INSERTED}, the same transaction
     * and the same definition id lock also write the default scope's activation record of the id to this version,
     * unless that record already names a higher version. A failure of either write rolls back both, so the version
     * is never available without the activation its first publication owed. Any other outcome writes no
     * activation record.
     *
     * @param record metadata and provenance for the code definition
     * @param activateDefault whether a first publication also becomes the default scope's current version
     * @return a future containing the reconciliation outcome after the transaction completes
     */
    Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault);

    /**
     * Claims an eligible candidate for publication, or reclaims this key's stale publication attempt, and
     * returns a unique token identifying the attempt.
     *
     * @param key scoped definition key to claim
     * @return a future containing the token-fenced publication claim
     */
    Future<ActivationClaim> claim(DefinitionKey key);

    /**
     * Completes the current publication claim when its token still matches: the claimed row becomes
     * {@code AVAILABLE} and its token is cleared. No other row changes, so any number of versions and scopes of one
     * id stay available together.
     *
     * @param claim token-fenced publication claim to complete
     * @return a future completed when the claimed row is available
     */
    Future<Void> complete(ActivationClaim claim);

    /**
     * Returns a matching publication attempt to candidate status. A claim with an obsolete or mismatched
     * token has no effect.
     *
     * @param claim token-fenced publication claim to release
     * @return a future completed when the matching claim is released
     */
    Future<Void> release(ActivationClaim claim);

    /**
     * Conditionally moves one definition from an expected status to a new status under the definition id
     * lock. The row must exist and currently have the expected {@code from} status; otherwise the returned future
     * fails with a {@link dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException} naming the key and
     * no row changes. Only the transitions the store supports are accepted: {@code AVAILABLE} to
     * {@code DEPRECATED}, {@code DEPRECATED} to {@code AVAILABLE} and {@code RETIRED} to {@code AVAILABLE}. Any
     * other pair is refused as an invalid transition before any statement is issued, and publication changes use
     * only the claim, complete and release operations. A change affects only the addressed row and refreshes its
     * status change time.
     *
     * <p>A transition to {@code DEPRECATED} of a default-scope row is refused, in the same transaction and with the
     * same {@code INVALID_TRANSITION}, while the default scope's activation record of the id names that version.
     *
     * <p>The last two are the reinstatement primitives; the {@link WorkflowDefinitionLifecycle} enforces the
     * preconditions that an executable source is available and that its hash equals the recorded one, and the store
     * does not.
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
     * is still pinned fails with reason {@code PINNED_INSTANCES}. A default-scope row that the default scope's
     * activation record of the id names is refused as an invalid transition before those checks. A refusal changes
     * no row, and a success changes only this row's status and status change time.
     *
     * @param key scoped definition key to retire
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> retire(DefinitionKey key);

    /**
     * Resets the stale publication attempts of one definition id to candidate status, as startup does. Under the
     * definition id lock, every row of the id that is {@code PUBLISHING} and whose last status change is older than
     * twice the configured staleness bound, measured by the database clock after the lock is held, becomes
     * {@code CANDIDATE} with its claim token cleared and its status change time refreshed. Younger publication
     * attempts and rows in other statuses are not touched.
     *
     * @param definitionId the definition id whose stale publication attempts are reset
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> resetStalePublishing(String definitionId);

    /**
     * Writes the scope's activation record of the definition id, replacing an earlier one. Under the definition id
     * lock and in the same transaction as the write, the target row must exist and be {@code AVAILABLE};
     * otherwise the returned future fails with a
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException} reason
     * {@code INVALID_TRANSITION} naming the target key, and nothing changes. No definition row changes.
     *
     * @param activation the record to write
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> putActivation(DefinitionActivation activation);

    /**
     * Deletes the scope's activation record of the definition id under the definition id lock. An absent record is
     * accepted without a change.
     *
     * @param scopeKey the scope whose record is deleted
     * @param definitionId the definition id
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> removeActivation(String scopeKey, String definitionId);

    /**
     * Lists activation records ordered by definition id and then scope key.
     *
     * @param scopeKey the exact scope whose records to list, or {@code null} for every scope
     * @return a future containing the matching records
     */
    Future<List<DefinitionActivation>> listActivations(@Nullable String scopeKey);
}
