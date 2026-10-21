// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Package-private collaborator owning the instance-migration flow (PRD-WF-006, Slice C9).
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition. It
 * absorbs the tx-aware migration algorithm ({@link #migrate}, {@link #doMigrate}) and the pinned
 * source-definition resolver ({@link #resolvePinnedForInstance}), which is used only by the migration
 * path.
 *
 * <p>Migration is <b>opt-in</b>: the {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry}
 * binding is declared via {@link WorkflowEngineModule#optionalMigrationRegistry()}, so the injected
 * {@link Optional} is empty when the application did not install
 * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}. When the registry is absent,
 * {@link #migrate} fails immediately with {@link UnsupportedOperationException} before any database
 * work, keeping the fail-fast guard co-located with the registry it guards. Apps that never call
 * {@code migrate} need not wire the module.
 *
 * <p>This service drives the state machine forward after applying a migration via
 * {@link WorkflowTransitionDriver#driveTransitions}, so it injects the driver <em>eagerly</em>.
 * Nothing references this service back, so it is a leaf in the construction order and forms no Dagger
 * cycle.
 *
 * <p>Plan-hash checks and the row-updated guard reuse the shared {@link WorkflowPayloads} utilities.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class MigrationExecutor {

    // --- Dependencies ---

    /**
     * Optional migration registry — {@code null} when the application did not install
     * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}. When {@code null},
     * {@link #migrate} fails immediately with {@link UnsupportedOperationException}.
     */
    @jakarta.annotation.Nullable
    private final dev.vertique.workflow.migration.WorkflowMigrationRegistry migrationRegistry;

    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;

    /**
     * Branch-token repository used to reject migration of instances with active fork-branch state.
     * May be {@code null} in legacy test constructors that omit the fork/join collaborators.
     */
    @jakarta.annotation.Nullable
    private final BranchTokenRepository<SqlClient> branchTokens;

    private final WorkflowTransitionDriver driver;
    private final Clock clock;

    /**
     * Comparison that decides whether a target state class extends the source's when the two differ.
     * Empty when the application installed none, which refuses a migration between differing state classes.
     */
    private final Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility;

    /**
     * Binder-row seam for the instance-owned migrate drive (Contract Appendix C2). Never
     * {@code null} — the engine assembly seam substitutes {@link WorkflowContextBinder#noop()}
     * when durable-context wiring is not exercised, so {@link #migrate} always calls
     * {@link WorkflowContextBinder#withBound} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /**
     * Constructs a new migration executor.
     *
     * @param migrationRegistryOpt the optional migration registry; empty when the migration module is
     *     not installed. May be {@code null} when invoked from legacy test constructors that delegate
     *     with {@code null}; Dagger always supplies a non-null {@link Optional} via
     *     {@code @BindsOptionalOf}.
     * @param registry the workflow registry used to resolve pinned source/target runtime workflows
     * @param instances the instance repository used to read and re-pin the migrated instance
     * @param history the history repository used for recent-history context, sequence numbers, and
     *     the {@code WORKFLOW_MIGRATED} history entry
     * @param branchTokens the branch-token repository used to reject instances with active
     *     fork-branch state; may be {@code null} in legacy test constructors
     * @param driver the transition driver invoked to drive transitions in the target plan after the
     *     migration is applied
     * @param clock the clock used to timestamp the migrated snapshot and history entry
     * @param contextBinder the binder-row seam for the instance-owned migrate drive; never
     *     {@code null} (the engine assembly seam substitutes {@link WorkflowContextBinder#noop()}
     *     when durable-context wiring is not exercised)
     * @param stateCompatibility the state comparison applied when the source and target state classes differ;
     *     empty refuses such a migration
     */
    @Inject
    MigrationExecutor(
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            @jakarta.annotation.Nullable BranchTokenRepository<SqlClient> branchTokens,
            WorkflowTransitionDriver driver,
            Clock clock,
            WorkflowContextBinder contextBinder,
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility) {
        // migrationRegistryOpt is null only from test-only constructors that delegate with null;
        // Dagger always provides a non-null Optional via @BindsOptionalOf.
        this.migrationRegistry = migrationRegistryOpt != null ? migrationRegistryOpt.orElse(null) : null;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.branchTokens = branchTokens;
        this.driver = driver;
        this.clock = clock;
        this.contextBinder = contextBinder;
        this.stateCompatibility = stateCompatibility != null ? stateCompatibility : Optional.empty();
    }

    /**
     * Constructs a migration executor without a state comparison, so a migration between differing state
     * classes is refused.
     *
     * @param migrationRegistryOpt the optional migration registry
     * @param registry the workflow registry
     * @param instances the instance repository
     * @param history the history repository
     * @param branchTokens the branch-token repository; may be {@code null}
     * @param driver the transition driver
     * @param clock the clock
     * @param contextBinder the binder-row seam
     */
    MigrationExecutor(
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            @jakarta.annotation.Nullable BranchTokenRepository<SqlClient> branchTokens,
            WorkflowTransitionDriver driver,
            Clock clock,
            WorkflowContextBinder contextBinder) {
        this(
                migrationRegistryOpt,
                registry,
                instances,
                history,
                branchTokens,
                driver,
                clock,
                contextBinder,
                Optional.empty());
    }

    // --- Migration flow ---

    /**
     * Migrates a running instance to {@code targetVersion} within the caller's transaction.
     *
     * <p>Migration algorithm (PRD-WF-003 §7.4):
     * <ol>
     *   <li>Load the instance row (non-locking read; optimistic version bump guards concurrency).</li>
     *   <li>Resolve the source {@link RuntimeWorkflow} via {@link #resolvePinnedForInstance}
     *       (fails with {@link dev.vertique.workflow.exception.WorkflowVersionNotRunnableException} carrying
     *       the instance id when this node holds no code for the pin). Assert plan-hash matches.</li>
     *   <li>Resolve the target {@link RuntimeWorkflow} for the key of the instance's definition scope, id and the
     *       target version; a target this node holds no code for fails the same way.</li>
     *   <li>Reject a deprecated target version with a typed deprecated-version failure before any
     *       handler call or write.</li>
     *   <li>Look up the migration handler from the registry; throw
     *       {@link dev.vertique.workflow.migration.WorkflowMigrationHandlerMissingException} if
     *       absent.</li>
     *   <li>Reject terminal source status → throw
     *       {@link dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException}.</li>
     *   <li>Reject instances with active fork-branch state → same exception.</li>
     *   <li>Decode state, build {@link dev.vertique.workflow.migration.MigrationContext} (last 50
     *       history entries), invoke the handler.</li>
     *   <li>Determine the resume step id from the {@link dev.vertique.workflow.migration.MigrationResult}
     *       variant; validate it exists in the target plan for
     *       {@link dev.vertique.workflow.migration.MigrationResult.ContinueAt}.</li>
     *   <li>Persist the re-pinned snapshot via
     *       {@link dev.vertique.workflow.engine.spi.WorkflowInstanceRepository#migratePinAndState}.</li>
     *   <li>Append {@code WORKFLOW_MIGRATED} history.</li>
     *   <li>Drive transitions from the resume step in the target plan.</li>
     * </ol>
     *
     * <p>If no {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry} was provided (the
     * migration module was not installed), the call immediately fails with
     * {@link UnsupportedOperationException} before any database work.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     */
    Future<Void> migrate(WorkflowInstanceId id, long targetVersion, SqlClient tx) {
        return migrate(id, null, targetVersion, tx);
    }

    /**
     * Migrates a running instance to {@code targetVersion} of the definition of its id in {@code targetScope}
     * within the caller's transaction; the steps of {@link #migrate(WorkflowInstanceId, long, SqlClient)} apply.
     *
     * <p>The target scope must be the instance's start scope or the default scope, else the migration fails
     * with {@link dev.vertique.workflow.exception.WorkflowDefinitionException} naming both scopes. The target
     * definition must name the contract of the definition the instance is pinned to, else it fails with
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException}. When the two state
     * classes differ, the target's must extend the source's, as the state comparison decides; without a
     * comparison such a migration is refused. These checks run before the handler is looked up or called.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetScope   the scope of the target definition; {@code null} means the scope of the definition the
     *     instance is pinned to
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     */
    Future<Void> migrate(
            WorkflowInstanceId id, @jakarta.annotation.Nullable String targetScope, long targetVersion, SqlClient tx) {
        if (migrationRegistry == null) {
            return Future.failedFuture(
                    new UnsupportedOperationException(
                            "migrate() requires WorkflowMigrationRegistry; install WorkflowMigrationModule in your Dagger component"));
        }
        return instances.findById(id, tx).compose((Optional<WorkflowInstance> optInst) -> {
            if (optInst.isEmpty()) {
                return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
            }
            WorkflowInstance inst = optInst.get();

            // Binder-row bind (Contract Appendix C2): instance-owned migrate drive.
            return contextBinder.withBound(inst, null, () -> doMigrateSteps(id, targetScope, targetVersion, inst, tx));
        });
    }

    /**
     * Executes steps 2–6 and the {@link #doMigrate} call after the instance-owned binder-row bind
     * is in effect. Extracted so {@link #migrate} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param id            the workflow instance id
     * @param requestedScope the scope of the target definition; {@code null} means the instance's definition scope
     * @param targetVersion the definition version to migrate to
     * @param inst          the loaded source instance snapshot
     * @param tx            the active transaction
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     */
    private Future<Void> doMigrateSteps(
            WorkflowInstanceId id,
            @jakarta.annotation.Nullable String requestedScope,
            long targetVersion,
            WorkflowInstance inst,
            SqlClient tx) {
        // --- Step 2: Resolve source RuntimeWorkflow + plan-hash check ---
        RuntimeWorkflow sourceRw;
        try {
            sourceRw = resolvePinnedForInstance(inst);
            WorkflowPayloads.requirePlanHashMatches(inst, sourceRw);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        // --- Step 3: Resolve target RuntimeWorkflow in the target scope ---
        String targetScope = requestedScope != null ? requestedScope : inst.definitionScopeKey();
        if (!targetScope.equals(inst.definitionScopeKey())
                && !targetScope.equals(inst.scopeKey())
                && !WorkflowScopes.DEFAULT.equals(targetScope)) {
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                    "migration of instance '" + id.value() + "' to scope '" + targetScope + "' is refused: scope '"
                            + targetScope + "' is not visible from the instance's start scope '" + inst.scopeKey()
                            + "'; an instance migrates only to its start scope or the default scope"));
        }
        DefinitionKey targetKey;
        try {
            targetKey = new DefinitionKey(targetScope, inst.definitionId(), targetVersion);
        } catch (IllegalArgumentException e) {
            // A version no key can hold is not registered.
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException(
                    targetScope, inst.definitionId(), targetVersion, inst.id()));
        }
        RuntimeWorkflow targetRw;
        try {
            targetRw = PinnedPlans.resolve(registry, targetKey);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        // A deprecated target admits no new work; refuse before any handler call or write.
        if (registry.isDeprecated(targetKey)) {
            return Future.failedFuture(WorkflowEngine.deprecatedRefusal(targetKey));
        }

        // The target must name the source's contract and, when its state class differs, extend the source's;
        // refuse before any handler call or write.
        Future<Void> incompatible = refuseIncompatibleTarget(inst, sourceRw, targetRw, targetKey);
        if (incompatible != null) {
            return incompatible;
        }

        // --- Step 4: Look up migration handler ---
        String defId = inst.definitionId();
        long fromVersion = inst.definitionVersion();
        Optional<dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?>> handlerOpt =
                migrationRegistry.find(inst.definitionScopeKey(), defId, fromVersion, targetScope, targetVersion);
        if (handlerOpt.isEmpty()) {
            return Future.failedFuture(new dev.vertique.workflow.migration.WorkflowMigrationHandlerMissingException(
                    id, defId, fromVersion, targetVersion));
        }
        dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?> handler = handlerOpt.get();

        // --- Step 5: Reject terminal source status ---
        if (isTerminal(inst.status())) {
            return Future.failedFuture(new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                    id, "source instance is in terminal status: " + inst.status()));
        }

        // --- Step 6: Reject instances with active fork-branch state ---
        Future<Void> branchCheck;
        if (branchTokens != null) {
            branchCheck = branchTokens.findActiveByWorkflow(id, tx).compose(activeBranches -> {
                if (!activeBranches.isEmpty()) {
                    return Future.failedFuture(
                            new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                                    id, "source instance has active fork-branch state"));
                }
                return Future.succeededFuture();
            });
        } else {
            branchCheck = Future.succeededFuture();
        }

        return branchCheck.compose(v -> doMigrate(inst, sourceRw, targetRw, targetKey.scopeKey(), handler, tx));
    }

    /**
     * Checks that the target definition names the source's contract and that its state class is the source's or
     * extends it.
     *
     * @return a failed future describing the refusal, or {@code null} when the target is acceptable
     */
    private Future<Void> refuseIncompatibleTarget(
            WorkflowInstance inst, RuntimeWorkflow sourceRw, RuntimeWorkflow targetRw, DefinitionKey targetKey) {
        String subject = "migration of instance '" + inst.id().value() + "' from '" + inst.definitionId() + "' v"
                + inst.definitionVersion() + " in scope '" + inst.definitionScopeKey() + "' to v"
                + targetKey.definitionVersion() + " in scope '" + targetKey.scopeKey() + "' is refused: ";
        if (!sourceRw.contractName().equals(targetRw.contractName())) {
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException(
                    targetKey,
                    subject + "the target names contract '" + targetRw.contractName() + "' but the source names '"
                            + sourceRw.contractName() + "'"));
        }
        if (sourceRw.stateType().equals(targetRw.stateType())) {
            return null;
        }
        String states = "the target state type " + targetRw.stateType().getSimpleName() + " differs from the source's "
                + sourceRw.stateType().getSimpleName();
        if (stateCompatibility.isEmpty()) {
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(subject + states
                    + " and the state comparison is unavailable (no WorkflowStateCompatibility is" + " bound)"));
        }
        try {
            Optional<String> path =
                    stateCompatibility.orElseThrow().firstIncompatibility(sourceRw.stateType(), targetRw.stateType());
            if (path.isPresent()) {
                return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                        subject + states + " and does not extend it: property '" + path.orElseThrow()
                                + "' is removed or has another type"));
            }
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return null;
    }

    /**
     * Executes the state-transformation and persistence steps of a migration after all pre-checks
     * have passed. Extracts recent history, invokes the handler, resolves the resume step, persists
     * the updated snapshot, appends history, and drives transitions.
     *
     * @param inst      the source workflow instance (pre-migration snapshot)
     * @param sourceRw  the resolved source {@link RuntimeWorkflow}
     * @param targetRw  the resolved target {@link RuntimeWorkflow}
     * @param targetScope the scope the target definition was resolved in; the instance is re-pinned to it
     * @param handler   the migration handler to invoke
     * @param tx        the active transaction
     * @return a {@link Future} that completes when transitions finish
     */
    private Future<Void> doMigrate(
            WorkflowInstance inst,
            RuntimeWorkflow sourceRw,
            RuntimeWorkflow targetRw,
            String targetScope,
            dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?> handler,
            SqlClient tx) {
        WorkflowInstanceId id = inst.id();

        // --- Step 7: Decode state and build MigrationContext ---
        return history.listRecentByInstance(id, 50, tx).compose(recentRaw -> {
            // recentRaw is DESC; convert to ascending WorkflowHistoryEntrySummary list.
            List<dev.vertique.workflow.state.WorkflowHistoryEntrySummary> recentHistory = recentRaw.stream()
                    .sorted(java.util.Comparator.comparingLong(
                            dev.vertique.workflow.state.WorkflowHistoryEntry::sequence))
                    .map(e -> new dev.vertique.workflow.state.WorkflowHistoryEntrySummary(
                            e.sequence(), e.entryType(), e.recordedAt()))
                    .toList();

            dev.vertique.workflow.migration.MigrationContext ctx = new dev.vertique.workflow.migration.MigrationContext(
                    id,
                    inst.currentStepId(),
                    inst.definitionScopeKey(),
                    inst.definitionVersion(),
                    targetScope,
                    targetRw.plan().definitionVersion(),
                    sourceRw.plan().planHash(),
                    targetRw.plan().planHash(),
                    recentHistory);

            // Decode source state.
            Object sourceState;
            try {
                sourceState = Json.decodeValue(Buffer.buffer(inst.stateJson()), handler.sourceStateType());
            } catch (Exception e) {
                return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                        "Failed to decode source state for migration of instance '" + id.value() + "': "
                                + e.getMessage(),
                        e));
            }

            // --- Step 8 & 9: Invoke handler ---
            dev.vertique.workflow.migration.MigrationResult<?> result;
            try {
                @SuppressWarnings("unchecked")
                dev.vertique.workflow.migration.WorkflowMigrationHandler<Object, ?> typedHandler =
                        (dev.vertique.workflow.migration.WorkflowMigrationHandler<Object, ?>) handler;
                result = typedHandler.migrate(sourceState, ctx);
            } catch (Exception e) {
                return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                        "Migration handler '" + handler.getClass().getName() + "' threw for instance '" + id.value()
                                + "': " + e.getMessage(),
                        e));
            }

            // --- Step 10: Resolve resume step ---
            String sourceStepId = inst.currentStepId();
            String newStepId =
                    switch (result) {
                        case dev.vertique.workflow.migration.MigrationResult.AnchorAtInitial<?> a ->
                            targetRw.plan().initialStepId();
                        case dev.vertique.workflow.migration.MigrationResult.ContinueAt<?> c -> c.stepId();
                    };

            if (result instanceof dev.vertique.workflow.migration.MigrationResult.ContinueAt<?> c) {
                if (!targetRw.nodeById().containsKey(c.stepId())) {
                    return Future.failedFuture(
                            new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                                    id,
                                    "target step '" + c.stepId() + "' not found in target plan v"
                                            + targetRw.plan().definitionVersion()));
                }
            }

            String newStateJson = Json.encode(result.newState());
            long prevVersion = inst.version();
            // Build migrated snapshot with re-pinned definition version + plan hash.
            // WorkflowInstance has no withDefinitionVersion/withPlanHash methods (immutable record),
            // so we use the canonical constructor directly.
            WorkflowInstance migrated = new WorkflowInstance(
                    inst.id(),
                    inst.definitionId(),
                    targetRw.plan().definitionVersion(),
                    targetRw.plan().planHash(),
                    prevVersion + 1,
                    WorkflowStatus.RUNNING,
                    inst.businessKey(),
                    inst.subjectRef(),
                    newStepId,
                    null,
                    null,
                    null,
                    newStateJson,
                    null,
                    null,
                    inst.createdAt(),
                    clock.instant(),
                    // AC-6/FR-WF-CTX-011 immutability: migrate must never re-capture or alter
                    // metadata — carry the pre-migration instance's captured context unchanged.
                    inst.metadata(),
                    inst.scopeKey(),
                    targetScope);

            Instant now = clock.instant();
            String handlerClassName = handler.getClass().getName();
            WorkflowMigratedHistoryPayload histPayload = new WorkflowMigratedHistoryPayload(
                    inst.definitionVersion(),
                    sourceRw.plan().planHash(),
                    targetRw.plan().definitionVersion(),
                    targetRw.plan().planHash(),
                    sourceStepId,
                    newStepId,
                    handlerClassName,
                    now,
                    WorkflowPayloads.commandCorrelationId());

            // --- Step 11: Persist re-pinned snapshot ---
            return instances
                    .migratePinAndState(migrated, prevVersion, tx)
                    .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                            rowCount, "instance '" + id.value() + "' during migrate"))
                    // --- Step 12: Append WORKFLOW_MIGRATED history ---
                    .compose(v -> history.nextSequence(id, tx))
                    .compose(seq -> history.append(
                            new dev.vertique.workflow.state.WorkflowHistoryEntry(
                                    id,
                                    seq,
                                    dev.vertique.workflow.state.WorkflowEntryType.WORKFLOW_MIGRATED,
                                    Json.encode(histPayload),
                                    now),
                            tx))
                    // --- Step 13: Drive transitions in the target plan ---
                    .compose(v -> driver.driveTransitions(migrated, targetRw, tx));
        });
    }

    // --- Internal helpers ---

    /**
     * Resolves the pinned {@link RuntimeWorkflow} for the given instance. A pin this node holds no code for fails with
     * {@link dev.vertique.workflow.exception.WorkflowVersionNotRunnableException} carrying the actual instance id,
     * after a structured warning log so operators can identify broken pin registrations without decoding exception
     * stack traces.
     *
     * <p>This helper centralises the error-enrichment pattern for the migration source-definition
     * lookup; it is used only by the migration flow.
     *
     * @param inst the workflow instance whose pinned definition to resolve
     * @return the resolved {@link RuntimeWorkflow}
     * @throws dev.vertique.workflow.exception.WorkflowVersionNotRunnableException with the instance
     *     id populated if the local registry holds no plan for the pinned version
     */
    private RuntimeWorkflow resolvePinnedForInstance(WorkflowInstance inst) {
        try {
            return PinnedPlans.resolve(registry, inst);
        } catch (dev.vertique.workflow.exception.WorkflowVersionNotRunnableException e) {
            org.slf4j.LoggerFactory.getLogger(MigrationExecutor.class)
                    .warn(
                            "workflow instance {} pinned to unregistered definition {}@v{}",
                            inst.id().value(),
                            inst.definitionId(),
                            inst.definitionVersion());
            throw e;
        }
    }

    /**
     * Returns {@code true} if the given status is a terminal state (no further transitions
     * possible).
     *
     * @param status the status to check
     * @return {@code true} for terminal statuses
     */
    private static boolean isTerminal(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, COMPENSATED, CANCELLED, EXPIRED -> true;
            default -> false;
        };
    }
}
