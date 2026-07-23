// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.OutboxService;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link OutboxService} backed by an {@link OutboxRepository}.
 *
 * <p>Delegates outbox row insertion to {@link OutboxRepository#insert}, preserving the caller's
 * transaction context so the outbox write and business state change are atomic.
 *
 * <p>Before insert, the service captures the currently bound durable context values (e.g.
 * {@code LocalizationContext}, {@code CorrelationContext}) into a {@link DurableMetadata} document
 * via {@link DurableContextPropagator#mergeCaptured} with the {@code "outbox"} boundary (FR-CTX-175).
 * The captured context is stored in the {@code metadata} JSONB column as
 * {@link OutboxMetadata#context()}, not in the entry's {@code headers} map. Caller-supplied headers
 * are written to the {@code headers} column unchanged.
 *
 * <p><strong>Per-row carrier binding (PRD identity-002 §14.6/A9, F3b).</strong> A fresh
 * {@code carrierId} {@link UUID} is allocated <em>before</em> {@code mergeCaptured} and threaded into
 * the encode context as a {@link DurableCarrierDescriptor} — {@code carrierId} = this row's carrier
 * id; target {@code (}{@value #OUTBOX_RELAY_TARGET_KIND}{@code , carrierId)}. A durable identity
 * snapshot captured here is thus signed for this exact outbox row and cannot be transplanted onto
 * another. The same id is persisted verbatim into the first-class, {@code NOT NULL UNIQUE}
 * {@code carrier_id} column ({@link OutboxRepository#insert}) — never into the app-writable
 * {@code metadata} JSONB — so the relay can reproduce it from a column it controls rather than from
 * attacker-writable input. The target kind {@value #OUTBOX_RELAY_TARGET_KIND} is the canonical
 * durable-target kind string for the outbox boundary (F7a): it matches the
 * {@link dev.vertique.core.context.DeferredExecutionOrigin#kind()} the receive-side relay dispatch
 * binds, so an operator-configured {@code identity.snapshot.carriageRequirements} entry for the
 * outbox uses this one string.
 *
 * <p>Repository failures from {@link OutboxRepository#insert} are wrapped by
 * {@link InboxOutboxExceptionMapper} so that callers see {@code InboxOutboxPersistenceException}
 * instead of raw {@link dev.vertique.db.exception.DataAccessException} types.
 *
 * <p>Registered as a singleton by {@code TransactionalMessagingPostgresqlModule}.
 */
@Singleton
class DefaultOutboxService implements OutboxService {

    private static final String BOUNDARY = dev.vertique.core.context.DispatchBoundary.OUTBOX;

    /**
     * Canonical durable-target kind for the outbox row-carrier (F7a) — matches the
     * {@code "outbox-relay"} kind {@code ServiceOutboxDestinationHandler} binds into
     * {@link dev.vertique.core.context.DeferredExecutionOrigin} on the receive side, so the produce-
     * and consume-side carrier target kinds — and any operator {@code carriageRequirements}
     * configuration keyed on this string — agree.
     */
    private static final String OUTBOX_RELAY_TARGET_KIND = "outbox-relay";

    private final OutboxRepository repository;
    private final DurableContextPropagator propagator;
    private final InboxOutboxExceptionMapper exceptionMapper;

    /**
     * Creates a new outbox service.
     *
     * @param repository      the outbox repository for entry storage
     * @param propagator      the durable context propagator for capturing ambient context into the
     *                        {@code metadata} document at publish time
     * @param exceptionMapper the exception mapper that translates data-access failures into
     *                        inbox/outbox-domain exceptions at the service boundary
     */
    @Inject
    DefaultOutboxService(
            OutboxRepository repository,
            DurableContextPropagator propagator,
            InboxOutboxExceptionMapper exceptionMapper) {
        this.repository = repository;
        this.propagator = propagator;
        this.exceptionMapper = exceptionMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Captures the currently bound durable context via {@link DurableContextPropagator#mergeCaptured}
     * into an {@link OutboxMetadata} document and delegates to {@link OutboxRepository#insert} within
     * the caller's transaction. The entry's {@code headers} are stored as-is (application/transport
     * headers only — no framework control keys). When the entry carries a {@link DelayedJobControl}
     * snapshot, it is persisted in {@code metadata.delivery.delayedJob}; all other entries receive
     * an empty delivery section (FR-CTX-175, FR-TM-039).
     *
     * <p><b>Durable-context contract:</b> the propagated context is ALWAYS the ambient context bound
     * in the holder at publish time — there is intentionally no caller-supplied context field on
     * {@link OutboxEntry}. To publish under a specific context, bind it (e.g. via
     * {@code ContextHolder.bind}) before calling {@code publish}; the capture picks it up. This
     * differs from the delayed-job path, which threads {@code premergedMetadata} for deferred
     * execution — the outbox relay instead re-reads {@code metadata.context} from the persisted row,
     * so no pre-merge is needed here.
     *
     * <p><b>Per-row carrier binding (F3b, see class javadoc):</b> a fresh {@code carrierId} is
     * allocated before {@code mergeCaptured} so any durable identity snapshot captured here is signed
     * for this exact row, and is persisted into the first-class {@code carrier_id} column via
     * {@link OutboxRepository#insert}.
     *
     * <p>Any {@link dev.vertique.db.exception.DataAccessException} from the repository is translated
     * to an {@link dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException} via
     * {@link InboxOutboxExceptionMapper#translate} so that callers see typed inbox/outbox failures.
     */
    @Override
    public Future<Long> publish(SqlClient tx, OutboxEntry entry) {
        // F3b row binding: allocate the carrier id up front so the durable snapshot this call
        // captures is signed for THIS row, then persist the same id into the first-class column.
        UUID carrierId = UUID.randomUUID();
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                carrierId.toString(),
                new DurableTarget(OUTBOX_RELAY_TARGET_KIND, carrierId.toString(), Optional.empty()));
        DurableMetadata context = propagator.mergeCaptured(DurableMetadata.empty(), BOUNDARY, carrier);
        OutboxDeliveryMetadata delivery =
                new OutboxDeliveryMetadata(Optional.empty(), Optional.ofNullable(entry.delayedJob()));
        OutboxMetadata metadata = new OutboxMetadata(context, delivery);
        return repository
                .insert(entry, metadata, carrierId, tx)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox publish")));
    }
}
