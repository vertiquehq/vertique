// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.function.Supplier;

/**
 * Default implementation of {@link InboxService} backed by an {@link InboxRepository}.
 *
 * <p>Delegates inbox deduplication to {@link InboxRepository#tryInsert} and invokes the
 * caller-supplied work only when the message is new. Both operations share the caller's
 * transaction to guarantee atomicity between deduplication and business logic.
 *
 * <p>Repository failures from {@link InboxRepository#tryInsert} are wrapped by
 * {@link PgInboxOutboxExceptionMapper} so that callers see
 * {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException} instead of raw
 * {@link dev.vertique.db.exception.DataAccessException} types. Failures from the
 * caller-supplied {@code work} supplier propagate UNWRAPPED — the mapper applies only to the
 * repository deduplication step, not to business logic (FR-IO-004).
 *
 * <p>Registered as a singleton by {@link TransactionalMessagingPostgresqlModule}.
 */
@Singleton
class DefaultInboxService implements InboxService {

    private final InboxRepository repository;
    private final PgInboxOutboxExceptionMapper exceptionMapper;

    /**
     * Creates a new inbox service.
     *
     * @param repository      the inbox repository for deduplication record storage
     * @param exceptionMapper the exception mapper that translates data-access failures into
     *                        inbox/outbox-domain exceptions at the service boundary
     */
    @Inject
    DefaultInboxService(InboxRepository repository, PgInboxOutboxExceptionMapper exceptionMapper) {
        this.repository = repository;
        this.exceptionMapper = exceptionMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link InboxRepository#tryInsert} within the provided transaction.
     * When the insert succeeds (new message), invokes {@code work} and wraps the result in
     * {@link InboxResult.Processed}. When a conflict is detected (duplicate), skips {@code work}
     * and returns {@link InboxResult.Duplicate} immediately.
     *
     * <p>The {@code recover} that applies {@link PgInboxOutboxExceptionMapper#translate} is chained
     * on the {@code tryInsert} future BEFORE the {@code compose} that invokes {@code work}. This
     * ensures ONLY repository failures are wrapped; failures raised by {@code work.get()} inside
     * the {@code compose} propagate unchanged (FR-IO-004).
     */
    @Override
    public <T> Future<InboxResult<T>> processOnce(
            String messageId, String source, SqlClient tx, Supplier<Future<T>> work) {
        return repository
                .tryInsert(messageId, source, tx)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "inbox tryInsert")))
                .compose(inserted -> {
                    if (inserted) {
                        return work.get().map(value -> (InboxResult<T>) new InboxResult.Processed<>(value));
                    }
                    return Future.succeededFuture(new InboxResult.Duplicate<>());
                });
    }
}
