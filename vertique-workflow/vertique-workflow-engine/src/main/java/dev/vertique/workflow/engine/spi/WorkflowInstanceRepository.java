// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Repository SPI for {@link WorkflowInstance} persistence.
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this
 * interface without binding to a specific database client. Implementations supply a concrete
 * {@code TX} (for example {@code io.vertx.sqlclient.SqlClient} for a SQL dialect adapter).
 *
 * <p>Optimistic concurrency is enforced via a {@code version} column: {@link #updateOptimistic}
 * returns {@code 0} when the expected version no longer matches (a stale write).
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface WorkflowInstanceRepository<TX> {

    // --- Write operations ---

    /**
     * Inserts a new workflow instance row within the given transaction.
     *
     * @param inst the workflow instance to persist
     * @param tx   the active transaction to use for the insert
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insert(WorkflowInstance inst, TX tx);

    /**
     * Performs an optimistic-concurrency update of an existing workflow instance row.
     *
     * <p>The update is conditioned on {@code version = expectedVersion}. If the row's version has
     * already been incremented by a concurrent transaction, the update matches 0 rows and this
     * method returns {@code 0}.
     *
     * @param updated         the updated workflow instance snapshot to write
     * @param expectedVersion the version value that must be present in the DB row for the update to
     *                        succeed
     * @param tx              the active transaction to use
     * @return a {@link Future} containing {@code 1} if the update succeeded, {@code 0} if the
     *         version was stale
     */
    Future<Integer> updateOptimistic(WorkflowInstance updated, long expectedVersion, TX tx);

    /**
     * Migration-specific optimistic update that re-pins the instance to a new definition scope, version
     * and plan hash, clears all wait and error fields, forces a non-terminal {@code RUNNING} state, and
     * writes the migrated state.
     *
     * @param updated         the post-migration workflow instance snapshot; must carry the new
     *                        definition scope key, definition version, plan hash, version, current step id, and state
     * @param expectedVersion the version value that must be present in the DB row for the update to
     *                        succeed (optimistic concurrency guard)
     * @param tx              the active transaction to use
     * @return a {@link Future} containing {@code 1} if the update succeeded, {@code 0} if the
     *         expected version no longer matches (stale write)
     */
    Future<Integer> migratePinAndState(WorkflowInstance updated, long expectedVersion, TX tx);

    // --- Read operations ---

    /**
     * Loads a workflow instance by id without row locking.
     *
     * @param id the workflow instance id to look up
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findById(WorkflowInstanceId id);

    /**
     * Loads a workflow instance by id within the supplied transaction (no row lock). Used for
     * snapshot-consistent reads where the caller has already opened a transaction.
     *
     * @param id the workflow instance id to look up
     * @param tx the active transaction or pooled connection to use for the read
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findById(WorkflowInstanceId id, TX tx);

    /**
     * Reads the contract name that the durable definition registry stores for a definition, within the supplied
     * transaction. The read uses the caller's transaction and takes no further connection, so a caller that holds
     * one can ask without waiting on the pool.
     *
     * <p>The default returns empty, as an implementation without access to the definition rows has no answer; a
     * caller that needs the contract then refuses instead of guessing.
     *
     * @param key the full definition key
     * @param tx the active transaction or pooled connection to use for the read
     * @return a {@link Future} containing the stored contract name, or {@link Optional#empty()} when there is no row
     */
    default Future<Optional<String>> findDefinitionContract(DefinitionKey key, TX tx) {
        return Future.succeededFuture(Optional.empty());
    }

    /**
     * Loads a workflow instance by id and acquires a row lock ({@code SELECT ... FOR UPDATE}) within
     * the given transaction.
     *
     * @param id the workflow instance id to look up
     * @param tx the active transaction to use for the locking read
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findByIdForUpdate(WorkflowInstanceId id, TX tx);

    /**
     * Loads a workflow instance by start scope, definition id and business key within the given
     * transaction. Returns empty immediately when {@code bk} is {@code null}.
     *
     * @param scopeKey the start scope the instance was created in
     * @param defId the definition id scope for the business key
     * @param bk    the business key value to look up; may be {@code null}
     * @param tx    the active transaction to use
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findByBusinessKey(String scopeKey, String defId, String bk, TX tx);

    /**
     * Finds the live instances that carry a business key and can accept a signal addressed to the instance, across
     * scopes and definitions, within the given transaction. An instance matches when the definition version it is
     * pinned to names {@code contractName}, when that version declares {@code signalName} among the signals the
     * instance itself awaits (a definition row that holds no list of signal names counts as declaring it, as recording
     * does), and when the instance has not ended: any status other than {@code COMPLETED}, {@code COMPENSATED},
     * {@code CANCELLED} and {@code EXPIRED} is live. A signal that a version awaits only inside a fork branch does not
     * match.
     *
     * <p>At most two instances are returned, which is enough to tell no match, one match and several matches apart.
     * The business key is a lookup value and is never echoed.
     *
     * @param contractName the name of the contract the pinned definition must implement
     * @param signalName the signal the version must declare for the instance
     * @param businessKey the business key the instance carries
     * @param definitionId optional definition id the instance must be of, or {@code null} for any
     * @param scopeKey optional start scope the instance must have been created in, or {@code null} for any
     * @param tx the active transaction to use
     * @return a {@link Future} of at most two live instances; the default implementation returns a failed future
     *     holding an {@link UnsupportedOperationException}, for a repository that does not support signals delivered by
     *     business key
     */
    default Future<List<WorkflowInstance>> findLiveByBusinessKey(
            String contractName,
            String signalName,
            String businessKey,
            @Nullable String definitionId,
            @Nullable String scopeKey,
            TX tx) {
        return Future.failedFuture(new UnsupportedOperationException(
                "This instance repository does not support signals delivered by business key"));
    }

    /**
     * Returns a keyset-paginated, filtered list of workflow instances. Executes read-only (no row
     * locking).
     *
     * @param query  the query criteria to apply; fields set to {@code null} are ignored
     * @param cursor the page cursor; pass {@link PageCursor#first(int)} for the first page and
     *               {@link PageCursor#fromToken(String)} for subsequent pages
     * @return a {@link Future} containing the keyset-paginated result
     * @throws NullPointerException if {@code cursor} is {@code null} (raised synchronously by the
     *                              underlying paged-query builder)
     */
    Future<PagedResult<WorkflowInstance>> findFiltered(WorkflowInstanceQuery query, PageCursor cursor);
}
