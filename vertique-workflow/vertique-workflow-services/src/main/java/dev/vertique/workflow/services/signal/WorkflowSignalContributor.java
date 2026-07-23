// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.signal;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.List;

/**
 * {@link ServiceContractContributor} that registers the synthetic {@code workflow.signals.post}
 * service endpoint over the event bus.
 *
 * <p>At registry build time, {@link #contribute(JsonObject)} returns a single
 * {@link ServiceContractRegistry.ContractEntry} describing the {@link WorkflowSignalEndpoint#post}
 * operation. The services framework deploys a {@link dev.vertique.services.ServiceVerticle} for this
 * entry, making it callable from the outbox relay after a signal is dispatched.
 *
 * <p>The {@link #handleSignal(WorkflowSignalRequest)} method implements exactly-once signal delivery
 * by composing:
 * <ol>
 *   <li>{@link Pool#withTransaction} — opens a PostgreSQL transaction.</li>
 *   <li>{@link InboxService#processOnce} with source {@code "workflow-signals"} — guards against
 *       transport-level retries from the outbox relay by deduplicating on a composite
 *       {@code messageId} of the form {@code workflowId + ":" + dedupKey}. This instance-scoping
 *       prevents two different workflow instances that reuse the same caller-supplied dedup string
 *       from colliding in the inbox table.</li>
 *   <li>{@link TransactionalWorkflowOperations#signal} — applies the signal inside the same
 *       transaction so the inbox record and the workflow state transition commit atomically.</li>
 * </ol>
 *
 * <p>The handler-pattern is used: the contract interface is {@link WorkflowSignalEndpoint} (for
 * address derivation and metadata), while the actual handler method
 * ({@link #handleSignal(WorkflowSignalRequest)}) lives on this class. The
 * {@code ServiceContractEntries} builder is given both the contract method (via
 * {@link WorkflowSignalEndpoint#METHOD_POST}) and the handler method (via
 * {@link #HANDLE_SIGNAL_METHOD}) so that annotation resolution and dispatch are correct.
 */
@Singleton
public final class WorkflowSignalContributor implements ServiceContractContributor {

    // --- Static method handle resolved once at class-load ---

    /**
     * Reflective handle for {@link #handleSignal(WorkflowSignalRequest)}, resolved once at
     * class-load time and used in the builder to configure the handler-pattern dispatch.
     */
    static final Method HANDLE_SIGNAL_METHOD;

    static {
        try {
            HANDLE_SIGNAL_METHOD =
                    WorkflowSignalContributor.class.getDeclaredMethod("handleSignal", WorkflowSignalRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Provider<TransactionalWorkflowOperations<SqlClient>> txOpsProvider;
    private final InboxService inboxService;
    private final Pool pool;

    /**
     * Creates a new contributor.
     *
     * <p>The {@code txOps} is injected as a {@link Provider} rather than directly to avoid a
     * Dagger dependency cycle: {@code WorkflowSignalContributor} is a
     * {@link ServiceContractContributor}, and the {@code ServiceContractRegistry} that aggregates
     * contributors is in turn needed by {@code ServiceTargetResolver}, which is depended on by
     * {@code OutboxSideEffectRecorder}, which feeds into the {@code @WorkflowRecorders}
     * multibinding consumed by {@code RecorderRouter}, which {@code PgWorkflowEngine} depends on.
     * Using {@code Provider} defers the lookup to dispatch-time, breaking the compile-time cycle.
     *
     * @param txOpsProvider lazy provider for transactional workflow operations; resolved at
     *     dispatch time, not at contributor construction
     * @param inboxService  inbox service used to deduplicate signal deliveries
     * @param pool          connection pool used to open a new transaction per signal invocation
     */
    @Inject
    public WorkflowSignalContributor(
            Provider<TransactionalWorkflowOperations<SqlClient>> txOpsProvider, InboxService inboxService, Pool pool) {
        this.txOpsProvider = txOpsProvider;
        this.inboxService = inboxService;
        this.pool = pool;
    }

    /**
     * Returns a single {@link ServiceContractRegistry.ContractEntry} for the
     * {@code workflow.signals.post} operation.
     *
     * <p>The entry uses the handler-pattern: {@link WorkflowSignalEndpoint} is the contract key
     * (governs address derivation and target id), while this contributor instance is the
     * {@code serviceInstance} and {@link #HANDLE_SIGNAL_METHOD} is the handler method invoked at
     * dispatch time. Deployment options are read from the {@code services.workflow.signals} config
     * subtree.
     *
     * @param config root application configuration; used for deployment option resolution
     * @return a single-element list containing the signal contract entry
     */
    @Override
    public List<ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config) {
        return List.of(ServiceContractEntries.deployable()
                .contract(WorkflowSignalEndpoint.class)
                .serviceInstance(this)
                .namespace("workflow")
                .name("signals")
                .operation("post")
                .method(WorkflowSignalEndpoint.METHOD_POST)
                .handlerMethod(HANDLE_SIGNAL_METHOD)
                .payloadType(WorkflowSignalRequest.class)
                .returnType(Void.class)
                .param("payload", ParamSource.PAYLOAD, WorkflowSignalRequest.class)
                .handlerParam("payload", ParamSource.PAYLOAD, WorkflowSignalRequest.class)
                .done()
                .deploymentOptions(config, "services", "workflow", "signals")
                .build());
    }

    /**
     * Handles an inbound signal by opening a transaction and applying exactly-once delivery.
     *
     * <p>The work chain is:
     * <pre>{@code
     * pool.withTransaction(tx ->
     *     inboxService.processOnce(inboxMessageId, "workflow-signals", tx, () ->
     *         txOpsProvider.get().signal(req.workflowId(), req.signalName(), req.payload(), req.dedupKey(), tx))
     *     .mapEmpty())
     * }</pre>
     * Both the inbox dedup record and the workflow state transition commit in the same transaction.
     * If the relay retries due to a transient failure, the inbox record prevents the signal from
     * being applied twice.
     *
     * <p>The inbox {@code messageId} is scoped to the workflow instance:
     * {@code workflowId.value() + ":" + req.dedupKey()}. This prevents two different workflow
     * instances that happen to reuse the same caller-supplied dedup string from colliding in the
     * inbox table and silently dropping one another's signals. The {@code dedupKey} argument passed
     * to {@link TransactionalWorkflowOperations#signal} is unchanged — the engine's
     * {@code workflow_dedup} table already scopes dedup to the instance.
     *
     * <p>PRD-WF-007 (Contract Appendix C4): when {@link WorkflowSignalRequest#metadata()} is
     * non-null, it is decoded via {@link DurableMetadata#fromCarrier(io.vertx.core.json.JsonObject)}
     * and delivered through the metadata-aware 8-arg {@link TransactionalWorkflowOperations#signal}
     * overload. A {@code null} carrier keeps calling the legacy 7-arg overload byte-for-byte.
     *
     * <p>The carrier decode runs INSIDE the {@code processOnce} work supplier rather than before
     * {@code pool.withTransaction} is entered. This has two effects: (1) any decode failure (a
     * malformed {@code context} section or namespace body — see
     * {@link dev.vertique.core.exception.MalformedDurableMetadataException}, a
     * {@link dev.vertique.core.exception.ValidationException} subtype) propagates as a failed
     * {@link Future} through the normal async error-handling path instead of throwing synchronously
     * out of this method; and (2) a decode failure on the FIRST delivery of a malformed message
     * rolls back the whole transaction — including the inbox insert that
     * {@link InboxService#processOnce} performs before invoking this work supplier (insert, then
     * run work, in one transaction; see {@code DefaultInboxService.processOnce} in
     * {@code vertique-inbox-outbox-postgresql}). The dedup record therefore never commits for a
     * poison message, so every redelivery attempt re-decodes it and re-fails identically; only an
     * <em>already-committed</em> duplicate (a message whose first delivery succeeded) is skipped by
     * the dedup check before decode is attempted.
     *
     * <p>Redelivery of a message this handler fails is governed entirely by the inbound transport,
     * not by this module: the failed reply propagates to the external adapter as a failure, which
     * fails the exchange so the source
     * connector retains/retries the message per its own (route-configured) redelivery and
     * dead-letter policy. This module does not itself bound or dead-letter repeated redelivery of a
     * poison message — a persistently malformed message re-decodes and re-fails on every redelivery
     * until the transport's own policy stops retrying or an operator intervenes.
     *
     * @param req the signal request containing the workflow id, signal name, payload, and dedup key
     * @return a {@link Future} that completes with {@code null} when the signal is applied (or
     *     idempotently skipped as a duplicate)
     */
    Future<Void> handleSignal(WorkflowSignalRequest req) {
        // PRD-WF-002 D11a: scope the inbox message id by branch identity when the request targets a
        // specific fork-group branch so two sibling branches sharing a dedupKey do not collide in
        // the inbox table. Non-branch requests preserve the cycle-2 form (workflowId + ":" + key).
        String inboxMessageId =
                WorkflowDedupScopes.signalInboxId(req.workflowId(), req.forkStepId(), req.branchId(), req.dedupKey());
        TransactionalWorkflowOperations<SqlClient> txOps = txOpsProvider.get();
        if (req.metadata() == null) {
            return pool.withTransaction(tx -> inboxService
                    .processOnce(
                            inboxMessageId,
                            "workflow-signals",
                            tx,
                            () -> txOps.signal(
                                    req.workflowId(),
                                    req.signalName(),
                                    req.payload(),
                                    req.dedupKey(),
                                    req.forkStepId(),
                                    req.branchId(),
                                    tx))
                    .mapEmpty());
        }
        return pool.withTransaction(tx -> inboxService
                .processOnce(inboxMessageId, "workflow-signals", tx, () -> {
                    DurableMetadata signalMetadata = DurableMetadata.fromCarrier(req.metadata());
                    return txOps.signal(
                            req.workflowId(),
                            req.signalName(),
                            req.payload(),
                            req.dedupKey(),
                            req.forkStepId(),
                            req.branchId(),
                            signalMetadata,
                            tx);
                })
                .mapEmpty());
    }
}
