// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.executor.Savepoints;
import dev.vertique.workflow.engine.history.DecisionLog;
import dev.vertique.workflow.engine.registry.FailingMarks;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator owning the instance-migration flow.
 *
 * <p>{@link #record} writes a {@code MIGRATION} command in the caller's transaction and runs no workflow code.
 * {@link #prepare} and {@link #execute} run a claimed command in an executor segment: the first re-checks the pin and
 * the plans the executor made resident, the second runs the checks, the migration handler and the re-pin under a
 * savepoint, so that an invalid migration is rejected with the instance unchanged on its previous version.
 *
 * <p>Migration is <b>opt-in</b>: the {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry}
 * binding is declared via {@link WorkflowEngineModule#optionalMigrationRegistry()}, so the injected
 * {@link Optional} is empty when the application did not install
 * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}. When the registry is absent,
 * {@link #record} fails immediately with {@link UnsupportedOperationException} before any database
 * work. Apps that never call {@code migrate} need not wire the module.
 *
 * <p>This service drives the state machine forward after applying a migration via
 * {@link WorkflowTransitionDriver#driveTransitions}, so it injects the driver <em>eagerly</em>.
 * Nothing references this service back, so it is a leaf in the construction order and forms no Dagger
 * cycle.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class MigrationExecutor {

    private static final Logger log = LoggerFactory.getLogger(MigrationExecutor.class);

    /** The longest target scope a recording accepts, in characters. */
    private static final int MAX_SCOPE_LENGTH = 255;

    // --- Dependencies ---

    /**
     * Optional migration registry — {@code null} when the application did not install
     * {@link dev.vertique.workflow.migration.WorkflowMigrationModule}. When {@code null},
     * {@link #record} fails immediately with {@link UnsupportedOperationException}.
     */
    @jakarta.annotation.Nullable
    private final dev.vertique.workflow.migration.WorkflowMigrationRegistry migrationRegistry;

    private final WorkflowRegistry registry;
    private final Function<DefinitionKey, Optional<DefinitionFailure>> failing;

    /**
     * The reads of definition rows on the migration's transaction, which tell whether the target is deprecated;
     * {@code null} when the store cannot read on a transaction, which leaves no stored status to refuse a target by.
     */
    @jakarta.annotation.Nullable
    private final TransactionalDefinitionReads<SqlClient> definitionReads;

    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowCommandRepository<SqlClient> commands;

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
     * Binder-row seam for the migration drive. Never {@code null} — the engine assembly seam substitutes
     * {@link WorkflowContextBinder#noop()} when durable-context wiring is not exercised, so {@link #execute} always calls
     * {@link WorkflowContextBinder#withRecordedBase} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /** Takes the actor a recorded migration is logged with from the identity of the caller. */
    private final CommandActors actors;

    /** Logs the migration: its own patch and the full state after it. */
    private final DecisionLog decisions;

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
     * @param failing this node's evaluation of the versions of the scopes other than the default one that deployed code
     *     conflicts with; a migration into a version it reports as failing is refused
     * @param commands the command repository whose execution lock a migration takes before it reads or writes the
     *     instance
     * @param definitionReads the reads of definition rows on the migration's transaction, by which a deprecated target
     *     is refused; may be {@code null}
     * @param actors takes the actor a recorded migration is logged with from the caller's identity
     * @param decisions the decision log that records the migration's patch and baseline
     */
    MigrationExecutor(
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            @jakarta.annotation.Nullable BranchTokenRepository<SqlClient> branchTokens,
            WorkflowTransitionDriver driver,
            Clock clock,
            WorkflowContextBinder contextBinder,
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility,
            Function<DefinitionKey, Optional<DefinitionFailure>> failing,
            WorkflowCommandRepository<SqlClient> commands,
            @jakarta.annotation.Nullable TransactionalDefinitionReads<SqlClient> definitionReads,
            CommandActors actors,
            DecisionLog decisions) {
        this.decisions = decisions;
        this.actors = actors;
        // migrationRegistryOpt is null only from test-only constructors that delegate with null;
        // Dagger always provides a non-null Optional via @BindsOptionalOf.
        this.migrationRegistry = migrationRegistryOpt != null ? migrationRegistryOpt.orElse(null) : null;
        this.registry = registry;
        this.failing = failing;
        this.definitionReads = definitionReads;
        this.instances = instances;
        this.history = history;
        this.commands = commands;
        this.branchTokens = branchTokens;
        this.driver = driver;
        this.clock = clock;
        this.contextBinder = contextBinder;
        this.stateCompatibility = stateCompatibility != null ? stateCompatibility : Optional.empty();
    }

    /**
     * Constructs the migration executor with the failing marks of the node's plan cache, read when a migration runs.
     *
     * @param migrationRegistryOpt the optional migration registry
     * @param registry the workflow registry
     * @param instances the instance repository
     * @param history the history repository
     * @param branchTokens the branch-token repository; may be {@code null}
     * @param driver the transition driver
     * @param clock the clock
     * @param contextBinder the binder-row seam
     * @param stateCompatibility the state comparison; empty refuses a migration between differing state classes
     * @param marks the failing marks, resolved when a migration runs because building them builds the plan cache
     * @param commands the command repository whose execution lock a migration takes
     * @param definitionReads the reads of definition rows on a transaction, when the store offers them
     * @param actors takes the actor a recorded migration is logged with from the caller's identity
     * @param decisions the decision log that records the migration's patch and baseline
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
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility,
            Provider<FailingMarks> marks,
            WorkflowCommandRepository<SqlClient> commands,
            Optional<TransactionalDefinitionReads<SqlClient>> definitionReads,
            CommandActors actors,
            DecisionLog decisions) {
        this(
                migrationRegistryOpt,
                registry,
                instances,
                history,
                branchTokens,
                driver,
                clock,
                contextBinder,
                stateCompatibility,
                key -> marks.get().find(key),
                commands,
                definitionReads.orElse(null),
                actors,
                decisions);
    }

    /**
     * Constructs a migration executor that holds no failing version.
     *
     * @param migrationRegistryOpt the optional migration registry
     * @param registry the workflow registry
     * @param instances the instance repository
     * @param history the history repository
     * @param branchTokens the branch-token repository; may be {@code null}
     * @param driver the transition driver
     * @param clock the clock
     * @param contextBinder the binder-row seam
     * @param stateCompatibility the state comparison; empty refuses a migration between differing state classes
     * @param commands the command repository whose execution lock a migration takes
     * @param decisions the decision log that records the migration's patch and baseline
     */
    MigrationExecutor(
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            @jakarta.annotation.Nullable BranchTokenRepository<SqlClient> branchTokens,
            WorkflowTransitionDriver driver,
            Clock clock,
            WorkflowContextBinder contextBinder,
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility,
            WorkflowCommandRepository<SqlClient> commands,
            DecisionLog decisions) {
        this(
                migrationRegistryOpt,
                registry,
                instances,
                history,
                branchTokens,
                driver,
                clock,
                contextBinder,
                stateCompatibility,
                key -> Optional.empty(),
                commands,
                null,
                CommandActors.ambient(),
                decisions);
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
     * @param commands the command repository whose execution lock a migration takes
     * @param decisions the decision log that records the migration's patch and baseline
     */
    MigrationExecutor(
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            @jakarta.annotation.Nullable BranchTokenRepository<SqlClient> branchTokens,
            WorkflowTransitionDriver driver,
            Clock clock,
            WorkflowContextBinder contextBinder,
            WorkflowCommandRepository<SqlClient> commands,
            DecisionLog decisions) {
        this(
                migrationRegistryOpt,
                registry,
                instances,
                history,
                branchTokens,
                driver,
                clock,
                contextBinder,
                Optional.empty(),
                commands,
                decisions);
    }

    // --- Recording ---

    /**
     * Records a migration of an instance to {@code targetVersion} in the caller's transaction and returns once it is
     * recorded. Under the instance's execution lock, an ended instance is refused; nothing else is checked here, and no
     * workflow code runs. An executor runs the migration afterwards, in its own segment, in queue order, and rejects it
     * with the instance unchanged when it is not valid.
     *
     * <p>If no {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry} was provided (the migration module was
     * not installed), the call immediately fails with {@link UnsupportedOperationException} before any database work.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetScope   the scope of the target definition; {@code null} means the scope of the definition the
     *     instance is pinned to
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the migration is recorded
     */
    Future<Void> record(
            WorkflowInstanceId id, @jakarta.annotation.Nullable String targetScope, long targetVersion, SqlClient tx) {
        if (migrationRegistry == null) {
            return Future.failedFuture(
                    new UnsupportedOperationException(
                            "migrate() requires WorkflowMigrationRegistry; install WorkflowMigrationModule in your Dagger component"));
        }
        if (targetScope != null && targetScope.length() > MAX_SCOPE_LENGTH) {
            // The scope is stored with the command: bound it, and echo none of it
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                    "migration of instance '" + id.value() + "' is refused: the target scope is longer than "
                            + MAX_SCOPE_LENGTH + " characters"));
        }
        // The execution lock comes first, before the instance is read and before any write.
        return commands.lockInstance(id, tx)
                .compose(locked -> instances.findById(id, tx))
                .compose((Optional<WorkflowInstance> optInst) -> {
                    if (optInst.isEmpty()) {
                        return Future.<Void>failedFuture(new WorkflowInstanceNotFoundException(id));
                    }
                    WorkflowInstance inst = optInst.get();
                    if (EndedInstanceOutcomes.isEnded(inst.status())) {
                        return Future.<Void>failedFuture(
                                new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                                        id, "source instance is in terminal status: " + inst.status()));
                    }
                    if (targetScope != null
                            && !targetScope.equals(inst.definitionScopeKey())
                            && !targetScope.equals(inst.scopeKey())
                            && !WorkflowScopes.DEFAULT.equals(targetScope)) {
                        // a scope the instance can never migrate into: no row is stored and no plan of it is built
                        return Future.<Void>failedFuture(
                                new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                                        id, "target scope not admitted"));
                    }
                    return commands.unsettledTargets(id, "MIGRATION", tx).compose(pending -> {
                        if (pending.isEmpty()) {
                            return actors.recording(
                                            "MIGRATION",
                                            actor ->
                                                    commands.recordMigration(id, targetVersion, targetScope, actor, tx))
                                    .<Void>mapEmpty();
                        }
                        // A migration is on its way: the same one is recorded once, another one waits for it
                        boolean same =
                                pending.stream().anyMatch(stored -> isTarget(stored, targetScope, targetVersion));
                        return same
                                ? Future.<Void>succeededFuture()
                                : Future.<Void>failedFuture(
                                        new dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException(
                                                id, "another migration of the instance is already pending"));
                    });
                });
    }

    /** Whether the stored target of a recorded migration is the given target. */
    private static boolean isTarget(String stored, @jakarta.annotation.Nullable String scope, long version) {
        try {
            io.vertx.core.json.JsonObject json = new io.vertx.core.json.JsonObject(stored);
            Long storedVersion = json.getLong("version");
            return storedVersion != null
                    && storedVersion == version
                    && java.util.Objects.equals(json.getString("scope"), scope);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- Execution in a segment ---

    /** What the segment of a {@code MIGRATION} command came to. */
    sealed interface Result {

        /**
         * The instance is re-pinned to the target and driven to its next wait.
         *
         * @param target the target plan the instance is pinned to now
         */
        record Applied(RuntimeWorkflow target) implements Result {}

        /**
         * The migration was rolled back to its savepoint and the instance is unchanged.
         *
         * @param reason the sanitized reason
         */
        record Rejected(String reason) implements Result {}
    }

    /**
     * A migration whose pin and plans are what the executor made resident, ready to run.
     *
     * @param inst      the instance, read under the execution lock
     * @param command   the fenced command
     * @param source    the plan the instance is pinned to
     * @param targetKey the definition the instance moves to, or {@code null} when the recorded target names none
     * @param target    the plan of the target, or {@code null} when {@code targetKey} is
     * @param recorded  the durable context recorded with the command
     */
    record Prepared(
            WorkflowInstance inst,
            SegmentCommand command,
            RuntimeWorkflow source,
            @jakarta.annotation.Nullable DefinitionKey targetKey,
            @jakarta.annotation.Nullable RuntimeWorkflow target,
            @jakarta.annotation.Nullable dev.vertique.core.context.DurableMetadata recorded) {}

    /** The target of a recorded migration. */
    private record Target(@jakarta.annotation.Nullable String scope, long version) {}

    /** A migration refusal found by a check, with the fixed statement of why; never carries a failure's text. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient CommandErrors.MigrationRejection rejection;

        Refused(CommandErrors.MigrationRejection rejection) {
            super(rejection.name(), null, false, false);
            this.rejection = rejection;
        }
    }

    /**
     * Checks, without any I/O, that the instance is still pinned to the plan the executor made resident and that the
     * source and target plans resolve now. Nothing is written for a migration that is not prepared, so the caller
     * releases the claim.
     *
     * @param head    the peeked head the claim came from
     * @param command the fenced command
     * @param inst    the instance, read under the execution lock
     * @return the prepared migration, or empty when the pin changed since the executor warmed the plans, or a plan no
     *     longer resolves
     * @throws IllegalStateException when the recorded migration cannot be read
     */
    Optional<Prepared> prepare(CommandHead head, SegmentCommand command, WorkflowInstance inst) {
        if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                || !inst.definitionId().equals(head.definitionId())
                || inst.definitionVersion() != head.definitionVersion()
                || !java.util.Objects.equals(inst.planHash(), head.planHash())) {
            return Optional.empty();
        }
        Target target;
        dev.vertique.core.context.DurableMetadata recorded;
        try {
            io.vertx.core.json.JsonObject json = new io.vertx.core.json.JsonObject(command.targetJson());
            target = new Target(json.getString("scope"), json.getLong("version"));
            recorded = WorkflowContextBinder.decodeCarrier(command.contextJson());
        } catch (RuntimeException e) {
            throw new IllegalStateException("The recorded migration " + command.commandId() + " of workflow instance "
                    + inst.id().value() + " cannot be read");
        }
        RuntimeWorkflow sourceRw;
        try {
            sourceRw = resolvePinnedForInstance(inst);
            WorkflowPayloads.requirePlanHashMatches(inst, sourceRw);
        } catch (dev.vertique.workflow.exception.WorkflowVersionNotRunnableException
                | dev.vertique.workflow.exception.WorkflowPlanHashDriftException e) {
            return Optional.empty();
        }
        DefinitionKey targetKey;
        try {
            targetKey = new DefinitionKey(
                    target.scope() != null ? target.scope() : inst.definitionScopeKey(),
                    inst.definitionId(),
                    target.version());
        } catch (IllegalArgumentException e) {
            // A scope or version no key can hold names no definition: the migration is rejected.
            return Optional.of(new Prepared(inst, command, sourceRw, null, null, recorded));
        }
        RuntimeWorkflow targetRw;
        try {
            targetRw = PinnedPlans.resolve(registry, targetKey);
        } catch (dev.vertique.workflow.exception.WorkflowVersionNotRunnableException
                | dev.vertique.workflow.exception.WorkflowPlanHashDriftException e) {
            return Optional.empty();
        }
        return Optional.of(new Prepared(inst, command, sourceRw, targetKey, targetRw, recorded));
    }

    /**
     * Runs a prepared migration in the segment's transaction, with the recorded context bound. Everything runs under a
     * savepoint: a missing handler, a handler whose state types are not those of the definitions, a deprecated or
     * inadmissible target, a source that cannot be migrated, a handler or re-pin that fails, and an overrun of the step
     * cap roll back to it and reject the command with the instance unchanged.
     *
     * @param prepared the prepared migration
     * @param tx       the segment's transaction handle
     * @return the result the engine settles the command by
     */
    Future<Result> execute(Prepared prepared, SqlClient tx) {
        WorkflowInstance inst = prepared.inst();
        if (prepared.targetKey() == null) {
            return Future.succeededFuture(new Result.Rejected(
                    CommandErrors.migrationRejected(CommandErrors.MigrationRejection.TARGET_NOT_ADMITTED)));
        }
        return contextBinder.withRecordedBase(
                inst, prepared.command().commandId(), null, prepared.recorded(), () -> Savepoints.attemptAnyFailure(
                                tx,
                                () -> migrateUnderSavepoint(
                                        inst, prepared.source(), prepared.target(), prepared.targetKey(), tx))
                        .map(attempt -> switch (attempt) {
                            case Savepoints.Attempt.Applied<Void> applied -> new Result.Applied(prepared.target());
                            case Savepoints.Attempt.Contained<Void> contained -> {
                                log.warn(
                                        "Migration (command {}) of instance {} failed and was rejected: {}",
                                        prepared.command().commandId(),
                                        inst.id().value(),
                                        contained.cause().getClass().getName());
                                log.debug("Migration failure", contained.cause());
                                yield new Result.Rejected(reasonOf(contained, tx));
                            }
                        }));
    }

    private String reasonOf(Savepoints.Attempt.Contained<Void> contained, SqlClient tx) {
        if (contained.kind() == Savepoints.Containment.STEP_LIMIT) {
            return CommandErrors.migrationStepLimit(driver.stepCap(tx));
        }
        Throwable cause = contained.cause();
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof Refused refused) {
                return CommandErrors.migrationRejected(refused.rejection);
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return CommandErrors.migrationRejected(CommandErrors.MigrationRejection.FAILED);
    }

    /**
     * The checks that need the plans and the handler, and the migration itself: the target must be visible to the
     * instance, admitted, available (not deprecated or retired), of the source's contract and, when its state class differs, an extension of
     * the source's; a handler must exist and name the definitions' state types; the source must be neither ended nor
     * failed and must hold no active branch. Then the handler is invoked, the instance is re-pinned through pin
     * admission and driven from the resume step in the target plan.
     */
    private Future<Void> migrateUnderSavepoint(
            WorkflowInstance inst,
            RuntimeWorkflow sourceRw,
            RuntimeWorkflow targetRw,
            DefinitionKey targetKey,
            SqlClient tx) {
        WorkflowInstanceId id = inst.id();
        String targetScope = targetKey.scopeKey();
        if (!targetScope.equals(inst.definitionScopeKey())
                && !targetScope.equals(inst.scopeKey())
                && !WorkflowScopes.DEFAULT.equals(targetScope)) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.TARGET_NOT_ADMITTED));
        }
        // A version this node evaluated as failing admits no new work either.
        if (failing.apply(targetKey).isPresent()) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.TARGET_NOT_ADMITTED));
        }
        // A target that is not available admits no new work, whatever plan this node still holds for it; refuse before
        // any handler call or write.
        return targetStatus(targetKey, tx)
                .compose(status -> status == DefinitionStatus.DEPRECATED
                        ? Future.<Void>failedFuture(new Refused(CommandErrors.MigrationRejection.TARGET_DEPRECATED))
                        : status != null && status != DefinitionStatus.AVAILABLE
                                ? Future.<Void>failedFuture(
                                        new Refused(CommandErrors.MigrationRejection.TARGET_NOT_ADMITTED))
                                : migrateToAdmittedTarget(inst, sourceRw, targetRw, targetKey, tx));
    }

    /** The status of the stored row of the target, read on the migration's transaction; {@code null} when none. */
    private Future<DefinitionStatus> targetStatus(DefinitionKey target, SqlClient tx) {
        if (definitionReads == null) {
            return Future.succeededFuture(null);
        }
        return definitionReads
                .selectionRead(target.scopeKey(), target.definitionId(), tx)
                .map(read -> read.versions().stream()
                        .filter(row -> row.key().equals(target))
                        .map(row -> row.status())
                        .findFirst()
                        .orElse(null));
    }

    private Future<Void> migrateToAdmittedTarget(
            WorkflowInstance inst,
            RuntimeWorkflow sourceRw,
            RuntimeWorkflow targetRw,
            DefinitionKey targetKey,
            SqlClient tx) {
        WorkflowInstanceId id = inst.id();
        String targetScope = targetKey.scopeKey();
        // The target must name the source's contract and, when its state class differs, extend the source's.
        if (!incompatibilityFree(sourceRw, targetRw)) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.TARGET_NOT_ADMITTED));
        }
        if (migrationRegistry == null) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.HANDLER_MISSING));
        }
        Optional<dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?>> handlerOpt;
        try {
            handlerOpt = migrationRegistry.find(
                    inst.definitionScopeKey(),
                    inst.definitionId(),
                    inst.definitionVersion(),
                    targetScope,
                    targetKey.definitionVersion());
        } catch (dev.vertique.workflow.exception.WorkflowMigrationStateTypeMismatchException e) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.STATE_TYPE_MISMATCH));
        }
        if (handlerOpt.isEmpty()) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.HANDLER_MISSING));
        }
        dev.vertique.workflow.migration.WorkflowMigrationHandler<?, ?> handler = handlerOpt.get();
        if (!handler.sourceStateType().equals(sourceRw.stateType())
                || !handler.targetStateType().equals(targetRw.stateType())) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.STATE_TYPE_MISMATCH));
        }
        // A source that has failed or ended cannot be migrated.
        if (isTerminal(inst.status())) {
            return Future.failedFuture(new Refused(CommandErrors.MigrationRejection.INSTANCE_STATE));
        }
        Future<Void> branchCheck;
        if (branchTokens != null) {
            branchCheck = branchTokens
                    .findActiveByWorkflow(id, tx)
                    .compose(activeBranches -> activeBranches.isEmpty()
                            ? Future.<Void>succeededFuture()
                            : Future.<Void>failedFuture(new Refused(CommandErrors.MigrationRejection.INSTANCE_STATE)));
        } else {
            branchCheck = Future.succeededFuture();
        }
        return branchCheck.compose(v -> doMigrate(inst, sourceRw, targetRw, targetScope, handler, tx));
    }

    /** Whether the target names the source's contract and its state class is the source's or extends it. */
    private boolean incompatibilityFree(RuntimeWorkflow sourceRw, RuntimeWorkflow targetRw) {
        if (!sourceRw.contractName().equals(targetRw.contractName())) {
            return false;
        }
        if (sourceRw.stateType().equals(targetRw.stateType())) {
            return true;
        }
        if (stateCompatibility.isEmpty()) {
            return false;
        }
        return stateCompatibility
                .orElseThrow()
                .firstIncompatibility(sourceRw.stateType(), targetRw.stateType())
                .isEmpty();
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

        // --- Decode state and build MigrationContext ---
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

            // --- Invoke handler ---
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

            // --- Resolve resume step ---
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
                    // A migration never re-captures or alters the metadata: it carries the pre-migration
                    // instance's captured context unchanged.
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

            // --- Persist re-pinned snapshot ---
            return instances
                    .migratePinAndState(migrated, prevVersion, tx)
                    .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                            rowCount, "instance '" + id.value() + "' during migrate"))
                    // --- Log the migration: its patch under both versions' redaction, then the full state ---
                    .compose(v -> logMigration(inst, migrated, sourceStepId, tx))
                    // --- Append WORKFLOW_MIGRATED history ---
                    .compose(v -> history.nextSequence(id, tx))
                    .compose(seq -> history.append(
                            new dev.vertique.workflow.state.WorkflowHistoryEntry(
                                    id,
                                    seq,
                                    dev.vertique.workflow.state.WorkflowEntryType.WORKFLOW_MIGRATED,
                                    Json.encode(histPayload),
                                    now),
                            tx))
                    // --- Drive transitions in the target plan ---
                    .compose(v -> driver.driveTransitions(migrated, targetRw, tx));
        });
    }

    /** Logs a migration to the decision log: the patch of its state change, then the full state after it. */
    private Future<Void> logMigration(
            WorkflowInstance source,
            WorkflowInstance migrated,
            @jakarta.annotation.Nullable String stepId,
            SqlClient tx) {
        DefinitionKey sourceKey =
                new DefinitionKey(source.definitionScopeKey(), source.definitionId(), source.definitionVersion());
        DefinitionKey targetKey =
                new DefinitionKey(migrated.definitionScopeKey(), migrated.definitionId(), migrated.definitionVersion());
        SegmentCommand command = decisions.commandOf(tx);
        DecisionLog.Decision decision = new DecisionLog.Decision(
                migrated.id(),
                command == null ? null : command.commandId(),
                stepId == null ? "start" : stepId,
                "migrated",
                targetKey,
                migrated.planHash(),
                source.version(),
                migrated.version(),
                null);
        return decisions.logMigration(
                decision,
                sourceKey,
                new io.vertx.core.json.JsonObject(source.stateJson()),
                new io.vertx.core.json.JsonObject(migrated.stateJson()),
                tx);
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

    private static boolean isTerminal(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, COMPENSATED, CANCELLED, EXPIRED -> true;
            default -> false;
        };
    }
}
