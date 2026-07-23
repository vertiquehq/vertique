// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
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
     * Migration-specific optimistic update that re-pins the instance to a new definition version and
     * plan hash, clears all wait and error fields, forces a non-terminal {@code RUNNING} state, and
     * writes the migrated state.
     *
     * @param updated         the post-migration workflow instance snapshot; must carry the new
     *                        definition version, plan hash, version, current step id, and state
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
     * Loads a workflow instance by id and acquires a row lock ({@code SELECT ... FOR UPDATE}) within
     * the given transaction.
     *
     * @param id the workflow instance id to look up
     * @param tx the active transaction to use for the locking read
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findByIdForUpdate(WorkflowInstanceId id, TX tx);

    /**
     * Loads a workflow instance by definition id and business key within the given transaction.
     * Returns empty immediately when {@code bk} is {@code null}.
     *
     * @param defId the definition id scope for the business key
     * @param bk    the business key value to look up; may be {@code null}
     * @param tx    the active transaction to use
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    Future<Optional<WorkflowInstance>> findByBusinessKey(String defId, String bk, TX tx);

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
