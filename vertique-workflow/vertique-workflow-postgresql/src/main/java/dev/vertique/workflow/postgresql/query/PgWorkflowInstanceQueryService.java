// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.query;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.query.WorkflowInstanceQueryService;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * PostgreSQL-backed implementation of {@link WorkflowInstanceQueryService}.
 *
 * <p>Delegates directly to {@link PgWorkflowInstanceRepository}, which manages its own
 * connection pool internally. No wrapper transaction is opened here because the repository
 * methods are read-only and manage connections themselves — wrapping in a transaction that
 * nothing participates in would be wasteful and misleading.
 *
 * <p>This class is a {@code @Singleton} and must not block the Vert.x event loop. All methods
 * return {@link Future} values.
 */
@Singleton
public final class PgWorkflowInstanceQueryService implements WorkflowInstanceQueryService {

    private final PgWorkflowInstanceRepository repository;

    /**
     * Creates a new query service backed by the given repository.
     *
     * @param repository the instance repository used for filtered list and single-row lookup
     */
    @Inject
    public PgWorkflowInstanceQueryService(PgWorkflowInstanceRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates directly to {@link PgWorkflowInstanceRepository#findFiltered}.
     */
    @Override
    public Future<PagedResult<WorkflowInstance>> list(WorkflowInstanceQuery query, PageCursor cursor) {
        return repository.findFiltered(query, cursor);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates directly to {@link PgWorkflowInstanceRepository#findById(WorkflowInstanceId)}.
     */
    @Override
    public Future<Optional<WorkflowInstance>> getById(WorkflowInstanceId id) {
        return repository.findById(id);
    }
}
