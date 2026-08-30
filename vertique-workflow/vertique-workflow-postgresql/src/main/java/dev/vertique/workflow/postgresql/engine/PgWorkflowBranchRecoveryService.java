// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadata.MergePolicy;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically scans for branch tokens that need recovery (PRD-WF-002 §A.4.3).
 *
 * <p>Two recovery paths:
 * <ul>
 *   <li>{@code RETRY_SCHEDULED} branches whose {@code next_retry_at} is at or before {@code now}
 *       are resumed — the branch is driven through the {@link WorkflowRecoveryBridge} (which delegates
 *       to the engine's branch transition logic) and already gates dispatch via
 *       {@code workflow_dedup} so retries don't double-emit.</li>
 *   <li>Stale {@code RUNNING} branches whose {@code updated_at} is older than the configured
 *       threshold are demoted to {@code RETRY_SCHEDULED} (if attempts remain) or {@code FAILED}
 *       (otherwise). The recovery cycle then picks up the {@code RETRY_SCHEDULED} ones.</li>
 * </ul>
 *
 * <p>Each branch is processed in its own transaction so a single failed branch doesn't poison the
 * entire sweep. Transactions are opened through the injected {@link WorkflowTransactionRunner} so DB
 * failures in the sweep surface as {@code WorkflowException} (via the runner's two-stage mapper),
 * matching the caller-facing engine paths. The service is read-side {@code @Singleton};
 * {@code PgWorkflowBranchRecoveryCron} (a cluster-singleton {@code @CronJob}) drives the
 * periodic tick.
 */
@Singleton
public final class PgWorkflowBranchRecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(PgWorkflowBranchRecoveryService.class);

    private static final String STALE_RUNNING_NO_RETRIES = "stale_running_no_retries";

    private final WorkflowTransactionRunner<SqlClient> txRunner;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowRecoveryBridge recoveryBridge;
    private final WorkflowRegistry registry;
    private final Clock clock;

    /**
     * Durable context propagator used to bind each recovered branch's persisted
     * {@link BranchToken#metadata()} before its drive proceeds (FR-CTX-178). The sweep processes
     * branches sequentially in {@link #processDue}, opening one scope per branch and closing it
     * on {@code Future.eventually(...)} so two siblings cannot have overlapping holder writes on
     * the same Vert.x context. {@code null} is tolerated for legacy ctors that do not exercise
     * the bind path.
     */
    private final dev.vertique.context.DurableContextPropagator propagator;

    /**
     * Substrate lifecycle helper. When provided, branch-recovery uses
     * {@link dev.vertique.context.InboundExecutionContextScope#installDurable installDurable(...)}
     * which runs {@code propagator.bindFrom} AND every registered
     * {@link InboundContextInitializer} (e.g.
     * {@code CorrelationContextSeeder}) inside one composed scope.
     */
    private final dev.vertique.context.InboundExecutionContextScope inboundExecScope;

    /**
     * Creates a new recovery service.
     *
     * @param txRunner transaction runner that owns the transaction boundary and the two-stage
     *     workflow exception mapping for the sweep's DB work
     * @param branchTokens branch token repository
     * @param instances instance repository (used to resolve definitionId/Version per branch)
     * @param recoveryBridge recovery bridge exposing the engine internals the sweep needs (branch
     *     drive, terminal-join evaluation, plan-hash drift guard) without widening engine visibility
     * @param registry workflow registry used to resolve the runtime workflow per branch
     * @param clock clock used for the {@code next_retry_at} cutoff and stale-running threshold
     * @param propagator durable context propagator used to bind branch metadata for the duration
     *     of each branch's drive
     * @param inboundExecScope substrate lifecycle helper composing the durable bind with
     *     first-ingress initializers (see field javadoc); {@code null} skips that path
     */
    @Inject
    public PgWorkflowBranchRecoveryService(
            WorkflowTransactionRunner<SqlClient> txRunner,
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowRecoveryBridge recoveryBridge,
            WorkflowRegistry registry,
            Clock clock,
            dev.vertique.context.DurableContextPropagator propagator,
            dev.vertique.context.InboundExecutionContextScope inboundExecScope) {
        this.txRunner = txRunner;
        this.branchTokens = branchTokens;
        this.instances = instances;
        this.recoveryBridge = recoveryBridge;
        this.registry = registry;
        this.clock = clock;
        this.propagator = propagator;
        this.inboundExecScope = inboundExecScope;
    }

    /**
     * Constructor without the inbound-execution helper — exists for callers that still use the
     * plain propagator path. First-ingress initializers do not fire under this ctor.
     *
     * @param txRunner transaction runner that owns the transaction boundary and the two-stage
     *     workflow exception mapping for the sweep's DB work
     * @param branchTokens branch token repository
     * @param instances instance repository (used to resolve definitionId/Version per branch)
     * @param recoveryBridge recovery bridge exposing the engine internals the sweep needs
     * @param registry workflow registry used to resolve the runtime workflow per branch
     * @param clock clock used for the {@code next_retry_at} cutoff and stale-running threshold
     * @param propagator durable context propagator used to bind branch metadata for the duration
     *     of each branch's drive
     */
    public PgWorkflowBranchRecoveryService(
            WorkflowTransactionRunner<SqlClient> txRunner,
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowRecoveryBridge recoveryBridge,
            WorkflowRegistry registry,
            Clock clock,
            dev.vertique.context.DurableContextPropagator propagator) {
        this(txRunner, branchTokens, instances, recoveryBridge, registry, clock, propagator, null);
    }

    /**
     * Test-only ctor without a propagator — branch-recovery durable binding is skipped. Tests
     * that need binding must use the {@link Inject}'d ctor.
     *
     * @param txRunner transaction runner that owns the transaction boundary and the two-stage
     *     workflow exception mapping for the sweep's DB work
     * @param branchTokens branch token repository
     * @param instances instance repository (used to resolve definitionId/Version per branch)
     * @param recoveryBridge recovery bridge exposing the engine internals the sweep needs
     * @param registry workflow registry used to resolve the runtime workflow per branch
     * @param clock clock used for the {@code next_retry_at} cutoff and stale-running threshold
     */
    public PgWorkflowBranchRecoveryService(
            WorkflowTransactionRunner<SqlClient> txRunner,
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowRecoveryBridge recoveryBridge,
            WorkflowRegistry registry,
            Clock clock) {
        this(txRunner, branchTokens, instances, recoveryBridge, registry, clock, null, null);
    }

    /**
     * Runs one recovery sweep, computing the stale cutoff as {@code now - staleness} via the
     * injected {@link Clock}.
     *
     * <p>This is the cron-friendly overload used by {@code PgWorkflowBranchRecoveryCron}:
     * configuration declares a relative {@link Duration} and the service owns the wall-clock
     * resolution so the cron adapter stays free of timing logic. The {@link Instant} overload
     * called here re-reads the clock for the {@code next_retry_at} cutoff; the two reads are
     * intentional (different cutoffs, sub-millisecond drift is irrelevant against 5-minute
     * stale thresholds and 30-second cron periods).
     *
     * @param staleness branches whose {@code updated_at} is older than {@code now - staleness}
     *     are considered stale RUNNING
     * @param batchSize maximum number of rows to process per status (RETRY_SCHEDULED + RUNNING)
     * @return a {@link Future} of the total number of branches processed
     */
    public Future<Integer> sweepOnce(Duration staleness, int batchSize) {
        return sweepOnce(clock.instant().minus(staleness), batchSize);
    }

    /**
     * Runs one recovery sweep.
     *
     * @param staleThreshold instants strictly older than this are considered stale RUNNING
     * @param batchSize maximum number of rows to process per status (RETRY_SCHEDULED + RUNNING)
     * @return a {@link Future} of the total number of branches processed
     */
    public Future<Integer> sweepOnce(Instant staleThreshold, int batchSize) {
        Instant now = clock.instant();
        return txRunner.inTransaction(null, tx -> branchTokens.findRecoverable(now, batchSize, tx))
                .compose(due -> processDue(due).map(due.size()))
                .compose(dueCount -> txRunner.inTransaction(
                                null, tx -> branchTokens.findStaleRunning(staleThreshold, batchSize, tx))
                        .compose(stale -> processStale(stale).map(stale.size() + dueCount)));
    }

    private Future<Void> processDue(List<BranchToken> due) {
        Future<Void> chain = Future.succeededFuture();
        for (BranchToken token : due) {
            // Process branches sequentially so each branch's durable-metadata bind has the
            // holder to itself — never opening branch N+1's scope while branch N's is still
            // bound. The per-branch scope wraps the drive and closes on Future.eventually so it
            // survives the async driveBranchTransitions chain.
            chain = chain.compose(v -> resumeOne(token).recover(t -> {
                LOG.warn("branch recovery resume failed for {}: {}", token.id(), t.toString());
                return Future.<Void>succeededFuture();
            }));
        }
        return chain;
    }

    /**
     * Computes the recovery-time effective durable-context document for {@code token} from an
     * already-loaded parent instance.
     *
     * <p>Per FR-WF-CTX-024/026: when {@code instance} is {@code null} or its
     * {@link WorkflowInstance#metadata()} is {@code null}, the effective document is exactly
     * {@code token.metadata()} unchanged — a no-op merge that preserves today's behavior
     * bit-for-bit for NULL-carrier instances ({@link BranchToken#metadata()} is itself never
     * {@code null} — {@link BranchToken}'s compact constructor normalizes it to
     * {@code DurableMetadata.empty()}). Otherwise the effective document is
     * {@code token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)}: the branch's
     * persisted carrier is authoritative for any namespace present on both sides, and the instance
     * only fills namespaces the carrier lacks.
     *
     * @param token    the branch token being recovered
     * @param instance the already-loaded parent workflow instance, or {@code null} when it could
     *                 not be loaded (short-circuit paths that never reach the instance read)
     * @return the effective document to bind for this branch's recovery drive
     */
    private static DurableMetadata effectiveBase(
            BranchToken token, @jakarta.annotation.Nullable WorkflowInstance instance) {
        DurableMetadata tokenMeta = token.metadata();
        if (instance == null || instance.metadata() == null) {
            return tokenMeta;
        }
        return tokenMeta.merge(instance.metadata(), MergePolicy.CALLER_WINS);
    }

    /**
     * Wraps a per-branch recovery action with an authoritative durable-context bind of the given
     * effective document under {@link DispatchBoundary#WORKFLOW}. The scope is closed once the
     * action's returned {@link Future} completes, so it survives the full async action. A
     * synchronous throw before the action's future is returned still closes the scope.
     *
     * <p>{@code bindFrom}/{@code installDurable} are authoritative: every registered durable type
     * absent from {@code effectiveBase} is cleared for the scope's lifetime so the cron sweep's
     * ambient context (or any other caller's context) cannot leak into a {@code mergeCaptured(...)}
     * that may run during the demote/resume action. An empty document still triggers the bind —
     * that's how branches that were forked without any durable values get their ambient context
     * cleared from the cron sweep's holder.
     *
     * @param effectiveBase the document to bind for the action's lifetime
     * @param action        the per-branch recovery action to run inside the bound scope
     * @param <T>           the action's result type
     * @return a {@link Future} that completes when the action finishes (bound or unbound)
     */
    private <T> Future<T> withBranchDurableBound(
            DurableMetadata effectiveBase, java.util.function.Supplier<Future<T>> action) {
        if (inboundExecScope != null) {
            // Lifecycle helper composes propagator.bindFrom + first-ingress initializers via the
            // shared install/try/close shape (also used by WorkflowContextBinder.withBound and
            // BranchTransitionEngine.driveBranchTransitions).
            return inboundExecScope.installDurableAndRun(effectiveBase, DispatchBoundary.WORKFLOW, action);
        }
        // Legacy ctor fallback: no substrate lifecycle helper wired — bind via the plain
        // propagator when present, or run fully unbound when neither is wired.
        ContextHolder.Scope scope = null;
        try {
            if (propagator != null) {
                scope = propagator.bindFrom(effectiveBase, DispatchBoundary.WORKFLOW);
            }
            final ContextHolder.Scope finalScope = scope;
            Future<T> result = action.get();
            return finalScope == null
                    ? result
                    : result.eventually(() -> {
                        finalScope.close();
                        return Future.succeededFuture();
                    });
        } catch (RuntimeException e) {
            if (scope != null) {
                scope.close();
            }
            return Future.failedFuture(e);
        }
    }

    private Future<Void> processStale(List<BranchToken> stale) {
        Future<Void> chain = Future.succeededFuture();
        for (BranchToken token : stale) {
            chain = chain.compose(v -> demoteOne(token).recover(t -> {
                LOG.warn("branch recovery demote failed for {}: {}", token.id(), t.toString());
                return Future.<Void>succeededFuture();
            }));
        }
        return chain;
    }

    /**
     * Promotes a due {@code RETRY_SCHEDULED} branch back to {@code RUNNING} and drives it, binding
     * the recovery-time effective durable-context document around the drive and the subsequent
     * join evaluation.
     *
     * <p>Single fresh read, no extra transaction: opens one transaction, locks the branch row
     * ({@code findByIdForUpdate}), then reads the parent instance via the existing
     * {@link #loadInstanceAndRuntime} call (a plain non-locking {@code findById}, already required
     * for the plan-hash drift guard) — no separate pre-load transaction runs before this one. The
     * effective document is computed from that single instance read and the durable scope is
     * opened <em>inside</em> this transaction, immediately before the drive, closing on
     * {@code Future.eventually(...)} so it covers the drive and the terminal-join evaluation that
     * follows it in the same row's processing. Preserves the lock-order invariant: the branch-token
     * {@code FOR UPDATE} lock is taken first; the instance read stays a non-locking {@code findById}.
     *
     * @param stale the branch token to resume
     * @return a {@link Future} that completes when the row's processing (bound or unbound) finishes
     */
    private Future<Void> resumeOne(BranchToken stale) {
        return txRunner.inTransaction(
                null, tx -> branchTokens.findByIdForUpdate(stale.id(), tx).compose(opt -> {
                    if (opt.isEmpty()) return Future.<Void>succeededFuture();
                    BranchToken token = opt.get();
                    if (token.status() != BranchStatus.RETRY_SCHEDULED) return Future.<Void>succeededFuture();
                    if (token.nextRetryAt() == null || token.nextRetryAt().isAfter(clock.instant())) {
                        return Future.<Void>succeededFuture();
                    }
                    return loadInstanceAndRuntime(token, tx).compose(loadedOpt -> {
                        if (loadedOpt.isEmpty()) return Future.<Void>succeededFuture();
                        LoadedRuntime loaded = loadedOpt.get();
                        DurableMetadata effectiveBase = effectiveBase(token, loaded.inst());
                        return withBranchDurableBound(effectiveBase, () -> {
                            // Promote to RUNNING and drive. The BranchTransitionEngine's dispatch-
                            // dedup gate makes service redispatches idempotent (FR-WF-PAR-038).
                            BranchToken running =
                                    token.withStatus(BranchStatus.RUNNING, token.version() + 1, clock.instant());
                            return branchTokens
                                    .updateOptimistic(running, token.version(), tx)
                                    .compose(rc -> {
                                        if (rc == 0) return Future.<Void>succeededFuture();
                                        return recoveryBridge
                                                .driveRecoveredBranch(
                                                        running,
                                                        loaded.inst().stateJson(),
                                                        loaded.inst().subjectRef() == null
                                                                ? null
                                                                : loaded.inst()
                                                                        .subjectRef()
                                                                        .version(),
                                                        loaded.rw(),
                                                        tx,
                                                        effectiveBase)
                                                .compose(advanced -> recoveryBridge.evaluateRecoveredBranchIfTerminal(
                                                        loaded.inst(), advanced, loaded.rw(), tx));
                                    });
                        });
                    });
                }));
    }

    /**
     * Demotes a stale {@code RUNNING} branch to {@code RETRY_SCHEDULED} or {@code FAILED} and, only
     * on a terminal ({@code FAILED}) demotion, evaluates the parent join — binding the recovery-time
     * effective durable-context document around that terminal-evaluation work exactly as the drive
     * path does.
     *
     * <p>The non-terminal ({@code RETRY_SCHEDULED}) path never reaches the instance read at all —
     * unchanged short-circuit before {@link #loadInstanceAndRuntime} — so no durable scope is
     * opened in that case. On the terminal path, {@link #loadInstanceAndRuntime} is the same single
     * fresh, non-locking read used to compute the effective document, and the scope wraps only the
     * {@code evaluateRecoveredBranchIfTerminal} call that follows it.
     *
     * @param stale the branch token to demote
     * @return a {@link Future} that completes when the row's processing (bound or unbound) finishes
     */
    private Future<Void> demoteOne(BranchToken stale) {
        return txRunner.inTransaction(
                null, tx -> branchTokens.findByIdForUpdate(stale.id(), tx).compose(opt -> {
                    if (opt.isEmpty()) return Future.<Void>succeededFuture();
                    BranchToken token = opt.get();
                    if (token.status() != BranchStatus.RUNNING) return Future.<Void>succeededFuture();
                    Instant now = clock.instant();
                    BranchToken next = (token.attemptCount() + 1 < token.maxAttempts())
                            ? buildRetryScheduled(token, now)
                            : buildFailed(token, now);
                    return branchTokens
                            .updateOptimistic(next, token.version(), tx)
                            .compose(rc -> {
                                if (rc == 0) return Future.<Void>succeededFuture();
                                // Only a terminal demotion (FAILED — retry budget exhausted) needs the
                                // parent join re-evaluated; a non-terminal demotion (RETRY_SCHEDULED) is
                                // picked up by a later sweep, so skip the instance load entirely. This
                                // preserves the original short-circuit before loadInstanceAndRuntime.
                                if (next.status() != BranchStatus.FAILED) {
                                    return Future.<Void>succeededFuture();
                                }
                                // Stale RUNNING with no retry budget left was just FAILED; the parent
                                // instance is parked at the join. Resolve the runtime workflow and
                                // evaluate the join so ALL_REQUIRED takes the failure route (and race
                                // policies elect the first failure / supersede siblings). The bridge
                                // performs its own terminality check before evaluating the join.
                                return loadInstanceAndRuntime(next, tx).compose(loadedOpt -> {
                                    if (loadedOpt.isEmpty()) return Future.<Void>succeededFuture();
                                    LoadedRuntime loaded = loadedOpt.get();
                                    DurableMetadata effectiveBase = effectiveBase(next, loaded.inst());
                                    return withBranchDurableBound(
                                            effectiveBase,
                                            () -> recoveryBridge.evaluateRecoveredBranchIfTerminal(
                                                    loaded.inst(), next, loaded.rw(), tx));
                                });
                            });
                }));
    }

    /**
     * Loads the parent workflow instance, resolves the pinned runtime workflow, and runs the
     * plan-hash drift guard so recovery never silently runs new callbacks against a persisted
     * instance after a redeployment. Returns {@code null} when the instance has been deleted
     * (caller short-circuits).
     */
    private Future<Optional<LoadedRuntime>> loadInstanceAndRuntime(BranchToken token, SqlClient tx) {
        return instances.findById(token.workflowId(), tx).compose(opt -> {
            if (opt.isEmpty()) return Future.succeededFuture(Optional.empty());
            WorkflowInstance inst = opt.get();
            RuntimeWorkflow rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
            try {
                recoveryBridge.validateRecoveryPlan(inst, rw);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            return Future.succeededFuture(Optional.of(new LoadedRuntime(inst, rw)));
        });
    }

    private static BranchToken buildRetryScheduled(BranchToken token, Instant now) {
        return new BranchToken(
                token.id(),
                token.workflowId(),
                token.forkStepId(),
                token.branchId(),
                token.currentStepId(),
                BranchStatus.RETRY_SCHEDULED,
                token.waitType(),
                token.waitKey(),
                token.waitAuxId(),
                token.resultJson(),
                token.errorType(),
                token.errorMessage(),
                token.attemptCount() + 1,
                token.maxAttempts(),
                now.plusSeconds(5),
                "stale_running",
                "Branch was RUNNING longer than the stale threshold; demoted to RETRY_SCHEDULED",
                now,
                token.version() + 1,
                token.createdAt(),
                now,
                token.metadata()); // preserve persisted durable metadata so the next recovery
        //                            round can bind it before resuming the retried branch
    }

    private static BranchToken buildFailed(BranchToken token, Instant now) {
        return new BranchToken(
                token.id(),
                token.workflowId(),
                token.forkStepId(),
                token.branchId(),
                token.currentStepId(),
                BranchStatus.FAILED,
                null,
                null,
                null,
                token.resultJson(),
                STALE_RUNNING_NO_RETRIES,
                "Branch was RUNNING longer than the stale threshold and the retry budget is exhausted",
                token.attemptCount(),
                token.maxAttempts(),
                null,
                STALE_RUNNING_NO_RETRIES,
                "stale RUNNING + no retries remaining",
                now,
                token.version() + 1,
                token.createdAt(),
                now,
                token.metadata()); // FAILED is terminal — metadata value is observationally
        //                            unused, but preserving it keeps the row symmetric with
        //                            RETRY_SCHEDULED and avoids surprising a future operator
        //                            inspecting the row
    }

    private record LoadedRuntime(WorkflowInstance inst, RuntimeWorkflow rw) {}
}
