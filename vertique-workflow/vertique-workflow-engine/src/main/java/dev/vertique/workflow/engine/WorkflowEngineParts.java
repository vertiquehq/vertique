// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything {@link WorkflowEngineFactory#create(WorkflowEngineParts)} needs to assemble an engine outside Dagger.
 *
 * <p>The class has no public constructor: {@link #builder()} is the only way to create it, so a part can be added
 * later without breaking a caller. {@link Builder#build()} fails fast with a {@link NullPointerException} that names
 * the first required part that is missing. Every part is required except the {@link Set} parts, which default to
 * empty except the optional intent kinds, which default to the engine default, the {@link Optional} parts, which default to empty, and the {@code propagator}, which may be {@code null}.
 * The {@code clock} has no default.
 */
public final class WorkflowEngineParts {

    private final WorkflowTransactionRunner<SqlClient> txRunner;
    private final WorkflowRegistry registry;
    private final WorkflowDefinitionRegistryStore definitionStore;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowDedupRepository<SqlClient> dedup;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final JoinStateRepository<SqlClient> joinStates;
    private final TimerStore<SqlClient> timerStore;
    private final TaskStore<SqlClient> taskStore;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final Clock clock;
    private final Set<WorkflowSideEffectRecorder<SqlClient>> recorders;
    private final Set<IntentKind> optionalIntentKinds;
    private final Optional<WorkflowMigrationRegistry> migrationRegistry;
    private final @Nullable DurableContextPropagator propagator;
    private final Set<InboundContextInitializer> inboundContextInitializers;
    private final Optional<BeanValidator> beanValidator;
    private final WorkflowTypeNames typeNames;
    private final Optional<WorkflowStateCompatibility> stateCompatibility;
    private final WorkflowExecutorConfig executorConfig;
    private final WorkflowRecordingConfig recordingConfig;
    private final Set<WorkflowExecutorObserver> executorObservers;
    private final CommandWakeups commandWakeups;
    private final @Nullable Vertx vertx;

    private WorkflowEngineParts(Builder b) {
        this.txRunner = Objects.requireNonNull(b.txRunner, "txRunner");
        this.registry = Objects.requireNonNull(b.registry, "registry");
        this.definitionStore = Objects.requireNonNull(b.definitionStore, "definitionStore");
        this.instances = Objects.requireNonNull(b.instances, "instances");
        this.history = Objects.requireNonNull(b.history, "history");
        this.dedup = Objects.requireNonNull(b.dedup, "dedup");
        this.branchTokens = Objects.requireNonNull(b.branchTokens, "branchTokens");
        this.joinStates = Objects.requireNonNull(b.joinStates, "joinStates");
        this.timerStore = Objects.requireNonNull(b.timerStore, "timerStore");
        this.taskStore = Objects.requireNonNull(b.taskStore, "taskStore");
        this.commands = Objects.requireNonNull(b.commands, "commands");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.recorders = Set.copyOf(b.recorders);
        this.optionalIntentKinds = Set.copyOf(b.optionalIntentKinds);
        this.migrationRegistry = b.migrationRegistry;
        this.propagator = b.propagator;
        this.inboundContextInitializers = Set.copyOf(b.inboundContextInitializers);
        this.beanValidator = b.beanValidator;
        this.typeNames = Objects.requireNonNull(b.typeNames, "typeNames");
        this.stateCompatibility = b.stateCompatibility;
        this.executorConfig = Objects.requireNonNull(b.executorConfig, "executorConfig");
        this.recordingConfig = Objects.requireNonNull(b.recordingConfig, "recordingConfig");
        this.executorObservers = Set.copyOf(b.executorObservers);
        this.commandWakeups = Objects.requireNonNull(b.commandWakeups, "commandWakeups");
        this.vertx = b.vertx;
    }

    /**
     * Starts a builder with every optional part empty.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** The transaction runner SPI that owns the transaction boundary and the layered exception mapping. */
    public WorkflowTransactionRunner<SqlClient> txRunner() {
        return txRunner;
    }

    /** The workflow registry used to resolve pinned runtime workflows. */
    public WorkflowRegistry registry() {
        return registry;
    }

    /** The durable definition registry store. */
    public WorkflowDefinitionRegistryStore definitionStore() {
        return definitionStore;
    }

    /** The instance repository SPI. */
    public WorkflowInstanceRepository<SqlClient> instances() {
        return instances;
    }

    /** The history repository SPI. */
    public WorkflowHistoryRepository<SqlClient> history() {
        return history;
    }

    /** The dedup repository SPI. */
    public WorkflowDedupRepository<SqlClient> dedup() {
        return dedup;
    }

    /** The branch-token repository SPI. */
    public BranchTokenRepository<SqlClient> branchTokens() {
        return branchTokens;
    }

    /** The join-state repository SPI. */
    public JoinStateRepository<SqlClient> joinStates() {
        return joinStates;
    }

    /** The timer store. */
    public TimerStore<SqlClient> timerStore() {
        return timerStore;
    }

    /** The task store. */
    public TaskStore<SqlClient> taskStore() {
        return taskStore;
    }

    /** The command repository SPI the execution probe reads. */
    public WorkflowCommandRepository<SqlClient> commands() {
        return commands;
    }

    /** The clock used to timestamp entries and state transitions. */
    public Clock clock() {
        return clock;
    }

    /** The side-effect recorders contributed by the application; empty by default. */
    public Set<WorkflowSideEffectRecorder<SqlClient>> recorders() {
        return recorders;
    }

    /**
     * The intent kinds the recorder router treats as optional; {@link WorkflowEngineFactory#DEFAULT_OPTIONAL_INTENT_KINDS}
     * by default, the same set Dagger wiring uses.
     */
    public Set<IntentKind> optionalIntentKinds() {
        return optionalIntentKinds;
    }

    /** The migration registry; when empty, {@code migrate} fails with {@link UnsupportedOperationException}. */
    public Optional<WorkflowMigrationRegistry> migrationRegistry() {
        return migrationRegistry;
    }

    /** The durable-context propagator used to capture branch metadata; {@code null} when none is supplied. */
    public @Nullable DurableContextPropagator propagator() {
        return propagator;
    }

    /** The initializers run on every bound drive; empty by default. */
    public Set<InboundContextInitializer> inboundContextInitializers() {
        return inboundContextInitializers;
    }

    /** The bean validator that checks coerced decision and start payloads; when empty, constraints are not enforced. */
    public Optional<BeanValidator> beanValidator() {
        return beanValidator;
    }

    /** The type-name index the engine resolves a decision payload's declared type name through. */
    public WorkflowTypeNames typeNames() {
        return typeNames;
    }

    /**
     * The comparison a migration applies to differing state classes; when empty, a migration between differing state
     * classes is refused.
     */
    public Optional<WorkflowStateCompatibility> stateCompatibility() {
        return stateCompatibility;
    }

    /**
     * The configuration of the engine's executor: instances, concurrency, poll interval, lease, attempts, backoff,
     * step cap and stuck age; {@link WorkflowExecutorConfig#defaults()} by default.
     */
    public WorkflowExecutorConfig executorConfig() {
        return executorConfig;
    }

    /**
     * The recording bounds: the payload size bound that applies to a signal's payload and explicit carrier, and the
     * most signals an instance may hold open; {@link WorkflowRecordingConfig#defaults()} by default.
     */
    public WorkflowRecordingConfig recordingConfig() {
        return recordingConfig;
    }

    /** The source that wakes the executor's instances when a command becomes pending or deferred; none by default. */
    public CommandWakeups commandWakeups() {
        return commandWakeups;
    }

    /** The observers the executor reports its events to; empty by default. */
    public Set<WorkflowExecutorObserver> executorObservers() {
        return executorObservers;
    }

    /**
     * The Vert.x instance the executor is deployed on when the owner starts it through
     * {@link WorkflowEngineHandle#executor()}; {@code null} when none is supplied, in which case that start fails.
     */
    public @Nullable Vertx vertx() {
        return vertx;
    }

    /** Collects the parts; a setter replaces the part it names and returns the builder. */
    public static final class Builder {
        private WorkflowTransactionRunner<SqlClient> txRunner;
        private WorkflowRegistry registry;
        private WorkflowDefinitionRegistryStore definitionStore;
        private WorkflowInstanceRepository<SqlClient> instances;
        private WorkflowHistoryRepository<SqlClient> history;
        private WorkflowDedupRepository<SqlClient> dedup;
        private BranchTokenRepository<SqlClient> branchTokens;
        private JoinStateRepository<SqlClient> joinStates;
        private TimerStore<SqlClient> timerStore;
        private TaskStore<SqlClient> taskStore;
        private WorkflowCommandRepository<SqlClient> commands;
        private Clock clock;
        private Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of();
        private Set<IntentKind> optionalIntentKinds = WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS;
        private Optional<WorkflowMigrationRegistry> migrationRegistry = Optional.empty();
        private @Nullable DurableContextPropagator propagator;
        private Set<InboundContextInitializer> inboundContextInitializers = Set.of();
        private Optional<BeanValidator> beanValidator = Optional.empty();
        private WorkflowTypeNames typeNames;
        private Optional<WorkflowStateCompatibility> stateCompatibility = Optional.empty();
        private WorkflowExecutorConfig executorConfig = WorkflowExecutorConfig.defaults();
        private WorkflowRecordingConfig recordingConfig = WorkflowRecordingConfig.defaults();
        private Set<WorkflowExecutorObserver> executorObservers = Set.of();
        private CommandWakeups commandWakeups = CommandWakeups.none();
        private @Nullable Vertx vertx;

        private Builder() {}

        /** Sets the transaction runner. */
        public Builder txRunner(WorkflowTransactionRunner<SqlClient> txRunner) {
            this.txRunner = txRunner;
            return this;
        }

        /** Sets the workflow registry. */
        public Builder registry(WorkflowRegistry registry) {
            this.registry = registry;
            return this;
        }

        /** Sets the durable definition registry store. */
        public Builder definitionStore(WorkflowDefinitionRegistryStore definitionStore) {
            this.definitionStore = definitionStore;
            return this;
        }

        /** Sets the instance repository. */
        public Builder instances(WorkflowInstanceRepository<SqlClient> instances) {
            this.instances = instances;
            return this;
        }

        /** Sets the history repository. */
        public Builder history(WorkflowHistoryRepository<SqlClient> history) {
            this.history = history;
            return this;
        }

        /** Sets the dedup repository. */
        public Builder dedup(WorkflowDedupRepository<SqlClient> dedup) {
            this.dedup = dedup;
            return this;
        }

        /** Sets the branch-token repository. */
        public Builder branchTokens(BranchTokenRepository<SqlClient> branchTokens) {
            this.branchTokens = branchTokens;
            return this;
        }

        /** Sets the join-state repository. */
        public Builder joinStates(JoinStateRepository<SqlClient> joinStates) {
            this.joinStates = joinStates;
            return this;
        }

        /** Sets the timer store. */
        public Builder timerStore(TimerStore<SqlClient> timerStore) {
            this.timerStore = timerStore;
            return this;
        }

        /** Sets the task store. */
        public Builder taskStore(TaskStore<SqlClient> taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /** Sets the command repository. */
        public Builder commands(WorkflowCommandRepository<SqlClient> commands) {
            this.commands = commands;
            return this;
        }

        /** Sets the clock. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Sets the side-effect recorders. */
        public Builder recorders(Set<WorkflowSideEffectRecorder<SqlClient>> recorders) {
            this.recorders = Objects.requireNonNull(recorders, "recorders");
            return this;
        }

        /** Sets the intent kinds the recorder router treats as optional. */
        public Builder optionalIntentKinds(Set<IntentKind> optionalIntentKinds) {
            this.optionalIntentKinds = Objects.requireNonNull(optionalIntentKinds, "optionalIntentKinds");
            return this;
        }

        /** Sets the migration registry. */
        public Builder migrationRegistry(Optional<WorkflowMigrationRegistry> migrationRegistry) {
            this.migrationRegistry = Objects.requireNonNull(migrationRegistry, "migrationRegistry");
            return this;
        }

        /** Sets the durable-context propagator; {@code null} for callers that do not capture branch metadata. */
        public Builder propagator(@Nullable DurableContextPropagator propagator) {
            this.propagator = propagator;
            return this;
        }

        /** Sets the inbound context initializers. */
        public Builder inboundContextInitializers(Set<InboundContextInitializer> inboundContextInitializers) {
            this.inboundContextInitializers =
                    Objects.requireNonNull(inboundContextInitializers, "inboundContextInitializers");
            return this;
        }

        /** Sets the bean validator. */
        public Builder beanValidator(Optional<BeanValidator> beanValidator) {
            this.beanValidator = Objects.requireNonNull(beanValidator, "beanValidator");
            return this;
        }

        /** Sets the type-name index. */
        public Builder typeNames(WorkflowTypeNames typeNames) {
            this.typeNames = typeNames;
            return this;
        }

        /** Sets the state compatibility comparison. */
        public Builder stateCompatibility(Optional<WorkflowStateCompatibility> stateCompatibility) {
            this.stateCompatibility = Objects.requireNonNull(stateCompatibility, "stateCompatibility");
            return this;
        }

        /** Sets the executor configuration. */
        public Builder executorConfig(WorkflowExecutorConfig executorConfig) {
            this.executorConfig = Objects.requireNonNull(executorConfig, "executorConfig");
            return this;
        }

        /** Sets the recording configuration. */
        public Builder recordingConfig(WorkflowRecordingConfig recordingConfig) {
            this.recordingConfig = Objects.requireNonNull(recordingConfig, "recordingConfig");
            return this;
        }

        /** Sets the source that wakes the executor's instances; the default never wakes them. */
        public Builder commandWakeups(CommandWakeups commandWakeups) {
            this.commandWakeups = Objects.requireNonNull(commandWakeups, "commandWakeups");
            return this;
        }

        /** Sets the observers the executor reports its events to. */
        public Builder executorObservers(Set<WorkflowExecutorObserver> executorObservers) {
            this.executorObservers = Objects.requireNonNull(executorObservers, "executorObservers");
            return this;
        }

        /** Sets the Vert.x instance the executor is deployed on; {@code null} for an engine that never starts one. */
        public Builder vertx(@Nullable Vertx vertx) {
            this.vertx = vertx;
            return this;
        }

        /**
         * Creates the parts.
         *
         * @return the parts
         * @throws NullPointerException naming the first required part that was not set
         */
        public WorkflowEngineParts build() {
            return new WorkflowEngineParts(this);
        }
    }
}
