// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Default concrete implementation of {@link WorkflowRegistry}.
 *
 * <p>This class is instantiated by {@code WorkflowCoreModule.registry(...)} and is the single
 * in-process registry for all registered workflow definitions. It validates each definition at
 * registration time and rejects invalid plans. After plan validation, and before the publish phase,
 * it also runs every contributed {@link WorkflowDefinitionValidator} against the candidate
 * definition.
 *
 * <p>Any number of definition ids and versions may name one contract. A version must name the
 * same contract as every earlier version of its id in the same scope, and every signal its plan
 * waits for must be declared on that contract. Another scope may hold the same id under another
 * contract.
 *
 * <p>All versions of one scope and id use one state class. When a scope-owned definition and the default
 * scope's definition of the same id name the same contract and different state classes, the scope-owned
 * state class must extend the default's, as a {@link WorkflowStateCompatibility} decides; the rule is
 * checked when the second of the two registers. Without a comparison, differing state classes are
 * refused. Definitions of different contracts are never compared.
 *
 * <p>Internal storage, each map keyed by the scope and the definition id:
 * <ul>
 *   <li>{@code byDefinition} — {@link ConcurrentHashMap} keyed by scope and definitionId, inner
 *       {@link ConcurrentSkipListMap} keyed by version; allows efficient lookup of the highest
 *       version via {@code lastEntry()}, and answers {@link #contractOf(String, String)} from the
 *       first version it holds.</li>
 *   <li>{@code sources} — the registered definition objects per scope, id and version; also the
 *       source of {@link #definitionClass(Class)}.</li>
 *   <li>{@code deprecated} — set of version numbers per scope and definitionId that are marked
 *       deprecated; always a subset of the registered versions and mutated only under
 *       {@code writeLock}.</li>
 * </ul>
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #register(WorkflowDefinition)} and {@link #register(String, WorkflowDefinition)} are
 *       safe to call at any time after construction. Multiple callers can invoke them concurrently;
 *       the underlying publication is serialized by {@code writeLock}.</li>
 *   <li>Reads ({@link #resolve(DefinitionKey)}, {@link #find(DefinitionKey)},
 *       {@link #allRegistered}) are non-blocking relative to each other (they hold only
 *       {@code readLock}) but block briefly when a concurrent {@code register()} is in its
 *       publish phase.</li>
 *   <li>A failed {@code register()} rolls back atomically — concurrent readers never observe
 *       the partially-published-and-then-rolled-back version.</li>
 * </ul>
 *
 * <p>Per-definition callbacks live on {@link RuntimeWorkflow#callbacks()} and are the only
 * supported lookup path: callback ids are scoped to {@code (stepId, role)} which collides across
 * definitions, so a global aggregate would silently overwrite or require ambient definition
 * context to disambiguate. Callers that have a {@link RuntimeWorkflow} (i.e., the engine after a
 * {@link #resolve(DefinitionKey)} call) get a coherent per-definition view.
 *
 * <p>Why {@link ReentrantReadWriteLock} rather than lock-free structures alone:
 * {@code register()} is intrinsically multi-step — it checks the new version against the versions
 * of its scope and definition id that are already registered, then inserts it into {@code byDefinition} and
 * {@code sources}. Without a write-lock, two concurrent registrations of one id could both pass the
 * contract check, and a concurrent {@code resolve()} could observe a half-published
 * version. The write-lock serializes the publish phase, while allowing concurrent reads during
 * the common steady-state.
 */
public final class DefaultWorkflowRegistry implements WorkflowRegistry {

    /** The most undeclared signal names a refusal message repeats. */
    private static final int MAX_REPORTED_SIGNALS = 5;

    // --- Internal storage ---

    /** The scope and definition id that the registration rules and the version maps are keyed by. */
    private record ScopedId(String scopeKey, String definitionId) {}

    /** Keyed by (scope, definitionId) → (version → RuntimeWorkflow). */
    private final ConcurrentHashMap<ScopedId, ConcurrentSkipListMap<Long, RuntimeWorkflow>> byDefinition =
            new ConcurrentHashMap<>();

    /**
     * Keyed by (scope, definitionId) → (version → the definition that was registered). Guarded by
     * {@link #rwLock} together with {@link #byDefinition}; it holds exactly the registered versions.
     */
    private final HashMap<ScopedId, HashMap<Long, WorkflowDefinition<?, ?>>> sources = new HashMap<>();

    /**
     * Keyed by (scope, definitionId) → (version → the binding of the registered plan: the binary name of its state and
     * payload classes mapped to the name its hash used). Guarded by {@link #rwLock} together with
     * {@link #byDefinition}; it holds exactly the registered versions.
     */
    private final HashMap<ScopedId, HashMap<Long, Map<String, String>>> bindings = new HashMap<>();

    /**
     * Keyed by (scope, definitionId) → versions marked deprecated. Only registered versions are ever
     * present; guarded by {@link #rwLock} together with {@link #byDefinition}.
     */
    private final ConcurrentHashMap<ScopedId, Set<Long>> deprecated = new ConcurrentHashMap<>();

    /**
     * Keyed by (scope, definitionId) → versions registered through {@link #register(WorkflowDefinition)}, which is
     * how the deployed code and the packaged documents register. Guarded by {@link #rwLock} together with
     * {@link #byDefinition}; only registered versions are present.
     */
    private final HashMap<ScopedId, Set<Long>> deployed = new HashMap<>();

    /**
     * Serializes the multi-step publish phase of {@link #register}.
     * Reads take {@code readLock()}; the publish phase of register takes {@code writeLock()}.
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Optional fork/join semantic validator; null when none is wired (legacy callers). */
    private final WorkflowPlanValidator forkJoinValidator;

    /** Contributed registration-time definition validators; unordered and immutable. */
    private final Set<WorkflowDefinitionValidator> definitionValidators;

    /** The type-name index shared with the engine, the schema module and the document compiler. */
    private final WorkflowTypeNames typeNames;

    /** Decides whether a scope-owned state class extends the default scope's; empty refuses differing classes. */
    private final Optional<WorkflowStateCompatibility> stateCompatibility;

    /**
     * Creates a new empty {@code DefaultWorkflowRegistry} with no fork/join validator.
     *
     * <p>Plans containing {@link dev.vertique.workflow.plan.ForkNode} are accepted at
     * registration time but are not yet handled by the engine. Production wiring goes through
     * {@link #DefaultWorkflowRegistry(WorkflowPlanValidator)} via Dagger.
     */
    public DefaultWorkflowRegistry() {
        this(null);
    }

    /**
     * Creates a new empty {@code DefaultWorkflowRegistry} that consults {@code forkJoinValidator}
     * during {@link #register(dev.vertique.workflow.dsl.WorkflowDefinition)} immediately after
     * the plan is built and before existing graph/signal/callback validation.
     *
     * @param forkJoinValidator validator to invoke on each registered plan; may be null only in
     *     test contexts that intentionally skip fork/join semantic checks
     */
    public DefaultWorkflowRegistry(WorkflowPlanValidator forkJoinValidator) {
        this(forkJoinValidator, Set.of());
    }

    /**
     * INTERNAL framework seam — the framework implementation behind a contract this module
     * documents; not an application contract and outside the maturity promise.
     *
     * <p>Creates a new empty {@code DefaultWorkflowRegistry} that, in addition to the fork/join
     * validator, invokes every {@code definitionValidators} entry during
     * {@link #register(dev.vertique.workflow.dsl.WorkflowDefinition)} after plan validation and
     * before the publish phase. The validators are unordered.
     *
     * @param forkJoinValidator validator to invoke on each registered plan; may be null only in
     *     test contexts that intentionally skip fork/join semantic checks
     * @param definitionValidators validators to invoke on each candidate definition; copied
     *     defensively, must not be {@code null}
     */
    public DefaultWorkflowRegistry(
            WorkflowPlanValidator forkJoinValidator, Set<WorkflowDefinitionValidator> definitionValidators) {
        this(forkJoinValidator, definitionValidators, new DefaultWorkflowTypeNames());
    }

    /**
     * INTERNAL framework seam — the framework implementation behind a contract this module
     * documents; not an application contract and outside the maturity promise.
     *
     * <p>Creates a new empty {@code DefaultWorkflowRegistry} that, in addition to the behavior of
     * {@link #DefaultWorkflowRegistry(WorkflowPlanValidator, Set)}, names state and payload classes
     * through {@code typeNames} and indexes each candidate definition's contract, state, start,
     * signal and decision payload classes in it before the contributed validators run. A failed
     * registration removes the names it added.
     *
     * <p>Indexing needs a {@link DefaultWorkflowTypeNames}; any other implementation is only
     * consulted for names and its index is not extended.
     *
     * @param forkJoinValidator validator to invoke on each registered plan; may be null only in
     *     test contexts that intentionally skip fork/join semantic checks
     * @param definitionValidators validators to invoke on each candidate definition; copied
     *     defensively, must not be {@code null}
     * @param typeNames the shared type-name index; must not be {@code null}
     */
    public DefaultWorkflowRegistry(
            WorkflowPlanValidator forkJoinValidator,
            Set<WorkflowDefinitionValidator> definitionValidators,
            WorkflowTypeNames typeNames) {
        this(forkJoinValidator, definitionValidators, typeNames, Optional.empty());
    }

    /**
     * INTERNAL framework seam — the framework implementation behind a contract this module
     * documents; not an application contract and outside the maturity promise.
     *
     * <p>Creates a new empty {@code DefaultWorkflowRegistry} that, in addition to the behavior of
     * {@link #DefaultWorkflowRegistry(WorkflowPlanValidator, Set, WorkflowTypeNames)}, decides through
     * {@code stateCompatibility} whether the state class of a scope-owned definition extends the state class
     * of the default scope's definition of the same id and contract. When it is empty, differing state
     * classes of such a pair are refused.
     *
     * @param forkJoinValidator validator to invoke on each registered plan; may be null only in
     *     test contexts that intentionally skip fork/join semantic checks
     * @param definitionValidators validators to invoke on each candidate definition; copied
     *     defensively, must not be {@code null}
     * @param typeNames the shared type-name index; must not be {@code null}
     * @param stateCompatibility the state comparison; must not be {@code null}, may be empty
     */
    public DefaultWorkflowRegistry(
            WorkflowPlanValidator forkJoinValidator,
            Set<WorkflowDefinitionValidator> definitionValidators,
            WorkflowTypeNames typeNames,
            Optional<WorkflowStateCompatibility> stateCompatibility) {
        Objects.requireNonNull(definitionValidators, "definitionValidators");
        this.forkJoinValidator = forkJoinValidator;
        this.definitionValidators = Set.copyOf(definitionValidators);
        this.typeNames = Objects.requireNonNull(typeNames, "typeNames");
        this.stateCompatibility = Objects.requireNonNull(stateCompatibility, "stateCompatibility");
    }

    /**
     * INTERNAL framework seam — lets the engine assembly share the registry's index; not an
     * application contract and outside the maturity promise.
     *
     * @return the type-name index this registry names and indexes classes in; never {@code null}
     */
    public WorkflowTypeNames typeNames() {
        return typeNames;
    }

    // --- WorkflowRegistry ---

    /**
     * Registers a workflow definition in the default scope; see {@link #register(String, WorkflowDefinition)}.
     *
     * @param def the workflow definition to register
     * @throws WorkflowDefinitionException if the plan is invalid or a duplicate
     */
    @Override
    public void register(WorkflowDefinition<?, ?> def) {
        register(WorkflowScopes.DEFAULT, def, true);
    }

    /**
     * Registers a workflow definition in a scope, builds its plan via the DSL, and stores the resulting
     * {@link RuntimeWorkflow}.
     *
     * <p>Validation steps (performed before the write-lock is acquired, using only immutable
     * local values):
     * <ol>
     *   <li>Runs {@link WorkflowDefinition#define(WorkflowBuilder)} on a fresh builder.</li>
     *   <li>Verifies that {@code wf.init(...)} was called exactly once; throws
     *       {@link WorkflowDefinitionException} if not.</li>
     *   <li>Validates the plan graph shape via {@link #validatePlanGraph}.</li>
     *   <li>Verifies signal-name uniqueness across {@code WaitSignalNode}s.</li>
     *   <li>Verifies that every awaited signal is declared by a {@code @WorkflowSignal} method of the
     *       definition's contract, including methods the contract inherits; the exception names the
     *       missing signals in sorted order.</li>
     *   <li>Computes {@code planHash}.</li>
     *   <li>Runs every contributed {@link WorkflowDefinitionValidator} on the calling thread, in
     *       no particular order; an exception it throws propagates unchanged and nothing is
     *       published.</li>
     * </ol>
     *
     * <p>Publish phase (inside {@code writeLock}):
     * <ol>
     *   <li>Throws if {@code (scopeKey, definitionId, definitionVersion)} is already registered.</li>
     *   <li>Throws if an earlier version of the same definition id in the same scope names another
     *       contract; any number of definition ids and versions may name one contract, and another
     *       scope may hold the id under another contract.</li>
     *   <li>Throws if an earlier version of the same definition id in the same scope has another state
     *       class.</li>
     *   <li>Throws if the scope is not the default scope, the default scope holds the same id under the same
     *       contract with another state class, and the scope's state class does not extend the default's;
     *       registering the default definition second applies the same rule to each scope-owned
     *       definition of the id. The refusal names the scope-owned key and the first incompatible
     *       property path. Without a state comparison, differing state classes are refused.</li>
     *   <li>Stores the {@link RuntimeWorkflow} keyed by {@code (scopeKey, definitionId,
     *       definitionVersion)} — no half-committed state is visible to concurrent readers.</li>
     * </ol>
     *
     * <p>Every refusal names the scope, the definition id and the version.
     *
     * @param scopeKey the scope that owns the definition
     * @param def the workflow definition to register
     * @throws WorkflowDefinitionException if the plan is invalid, the scope key is not a valid scope key, or
     *     the key is a duplicate
     */
    @Override
    public void register(String scopeKey, WorkflowDefinition<?, ?> def) {
        register(scopeKey, def, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The plan rules of {@link #register(String, WorkflowDefinition)} apply. The state class and every
     * signal and decision payload class that {@code nameBinding} holds are named in the plan, and so in its
     * hash, by the bound name; any other class is named by its current logical name.
     */
    @Override
    public void register(String scopeKey, WorkflowDefinition<?, ?> def, Map<String, String> nameBinding) {
        Objects.requireNonNull(nameBinding, "nameBinding");
        register(scopeKey, def, false, DefaultWorkflowTypeNames.bound(typeNames, nameBinding), true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every rule of {@link #register(String, WorkflowDefinition)} applies except the comparison of the scope's
     * state class with the default scope's: the per-scope contract and state class rules, the signal declarations
     * and the plan validation still refuse the definition.
     */
    @Override
    public void registerFailing(String scopeKey, WorkflowDefinition<?, ?> def) {
        register(scopeKey, def, false, typeNames, false);
    }

    private void register(String scopeKey, WorkflowDefinition<?, ?> def, boolean deployment) {
        register(scopeKey, def, deployment, typeNames, true);
    }

    /** A definition built into the plan and runtime that a registration or a rebinding publishes. */
    private record Compiled(WorkflowBuilder<?> builder, RuntimeWorkflow runtime, Map<String, String> binding) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void register(
            String scopeKey,
            WorkflowDefinition<?, ?> def,
            boolean deployment,
            WorkflowTypeNames names,
            boolean compareWithDefault) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        // --- Pre-publish validation (no lock needed — produces only immutable local values) ---

        String definitionId = def.definitionId();
        long definitionVersion = def.definitionVersion();
        requireValidScope(scopeKey, definitionId, definitionVersion);
        Compiled compiled = compile(scopeKey, def, names);
        WorkflowBuilder builder = compiled.builder();
        WorkflowPlan plan = compiled.runtime().plan();
        RuntimeWorkflow rw = compiled.runtime();

        // The candidate's classes enter the shared index before the contributed validators run, so a
        // validator can resolve the logical names the plan holds. Any later failure removes them again.
        Runnable undoIndexing = indexCandidate(def, builder);
        try {
            // Contributed definition validators run last, before the publish phase; an exception
            // propagates unchanged and the definition is never published.
            RegisteredWorkflowDefinition candidate = new RegisteredWorkflowDefinition(plan, builder.startPayloadType());
            for (WorkflowDefinitionValidator validator : definitionValidators) {
                validator.validate(candidate);
            }
            publish(
                    def,
                    scopeKey,
                    definitionId,
                    definitionVersion,
                    rw,
                    compiled.binding(),
                    deployment,
                    compareWithDefault);
        } catch (RuntimeException | Error e) {
            undoIndexing.run();
            throw e;
        }

        // Per-definition callbacks live on `rw.callbacks()` (used by the engine via
        // RuntimeWorkflow.callbacks() during execution). We intentionally do NOT merge them into
        // any global aggregate: callback ids are scoped to {stepId, role} which collides across
        // definitions/versions, and a globally-keyed map would silently overwrite or require
        // callers to know which definition a callback belongs to. Consumers must look up
        // callbacks via {@link RuntimeWorkflow#callbacks()} on a resolved instance.
    }

    /**
     * Runs the definition on a fresh builder that names classes through {@code names}, validates the plan and
     * builds the runtime. Nothing is published and nothing is indexed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Compiled compile(String scopeKey, WorkflowDefinition<?, ?> def, WorkflowTypeNames names) {
        String definitionId = def.definitionId();
        long definitionVersion = def.definitionVersion();
        WorkflowBuilder builder = new WorkflowBuilder<>(names);
        def.define(builder);
        String stateTypeName = names.nameOf(def.stateType());

        // build() validates that init() was called; throws WorkflowDefinitionException if not
        WorkflowPlan plan = builder.build(definitionId, definitionVersion, stateTypeName);

        // PRD-WF-002: fork/join semantic validation runs FIRST so structural fork/join issues
        // surface before the existing graph/signal/callback checks.
        if (forkJoinValidator != null) {
            forkJoinValidator.validate(plan);
        }

        // Validate plan graph shape BEFORE building the nodeById map (so errors surface early)
        validatePlanGraph(plan, scopeKey, definitionId, definitionVersion);

        // Validate signal-name uniqueness
        validateSignalUniqueness(plan, scopeKey, definitionId, definitionVersion);

        // Every awaited signal must be declared on the contract
        validateDeclaredSignals(plan, def.contract(), scopeKey, definitionId, definitionVersion);

        // Build per-definition callbacks and validate callback-registry consistency
        WorkflowCallbackRegistry perDefCallbacks = builder.callbackRegistry();
        validateCallbackConsistency(plan, perDefCallbacks, scopeKey, definitionId, definitionVersion);
        RuntimeWorkflow rw = RuntimeWorkflow.of(
                plan,
                typeNames.contractNameOf(def.contract()),
                scopeKey,
                def.stateType(),
                builder.startPayloadType(),
                builder.initialState(),
                builder.signalPayloadTypes(),
                perDefCallbacks);
        return new Compiled(builder, rw, builder.nameBinding(def.stateType()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The runtime is built from the definition retained by {@link #registeredDefinition(DefinitionKey)} and
     * replaces the registered one under the publication lock only when its plan hash equals
     * {@code requiredPlanHash} and the version still holds that definition. The deprecation mark and the
     * deployed flag of the version are kept.
     */
    @Override
    public Optional<RuntimeWorkflow> rebind(
            DefinitionKey key, Map<String, String> nameBinding, String requiredPlanHash) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nameBinding, "nameBinding");
        Objects.requireNonNull(requiredPlanHash, "requiredPlanHash");
        Optional<WorkflowDefinition<?, ?>> retained = registeredDefinition(key);
        if (retained.isEmpty()) return Optional.empty();
        WorkflowDefinition<?, ?> def = retained.orElseThrow();
        Compiled compiled = compile(key.scopeKey(), def, DefaultWorkflowTypeNames.bound(typeNames, nameBinding));
        if (!requiredPlanHash.equals(compiled.runtime().plan().planHash())) return Optional.empty();
        ScopedId scopedId = new ScopedId(key.scopeKey(), key.definitionId());
        long version = key.definitionVersion();
        rwLock.writeLock().lock();
        try {
            var registered = sources.get(scopedId);
            var versions = byDefinition.get(scopedId);
            if (registered == null
                    || registered.get(version) != def
                    || versions == null
                    || !versions.containsKey(version)) return Optional.empty();
            versions.put(version, compiled.runtime());
            bindings.computeIfAbsent(scopedId, k -> new HashMap<>()).put(version, compiled.binding());
            return Optional.of(compiled.runtime());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read under the same lock as the registration, so it is empty for a version that is not registered.
     */
    @Override
    public Map<String, String> nameBinding(DefinitionKey key) {
        rwLock.readLock().lock();
        try {
            var registered = bindings.get(new ScopedId(key.scopeKey(), key.definitionId()));
            return registered == null ? Map.of() : registered.getOrDefault(key.definitionVersion(), Map.of());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Refuses a scope key that a {@link DefinitionKey} would reject. The default scope is a fixed value and is
     * not checked, so the definitions of earlier releases register as before.
     */
    private static void requireValidScope(String scopeKey, String definitionId, long definitionVersion) {
        if (WorkflowScopes.DEFAULT.equals(scopeKey)) {
            return;
        }
        try {
            new DefinitionKey(scopeKey, definitionId, definitionVersion);
        } catch (IllegalArgumentException e) {
            throw new WorkflowDefinitionException("definition '" + definitionId + "' v" + definitionVersion
                    + " cannot be registered: " + e.getMessage());
        }
    }

    /** Names a definition version and its scope for a refusal message. */
    private static String describe(String scopeKey, String definitionId, long definitionVersion) {
        return "'" + definitionId + "' v" + definitionVersion + " in scope '" + scopeKey + "'";
    }

    /** Atomic publish phase: the write lock is held for the entire multi-step publish. */
    private void publish(
            WorkflowDefinition<?, ?> def,
            String scopeKey,
            String definitionId,
            long definitionVersion,
            RuntimeWorkflow rw,
            Map<String, String> nameBinding,
            boolean deployment,
            boolean compareWithDefault) {
        ScopedId scopedId = new ScopedId(scopeKey, definitionId);
        rwLock.writeLock().lock();
        try {
            // Reject exact duplicates, and a version whose contract differs from the earlier versions of its
            // scope and id
            ConcurrentSkipListMap<Long, RuntimeWorkflow> registered = byDefinition.get(scopedId);
            if (registered != null && !registered.isEmpty()) {
                if (registered.containsKey(definitionVersion)) {
                    throw new WorkflowDefinitionException(
                            "definition " + describe(scopeKey, definitionId, definitionVersion)
                                    + " is already registered; duplicate registrations are not allowed");
                }
                String earlierContract = registered.firstEntry().getValue().contractName();
                if (!earlierContract.equals(rw.contractName())) {
                    throw new WorkflowDefinitionException("definition "
                            + describe(scopeKey, definitionId, definitionVersion)
                            + " names contract '" + rw.contractName() + "' but earlier versions of '" + definitionId
                            + "' in that scope name contract '" + earlierContract
                            + "'; every version of a definition in a scope must name the same contract");
                }
                Class<?> earlierState = registered.firstEntry().getValue().stateType();
                if (!earlierState.equals(rw.stateType())) {
                    throw new WorkflowDefinitionException("definition "
                            + describe(scopeKey, definitionId, definitionVersion)
                            + " has state type " + rw.stateType().getSimpleName() + " but earlier versions of '"
                            + definitionId + "' in that scope have state type " + earlierState.getSimpleName()
                            + "; every version of a definition in a scope must have the same state type");
                }
            }
            if (compareWithDefault) requireStateExtension(scopeKey, definitionId, definitionVersion, rw);
            byDefinition
                    .computeIfAbsent(scopedId, k -> new ConcurrentSkipListMap<>())
                    .put(definitionVersion, rw);
            sources.computeIfAbsent(scopedId, k -> new HashMap<>()).put(definitionVersion, def);
            bindings.computeIfAbsent(scopedId, k -> new HashMap<>()).put(definitionVersion, nameBinding);
            if (deployment)
                deployed.computeIfAbsent(scopedId, k -> new HashSet<>()).add(definitionVersion);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Applies the scope-versus-default state rule to a candidate whose scope-and-id rules passed: a scope-owned
     * candidate is compared with the default scope's definition of the id, a default candidate with each
     * scope-owned definition of the id. Only definitions of one contract are compared. Called under the write
     * lock.
     */
    private void requireStateExtension(
            String scopeKey, String definitionId, long definitionVersion, RuntimeWorkflow rw) {
        if (WorkflowScopes.DEFAULT.equals(scopeKey)) {
            for (var entry : byDefinition.entrySet()) {
                ScopedId other = entry.getKey();
                if (!other.definitionId().equals(definitionId)
                        || WorkflowScopes.DEFAULT.equals(other.scopeKey())
                        || entry.getValue().isEmpty()) {
                    continue;
                }
                var scopeOwned = entry.getValue().firstEntry();
                requireExtension(rw, scopeOwned.getValue(), other.scopeKey(), definitionId, scopeOwned.getKey());
            }
            return;
        }
        var defaults = byDefinition.get(new ScopedId(WorkflowScopes.DEFAULT, definitionId));
        if (defaults == null || defaults.isEmpty()) {
            return;
        }
        requireExtension(defaults.firstEntry().getValue(), rw, scopeKey, definitionId, definitionVersion);
    }

    /**
     * Refuses a scope-owned definition whose state class differs from the default scope's definition of the
     * same contract and does not extend it. Definitions of other contracts and of equal state classes pass.
     */
    private void requireExtension(
            RuntimeWorkflow defaultRw,
            RuntimeWorkflow scopeOwned,
            String scopeKey,
            String definitionId,
            long scopeOwnedVersion) {
        if (!defaultRw.contractName().equals(scopeOwned.contractName())
                || defaultRw.stateType().equals(scopeOwned.stateType())) {
            return;
        }
        String subject = "definition " + describe(scopeKey, definitionId, scopeOwnedVersion) + " has state type "
                + scopeOwned.stateType().getSimpleName() + " but the default scope's '" + definitionId
                + "' of the same contract has state type "
                + defaultRw.stateType().getSimpleName();
        if (stateCompatibility.isEmpty()) {
            throw new WorkflowDefinitionException(subject
                    + "; the state comparison is unavailable (no WorkflowStateCompatibility is bound), so"
                    + " differing state types are refused");
        }
        Optional<String> incompatible =
                stateCompatibility.orElseThrow().firstIncompatibility(defaultRw.stateType(), scopeOwned.stateType());
        if (incompatible.isPresent()) {
            throw new WorkflowDefinitionException(subject + "; the state type must extend the default scope's, but"
                    + " property '" + incompatible.orElseThrow() + "' is removed or has another type");
        }
    }

    /**
     * Adds the candidate's contract, state, start, signal and decision payload classes to the shared
     * index. A conflicting or invalid name fails registration and leaves the index unchanged.
     *
     * @return an action that removes the names this call added
     */
    private Runnable indexCandidate(WorkflowDefinition<?, ?> def, WorkflowBuilder<?> builder) {
        if (!(typeNames instanceof DefaultWorkflowTypeNames index)) {
            return () -> {};
        }
        List<Class<?>> classes = new ArrayList<>();
        classes.add(def.stateType());
        classes.add(builder.startPayloadType());
        classes.addAll(builder.signalPayloadTypes().values());
        classes.addAll(builder.decisionPayloadTypes());
        return index.add(def.contract(), classes);
    }

    /**
     * Validates the structural integrity of the plan graph at registration time.
     *
     * <p>Checks performed (each violation throws {@link WorkflowDefinitionException}):
     * <ol>
     *   <li>No duplicate {@code stepId}s — duplicate ids silently cause the second node to shadow
     *       the first in the {@code nodeById} map, making the first unreachable.</li>
     *   <li>{@code initialStepId} must resolve to a node in the plan.</li>
     *   <li>For every {@link ServiceDispatchNode}: {@code nextStepId} must resolve to a node;
     *       if {@code compensationStepId} is non-null it must resolve to a
     *       {@link CompensationNode} whose {@code forwardStepId} equals this node's
     *       {@code stepId}.</li>
     *   <li>For every {@link WaitSignalNode}: {@code nextStepId} must resolve to a node; if
     *       {@code timeout} is non-null, {@code timeoutNextStepId} must also resolve to a node.</li>
     *   <li>For every {@link TimerNode}: {@code nextStepId} must resolve to a node.</li>
     *   <li>For every {@link CompensationNode}: {@code forwardStepId} must resolve to a
     *       {@link ServiceDispatchNode} whose {@code compensationStepId} equals this node's
     *       {@code stepId}.</li>
     * </ol>
     *
     * @param plan the plan to validate
     * @param scopeKey the scope of the definition (for error messages)
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if any structural rule is violated
     */
    private static void validatePlanGraph(
            WorkflowPlan plan, String scopeKey, String definitionId, long definitionVersion) {
        String label = "plan " + describe(scopeKey, definitionId, definitionVersion);

        // --- 1. Collect all stepIds and reject duplicates ---
        Map<String, WorkflowNode> nodeById = new HashMap<>();
        for (WorkflowNode node : plan.nodes()) {
            WorkflowNode prev = nodeById.put(node.stepId(), node);
            if (prev != null) {
                throw new WorkflowDefinitionException(
                        "duplicate stepId '" + node.stepId() + "' in " + label + "; each step must have a unique id");
            }
        }

        // --- 2. plan must have at least one node and a non-null initialStepId that resolves ---
        if (plan.nodes().isEmpty()) {
            throw new WorkflowDefinitionException(
                    label + " has no nodes; a workflow plan must contain at least one node");
        }
        if (plan.initialStepId() == null) {
            throw new WorkflowDefinitionException(
                    label + " has a null initialStepId; declare wf.initialStep(...) and/or at least one DSL step"
                            + " (the first step's id is used when initialStep is not set explicitly)");
        }
        if (!nodeById.containsKey(plan.initialStepId())) {
            throw new WorkflowDefinitionException(
                    "initialStepId '" + plan.initialStepId() + "' in " + label + " does not match any node stepId");
        }

        // --- 3–6. Per-node reference checks ---
        for (WorkflowNode node : plan.nodes()) {
            switch (node) {
                case ServiceDispatchNode sdn -> {
                    // nextStepId must resolve
                    if (!nodeById.containsKey(sdn.nextStepId())) {
                        throw new WorkflowDefinitionException("ServiceDispatchNode '" + sdn.stepId() + "' in " + label
                                + " references unknown nextStepId '" + sdn.nextStepId() + "'");
                    }
                    // compensationStepId must resolve to a CompensationNode with matching forwardStepId
                    if (sdn.compensationStepId() != null) {
                        WorkflowNode compNode = nodeById.get(sdn.compensationStepId());
                        if (!(compNode instanceof CompensationNode cn)) {
                            throw new WorkflowDefinitionException(
                                    "ServiceDispatchNode '" + sdn.stepId() + "' in " + label
                                            + " references compensationStepId '" + sdn.compensationStepId()
                                            + "' which does not resolve to a CompensationNode");
                        }
                        if (!sdn.stepId().equals(cn.forwardStepId())) {
                            throw new WorkflowDefinitionException(
                                    "ServiceDispatchNode '" + sdn.stepId() + "' in " + label
                                            + " references compensationStepId '" + sdn.compensationStepId()
                                            + "' but that CompensationNode's forwardStepId is '"
                                            + cn.forwardStepId() + "' — compensation pair is mismatched");
                        }
                    }
                }
                case WaitSignalNode wsn -> {
                    if (!nodeById.containsKey(wsn.nextStepId())) {
                        throw new WorkflowDefinitionException("WaitSignalNode '" + wsn.stepId() + "' in " + label
                                + " references unknown nextStepId '" + wsn.nextStepId() + "'");
                    }
                    if (wsn.timeout() != null) {
                        String timeoutNext = wsn.timeout().timeoutNextStepId();
                        if (!nodeById.containsKey(timeoutNext)) {
                            throw new WorkflowDefinitionException("WaitSignalNode '" + wsn.stepId() + "' in " + label
                                    + " timeout branch references unknown timeoutNextStepId '" + timeoutNext + "'");
                        }
                    }
                }
                case TimerNode tn -> {
                    if (!nodeById.containsKey(tn.nextStepId())) {
                        throw new WorkflowDefinitionException("TimerNode '" + tn.stepId() + "' in " + label
                                + " references unknown nextStepId '" + tn.nextStepId() + "'");
                    }
                }
                case CompensationNode cn -> {
                    WorkflowNode fwdNode = nodeById.get(cn.forwardStepId());
                    if (!(fwdNode instanceof ServiceDispatchNode sdn)) {
                        throw new WorkflowDefinitionException("CompensationNode '" + cn.stepId() + "' in " + label
                                + " references forwardStepId '" + cn.forwardStepId()
                                + "' which does not resolve to a ServiceDispatchNode");
                    }
                    if (!cn.stepId().equals(sdn.compensationStepId())) {
                        throw new WorkflowDefinitionException("CompensationNode '" + cn.stepId() + "' in " + label
                                + " references forwardStepId '" + cn.forwardStepId()
                                + "' but that ServiceDispatchNode's compensationStepId is '"
                                + sdn.compensationStepId() + "' — compensation pair is mismatched");
                    }
                }
                case HumanTaskNode htn -> {
                    // Each TaskDecision.nextStepId must resolve.
                    for (HumanTaskNode.TaskDecision d : htn.decisions()) {
                        if (!nodeById.containsKey(d.nextStepId())) {
                            throw new WorkflowDefinitionException("HumanTaskNode '" + htn.stepId() + "' in " + label
                                    + " decision '" + d.name()
                                    + "' references unknown nextStepId '" + d.nextStepId() + "'");
                        }
                    }
                    // Optional due-date branch: dueNextStepId must resolve when a due-date triplet is set.
                    if (htn.dueNextStepId() != null && !nodeById.containsKey(htn.dueNextStepId())) {
                        throw new WorkflowDefinitionException("HumanTaskNode '" + htn.stepId() + "' in " + label
                                + " due-date branch references unknown dueNextStepId '" + htn.dueNextStepId() + "'");
                    }
                }
                default -> {
                    // CompleteNode, FailNode, DecisionNode: no outbound refs to validate
                }
            }
        }
    }

    /**
     * Validates that all {@link CallbackId} references embedded in the plan are resolvable in the
     * supplied callback registry.
     *
     * <p>This is a defense-in-depth check. The builder registers every callback it creates, so any
     * inconsistency caught here indicates an internal builder invariant was broken.
     *
     * <p>Checks:
     * <ul>
     *   <li>For every {@link TimerNode} with a {@link TimerSpec.FromState} spec: the
     *       {@code resolverCallbackId} must resolve via
     *       {@link WorkflowCallbackRegistry#timerResolver(CallbackId)}.</li>
     *   <li>For every {@link WaitSignalNode} with a non-null {@code timeout}: the
     *       {@code onTimeoutMutatorCallbackId} must resolve via
     *       {@link WorkflowCallbackRegistry#stateMutator(CallbackId)}.</li>
     * </ul>
     *
     * @param plan the validated plan
     * @param callbacks the per-definition callback registry built from the same builder
     * @param scopeKey the scope of the definition (for error messages)
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if a required callback is missing
     */
    private static void validateCallbackConsistency(
            WorkflowPlan plan,
            WorkflowCallbackRegistry callbacks,
            String scopeKey,
            String definitionId,
            long definitionVersion) {
        String label = "plan " + describe(scopeKey, definitionId, definitionVersion);
        for (WorkflowNode node : plan.nodes()) {
            switch (node) {
                case TimerNode tn -> {
                    if (tn.spec() instanceof TimerSpec.FromState fs) {
                        CallbackId cbId = fs.resolverCallbackId();
                        try {
                            callbacks.timerResolver(cbId);
                        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                            throw new WorkflowDefinitionException("TimerNode '" + tn.stepId() + "' in " + label
                                    + " uses TimerSpec.FromState but no timerResolver is registered for callback '"
                                    + cbId.value() + "'");
                        }
                    }
                }
                case WaitSignalNode wsn -> {
                    if (wsn.timeout() != null) {
                        CallbackId mutatorId = wsn.timeout().onTimeoutMutatorCallbackId();
                        try {
                            callbacks.stateMutator(mutatorId);
                        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                            throw new WorkflowDefinitionException("WaitSignalNode '" + wsn.stepId() + "' in " + label
                                    + " has a timeout branch but no stateMutator is registered for callback '"
                                    + mutatorId.value() + "'");
                        }
                    }
                }
                default -> {
                    /* no extra checks for other node types */
                }
            }
        }
    }

    /**
     * Validates that no two {@link WaitSignalNode}s in the plan share the same signal name.
     *
     * @param plan the plan to validate
     * @param scopeKey the scope of the definition (for error messages)
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if duplicate signal names are found
     */
    private static void validateSignalUniqueness(
            WorkflowPlan plan, String scopeKey, String definitionId, long definitionVersion) {
        Set<String> seen = new HashSet<>();
        for (var node : plan.nodes()) {
            if (node instanceof WaitSignalNode w) {
                if (!seen.add(w.signalName())) {
                    throw new WorkflowDefinitionException("duplicate signal name '" + w.signalName() + "' in plan "
                            + describe(scopeKey, definitionId, definitionVersion)
                            + "; plans must have unique signal names across WaitSignalNodes");
                }
            }
        }
    }

    /**
     * Describes the undeclared signals for an error message: the first {@value #MAX_REPORTED_SIGNALS} names in
     * sorted order, each cut and sanitized because document authors supply them, and a count of the rest.
     */
    private static String describeMissing(SortedSet<String> missing) {
        String named = missing.stream()
                .limit(MAX_REPORTED_SIGNALS)
                .map(EchoedNames::bounded)
                .collect(Collectors.joining(", "));
        int rest = missing.size() - MAX_REPORTED_SIGNALS;
        return rest > 0 ? named + ", and " + rest + " more" : named;
    }

    /**
     * Validates that every signal the plan waits for is declared by a {@link WorkflowSignal} method of the
     * contract interface, including methods the contract inherits from other interfaces. Forward, branch and
     * timeout-guarded waits are all {@link WaitSignalNode}s of the plan.
     *
     * @param plan the plan to validate
     * @param contract the contract interface of the definition
     * @param scopeKey the scope of the definition (for error messages)
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if the plan waits for a signal the contract does not declare
     */
    private static void validateDeclaredSignals(
            WorkflowPlan plan, Class<?> contract, String scopeKey, String definitionId, long definitionVersion) {
        Set<String> declared = new HashSet<>();
        for (Method method : contract.getMethods()) {
            WorkflowSignal signal = method.getAnnotation(WorkflowSignal.class);
            if (signal != null) {
                declared.add(signal.value());
            }
        }
        SortedSet<String> missing = new TreeSet<>();
        for (WorkflowNode node : plan.nodes()) {
            if (node instanceof WaitSignalNode wait && !declared.contains(wait.signalName())) {
                missing.add(wait.signalName());
            }
        }
        if (!missing.isEmpty()) {
            throw new WorkflowDefinitionException("definition " + describe(scopeKey, definitionId, definitionVersion)
                    + " waits for signals that contract " + contract.getName() + " does not declare: "
                    + describeMissing(missing)
                    + "; declare each awaited signal as a @WorkflowSignal method on the contract");
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws WorkflowVersionPinUnavailableException if the scope does not hold the version; the message
     *     names the scope, the definition id and the version
     */
    @Override
    public RuntimeWorkflow resolve(DefinitionKey key) {
        return resolve(key.scopeKey(), key.definitionId(), key.definitionVersion());
    }

    private RuntimeWorkflow resolve(String scopeKey, String definitionId, long version) {
        RuntimeWorkflow rw = lookup(scopeKey, definitionId, version);
        if (rw == null) {
            throw new WorkflowVersionPinUnavailableException(scopeKey, definitionId, version, null);
        }
        return rw;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RuntimeWorkflow> find(DefinitionKey key) {
        return Optional.ofNullable(lookup(key.scopeKey(), key.definitionId(), key.definitionVersion()));
    }

    private RuntimeWorkflow lookup(String scopeKey, String definitionId, long version) {
        rwLock.readLock().lock();
        try {
            NavigableMap<Long, RuntimeWorkflow> versions = byDefinition.get(new ScopedId(scopeKey, definitionId));
            return versions == null ? null : versions.get(version);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans the registered scope and id pairs, so it is for listings; the order is by scope key and then by
     * version.
     */
    @Override
    public List<DefinitionKey> keysFor(String definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        rwLock.readLock().lock();
        try {
            List<DefinitionKey> keys = new ArrayList<>();
            for (Map.Entry<ScopedId, ConcurrentSkipListMap<Long, RuntimeWorkflow>> entry : byDefinition.entrySet()) {
                if (!entry.getKey().definitionId().equals(definitionId)) {
                    continue;
                }
                for (Long version : entry.getValue().keySet()) {
                    keys.add(new DefinitionKey(entry.getKey().scopeKey(), definitionId, version));
                }
            }
            keys.sort(
                    Comparator.comparing(DefinitionKey::scopeKey).thenComparingLong(DefinitionKey::definitionVersion));
            return List.copyOf(keys);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answered from the per-scope and id index that the contract rule of {@link #register(String,
     * WorkflowDefinition)} reads, in one lookup.
     */
    @Override
    public Optional<String> contractOf(String scopeKey, String definitionId) {
        rwLock.readLock().lock();
        try {
            ConcurrentSkipListMap<Long, RuntimeWorkflow> versions =
                    byDefinition.get(new ScopedId(scopeKey, definitionId));
            return versions == null || versions.isEmpty()
                    ? Optional.empty()
                    : Optional.of(versions.firstEntry().getValue().contractName());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Derived from the registered definitions under the read lock, so it follows
     * {@link #unregister(DefinitionKey)}. When several versions of one id share the class, the
     * highest version's contract answers.
     */
    @Override
    public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
        Objects.requireNonNull(definitionClass, "definitionClass");
        rwLock.readLock().lock();
        try {
            String foundId = null;
            WorkflowDefinition<?, ?> foundDefinition = null;
            long foundVersion = Long.MIN_VALUE;
            for (Map.Entry<ScopedId, HashMap<Long, WorkflowDefinition<?, ?>>> byId : sources.entrySet()) {
                String id = byId.getKey().definitionId();
                for (Map.Entry<Long, WorkflowDefinition<?, ?>> byVersion :
                        byId.getValue().entrySet()) {
                    if (byVersion.getValue().getClass() != definitionClass) {
                        continue;
                    }
                    if (foundId != null && !foundId.equals(id)) {
                        return Optional.empty();
                    }
                    if (foundId == null || byVersion.getKey() > foundVersion) {
                        foundId = id;
                        foundDefinition = byVersion.getValue();
                        foundVersion = byVersion.getKey();
                    }
                }
            }
            if (foundId == null) {
                return Optional.empty();
            }
            Class<?> contract = foundDefinition.contract();
            return Optional.of(new RegisteredDefinitionClass(foundId, typeNames.contractNameOf(contract), contract));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flattens all per-version inner maps into a single immutable list. The order of entries is
     * deterministic within each scope and definitionId (ascending version), but the order across
     * different scopes and definitionIds is not guaranteed.
     */
    @Override
    public Collection<RuntimeWorkflow> allRegistered() {
        rwLock.readLock().lock();
        try {
            List<RuntimeWorkflow> result = new ArrayList<>();
            for (NavigableMap<Long, RuntimeWorkflow> versions : byDefinition.values()) {
                result.addAll(versions.values());
            }
            return List.copyOf(result);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The mark is taken under the same write lock as {@link #register} and {@link #unregister},
     * so a version is never observable as registered-but-unmarked after this method returns.
     * Marking a version that is not currently registered is a no-op, because a deprecation can race
     * with the local removal of the same version; marking an already deprecated version is
     * idempotent. {@link #unregister(DefinitionKey)} clears the mark.
     */
    @Override
    public void markDeprecated(DefinitionKey key) {
        ScopedId scopedId = new ScopedId(key.scopeKey(), key.definitionId());
        rwLock.writeLock().lock();
        try {
            var versions = byDefinition.get(scopedId);
            if (versions == null || !versions.containsKey(key.definitionVersion())) return;
            deprecated
                    .computeIfAbsent(scopedId, k -> ConcurrentHashMap.newKeySet())
                    .add(key.definitionVersion());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The mark is cleared under the same write lock as {@link #register}, {@link #unregister} and
     * {@link #markDeprecated}, so no caller observes a half-applied change.
     */
    @Override
    public void unmarkDeprecated(DefinitionKey key) {
        ScopedId scopedId = new ScopedId(key.scopeKey(), key.definitionId());
        rwLock.writeLock().lock();
        try {
            deprecated.computeIfPresent(scopedId, (k, marked) -> {
                marked.remove(key.definitionVersion());
                return marked.isEmpty() ? null : marked;
            });
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code false} for a version that is not currently registered.
     */
    @Override
    public boolean isDeprecated(DefinitionKey key) {
        rwLock.readLock().lock();
        try {
            return deprecated
                    .getOrDefault(new ScopedId(key.scopeKey(), key.definitionId()), Set.of())
                    .contains(key.definitionVersion());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>True for a version registered through {@link #register(WorkflowDefinition)}, read under the same lock as
     * the registration; false for a version registered through {@link #register(String, WorkflowDefinition)}, and
     * again after {@link #unregister(DefinitionKey)}.
     */
    @Override
    public boolean isDeployed(DefinitionKey key) {
        rwLock.readLock().lock();
        try {
            Set<Long> versions = deployed.get(new ScopedId(key.scopeKey(), key.definitionId()));
            return versions != null && versions.contains(key.definitionVersion());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the definition object passed to {@link #register}, read under the same lock as the
     * registration, so it is empty for a version that was never registered and again after
     * {@link #unregister(DefinitionKey)}.
     */
    @Override
    public Optional<WorkflowDefinition<?, ?>> registeredDefinition(DefinitionKey key) {
        rwLock.readLock().lock();
        try {
            var registered = sources.get(new ScopedId(key.scopeKey(), key.definitionId()));
            return registered == null ? Optional.empty() : Optional.ofNullable(registered.get(key.definitionVersion()));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Removes the version and its deprecation mark under the publication lock. */
    @Override
    public void unregister(DefinitionKey key) {
        ScopedId scopedId = new ScopedId(key.scopeKey(), key.definitionId());
        long version = key.definitionVersion();
        rwLock.writeLock().lock();
        try {
            var versions = byDefinition.get(scopedId);
            if (versions == null || versions.remove(version) == null) return;
            if (versions.isEmpty()) byDefinition.remove(scopedId, versions);
            sources.computeIfPresent(scopedId, (k, registered) -> {
                registered.remove(version);
                return registered.isEmpty() ? null : registered;
            });
            bindings.computeIfPresent(scopedId, (k, registered) -> {
                registered.remove(version);
                return registered.isEmpty() ? null : registered;
            });
            deprecated.computeIfPresent(scopedId, (k, marked) -> {
                marked.remove(version);
                return marked.isEmpty() ? null : marked;
            });
            deployed.computeIfPresent(scopedId, (k, marked) -> {
                marked.remove(version);
                return marked.isEmpty() ? null : marked;
            });
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
