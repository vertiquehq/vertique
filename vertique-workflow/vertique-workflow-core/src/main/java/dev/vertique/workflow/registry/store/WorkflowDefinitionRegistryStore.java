// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;
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
     * @param key tenant-scoped definition key to find
     * @return a future containing the metadata record, or an empty value when no definition has that key
     */
    Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key);

    /**
     * Finds source text for a document definition by key.
     *
     * @param key tenant-scoped definition key to find
     * @return a future containing the document, or an empty value when the key has no document definition
     */
    Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key);

    /**
     * Lists metadata records selected by tenant scope and optional statuses; source text is not included.
     *
     * @param filter tenant scope and optional definition statuses to select
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
     * Claims an eligible candidate for activation, or reclaims this key's stale activation attempt, and
     * returns a unique token identifying the attempt.
     *
     * @param key tenant-scoped definition key to claim
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
     * Recomputes active membership for one exact tenant owner and definition id. Only ACTIVE and
     * SUPERSEDED rows participate; the highest version becomes ACTIVE and lower eligible versions
     * become SUPERSEDED. Other statuses, owners, and definition ids are left unchanged.
     *
     * @param tenantKey the exact owner, or {@code null} for the global owner
     * @param definitionId the definition id to recompute
     * @return a future completed after the store-owned transaction commits
     */
    Future<Void> recomputeActive(@Nullable String tenantKey, String definitionId);
}
