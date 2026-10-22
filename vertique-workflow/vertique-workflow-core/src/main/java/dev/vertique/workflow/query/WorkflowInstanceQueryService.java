// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.query;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * Public-facing query service for workflow instances (cycle 5).
 *
 * <p>Exposes three operations:
 * <ul>
 *   <li>{@link #list(WorkflowInstanceQuery, PageCursor)} — keyset-paginated list of instances
 *       matching a {@link WorkflowInstanceQuery} filter.</li>
 *   <li>{@link #getById(WorkflowInstanceId)} — single-instance lookup by primary key.</li>
 *   <li>{@link #countNonTerminal(DefinitionKey)} — number of unfinished instances pinned to one
 *       definition version of one scope; {@link #countNonTerminal(String, long)} counts in the default
 *       scope.</li>
 * </ul>
 *
 * <p>History is intentionally <em>not</em> surfaced here. The cycle-4 ADR-0049 drew a deliberate
 * boundary between the internal {@code workflow_history} table (populated at every state mutation)
 * and the external durable event stream (published via the outbox relay). Surfacing history through
 * a public service would blur that boundary and couple downstream consumers to internal engine
 * details. Consumers that need the audit trail should subscribe to the durable workflow event
 * stream instead.
 *
 * <p>REST/management endpoints ({@code FR-WF-180}–{@code FR-WF-185}) remain deferred; cycle 5
 * ships the programmatic API only.
 *
 * <p>Implementations are expected to be {@code @Singleton} and must not block the Vert.x event
 * loop. All operations return {@link Future} values.
 */
public interface WorkflowInstanceQueryService {

    /**
     * Returns a keyset-paginated list of workflow instances matching the given query.
     *
     * <p>An empty {@link WorkflowInstanceQuery#none()} returns all live (non-archived) instances
     * in the default cursor order. Multiple non-null filter fields are combined as a conjunction
     * (AND). Set {@link WorkflowInstanceQuery#includeArchived()} to {@code true} to include
     * soft-deleted instances.
     *
     * @param query filter criteria; use {@link WorkflowInstanceQuery#none()} for an unfiltered list
     * @param cursor pagination cursor; use {@link PageCursor#first(int)} for the initial page
     * @return a {@link Future} that completes with the paged result, or fails if the query fails
     */
    Future<PagedResult<WorkflowInstance>> list(WorkflowInstanceQuery query, PageCursor cursor);

    /**
     * Returns the workflow instance with the given id, or an empty {@link Optional} if no instance
     * exists with that id.
     *
     * @param id the workflow instance id to look up
     * @return a {@link Future} that completes with an {@link Optional} containing the instance if
     *     found, or an empty {@link Optional} if not found; fails if the lookup fails
     */
    Future<Optional<WorkflowInstance>> getById(WorkflowInstanceId id);

    /**
     * Counts the instances pinned to exactly one definition version that have not finished: those running,
     * waiting or compensating, archived instances included. The count is a point-in-time read for reporting;
     * it does not guard a lifecycle change.
     *
     * <p>The default implementation fails the returned future with {@link UnsupportedOperationException}, so
     * existing implementors keep compiling and never silently report zero.
     *
     * @param definitionId the workflow definition id
     * @param version the exact definition version
     * @return a {@link Future} that completes with the number of non-terminal instances, or fails if the count
     *     fails or is unsupported
     */
    default Future<Long> countNonTerminal(String definitionId, long version) {
        return Future.failedFuture(new UnsupportedOperationException(
                getClass().getName() + " does not support the countNonTerminal operation"));
    }

    /**
     * Counts the instances pinned to exactly one definition version of one scope that are running, waiting or
     * compensating, archived instances included. Instances pinned to the same id and version in another scope
     * are not counted.
     *
     * <p>The default implementation fails the returned future with {@link UnsupportedOperationException}, so
     * existing implementors keep compiling and never silently report zero.
     *
     * @param key the scope, definition id and exact version of the pinned definition
     * @return a {@link Future} that completes with the number of non-terminal instances, or fails if the count
     *     fails or is unsupported
     */
    default Future<Long> countNonTerminal(DefinitionKey key) {
        return Future.failedFuture(new UnsupportedOperationException(
                getClass().getName() + " does not support the countNonTerminal operation"));
    }
}
