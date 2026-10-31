// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
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
import io.vertx.core.Future;
import jakarta.inject.Provider;
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
 * <p>The registry holds the code definitions the application registers while it starts, and no other plan. A
 * document plan, packaged or published, is built from its stored row into the engine's plan cache, which this
 * registry reaches through a bound {@link DocumentPlanSource}; the registry keeps no reference to it.
 *
 * <p>Any number of definition ids and versions may name one contract. A code version must name the same contract
 * as every earlier version of its id, and every signal its plan waits for must be declared on that contract. All
 * versions of one id use one state class.
 *
 * <p>Internal storage, each map keyed by the scope and the definition id:
 * <ul>
 *   <li>{@code byDefinition} — {@link ConcurrentHashMap} keyed by scope and definitionId, inner
 *       {@link ConcurrentSkipListMap} keyed by version; allows efficient lookup of the highest
 *       version via {@code lastEntry()}, and answers the contract rule from the first version it holds.</li>
 *   <li>{@code sources} — the registered definition objects per scope, id and version.</li>
 *   <li>{@code knownClasses} — the definition id and contract each registered definition class selected;
 *       the source of {@link #definitionClass(Class)}.</li>
 *   <li>{@code bindings} — the type names each registered plan's hash used.</li>
 * </ul>
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #register(WorkflowDefinition)} is safe to call from several threads while the application
 *       starts; the underlying publication is serialized by {@code writeLock}.</li>
 *   <li>Reads ({@link #resolve(DefinitionKey)}, {@link #find(DefinitionKey)},
 *       {@link #codeDefinitions}) are non-blocking relative to each other (they hold only
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

    /** The contract and highest version a definition class was registered with under one definition id. */
    private record KnownClass(long version, Class<?> contract) {}

    /**
     * Keyed by the runtime class of a registered definition → (definition id → the highest version of that id
     * the class was registered as, and its contract). Written when a version registers and never removed, so
     * the class keeps selecting its definition id after every version it was registered as has retired. It
     * holds no plan and makes no version startable. Guarded by {@link #rwLock}.
     */
    private final HashMap<Class<?>, HashMap<String, KnownClass>> knownClasses = new HashMap<>();

    /**
     * Keyed by (scope, definitionId) → (version → the binding of the registered plan: the binary name of its state and
     * payload classes mapped to the name its hash used). Guarded by {@link #rwLock} together with
     * {@link #byDefinition}; it holds exactly the registered versions.
     */
    private final HashMap<ScopedId, HashMap<Long, Map<String, String>>> bindings = new HashMap<>();

    /**
     * Serializes the multi-step publish phase of {@link #register}.
     * Reads take {@code readLock()}; the publish phase of register takes {@code writeLock()}.
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Optional fork/join semantic validator; null when none is wired (legacy callers). */
    private final WorkflowPlanValidator forkJoinValidator;

    /** Resolves the contributed registration-time definition validators for each plan build; unordered. */
    private final Provider<Set<WorkflowDefinitionValidator>> definitionValidators;

    /** The type-name index shared with the engine, the schema module and the document compiler. */
    private final WorkflowTypeNames typeNames;

    /** Decides whether a scope-owned state class extends the default scope's; empty refuses differing classes. */
    private final Optional<WorkflowStateCompatibility> stateCompatibility;

    /** Where the plans of document keys that this registry does not hold come from; null until the engine binds one. */
    private volatile DocumentPlanSource documentPlanSource;

    /** Set once the engine's startup has ended; {@link #register(WorkflowDefinition)} is refused from then on. */
    private volatile boolean sealed;

    /** Set when the node is recording-only; {@link #load(DefinitionKey)} is refused from then on. */
    private volatile boolean recordingOnly;

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
        this(forkJoinValidator, fixedValidators(definitionValidators), typeNames, stateCompatibility);
    }

    /**
     * INTERNAL framework seam — the framework implementation behind a contract this module
     * documents; not an application contract and outside the maturity promise.
     *
     * <p>Creates a new empty {@code DefaultWorkflowRegistry} that resolves its contributed definition
     * validators from {@code definitionValidators} each time it builds a plan, instead of holding
     * them from construction. Validators that themselves depend on the registry graph (recorders,
     * handlers) are therefore constructed on the first plan build, not while the registry is
     * constructed, which keeps the dependency graph acyclic.
     *
     * @param forkJoinValidator validator to invoke on each registered plan; may be null only in
     *     test contexts that intentionally skip fork/join semantic checks
     * @param definitionValidators resolves the validators to invoke on each candidate definition;
     *     must not be {@code null}
     * @param typeNames the shared type-name index; must not be {@code null}
     * @param stateCompatibility the state comparison; must not be {@code null}, may be empty
     */
    public DefaultWorkflowRegistry(
            WorkflowPlanValidator forkJoinValidator,
            Provider<Set<WorkflowDefinitionValidator>> definitionValidators,
            WorkflowTypeNames typeNames,
            Optional<WorkflowStateCompatibility> stateCompatibility) {
        this.forkJoinValidator = forkJoinValidator;
        this.definitionValidators = Objects.requireNonNull(definitionValidators, "definitionValidators");
        this.typeNames = Objects.requireNonNull(typeNames, "typeNames");
        this.stateCompatibility = Objects.requireNonNull(stateCompatibility, "stateCompatibility");
    }

    private static Provider<Set<WorkflowDefinitionValidator>> fixedValidators(
            Set<WorkflowDefinitionValidator> definitionValidators) {
        Objects.requireNonNull(definitionValidators, "definitionValidators");
        Set<WorkflowDefinitionValidator> copy = Set.copyOf(definitionValidators);
        return () -> copy;
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
     * Registers a code definition in the default scope, builds its plan via the DSL, and stores the resulting
     * {@link RuntimeWorkflow}.
     *
     * <p>Registration is a startup operation: it is accepted while the registry is constructed and the engine starts,
     * and refused with {@link IllegalStateException} once the engine has {@linkplain #seal() sealed} the registry.
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
     *   <li>Throws if {@code (definitionId, definitionVersion)} is already registered.</li>
     *   <li>Throws if an earlier version of the same definition id names another contract; any number of
     *       definition ids and versions may name one contract.</li>
     *   <li>Throws if an earlier version of the same definition id has another state class.</li>
     *   <li>Stores the {@link RuntimeWorkflow} keyed by {@code (definitionId, definitionVersion)} — no
     *       half-committed state is visible to concurrent readers.</li>
     * </ol>
     *
     * <p>Every refusal names the scope, the definition id and the version.
     *
     * @param def the workflow definition to register
     * @throws WorkflowDefinitionException if the plan is invalid or a duplicate
     * @throws IllegalStateException if the registry is sealed; the message names the definition
     */
    @Override
    public void register(WorkflowDefinition<?, ?> def) {
        if (sealed) {
            throw new IllegalStateException("Definition '" + def.definitionId() + "' version "
                    + def.definitionVersion()
                    + " cannot be registered after startup; code definitions are registered while the application"
                    + " starts");
        }
        registerCode(def);
    }

    /**
     * Ends the registration window: {@link #register(WorkflowDefinition)} throws {@link IllegalStateException} from
     * now on, and {@link #adoptNameBinding} changes nothing. Calling it again has no effect.
     *
     * <p>INTERNAL: called only by the engine's bootstrap when startup ends; applications never call it.
     */
    public void seal() {
        sealed = true;
    }

    /** A definition built into the plan and runtime that a registration or a name binding publishes. */
    private record Compiled(WorkflowBuilder<?> builder, RuntimeWorkflow runtime, Map<String, String> binding) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerCode(WorkflowDefinition<?, ?> def) {
        // --- Pre-publish validation (no lock needed — produces only immutable local values) ---

        String scopeKey = WorkflowScopes.DEFAULT;
        String definitionId = def.definitionId();
        long definitionVersion = def.definitionVersion();
        Compiled compiled = compile(scopeKey, def, typeNames);
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
            for (WorkflowDefinitionValidator validator : definitionValidators.get()) {
                validator.validate(candidate);
            }
            publish(def, scopeKey, definitionId, definitionVersion, rw, compiled.binding());
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
     * Replaces the runtime of a registered code definition with one whose plan was built under the type names its
     * stored plan hash was first computed with, when that plan has the required hash.
     *
     * <p>INTERNAL: at startup the engine's bootstrap calls it for a code definition that was registered under
     * current logical names, before the stored name binding of its row was known, so that the node serves the plan
     * hash the row holds and a class that gained a logical name since does not change it. Nothing changes when the
     * hash differs, when the definition is not registered, or once the registry is {@linkplain #seal() sealed}.
     * Applications never call it.
     *
     * <p>The registration validators are not run again: a plan hash equal to {@code requiredPlanHash} means the plan
     * is the one that already passed registration, and only the name binding differs.
     *
     * @param key the default-scope key of the registered code definition
     * @param nameBinding the binary name of a state or payload class mapped to the name to use for it
     * @param requiredPlanHash the plan hash the rebuilt plan must have
     * @return the runtime now registered, or empty when nothing was replaced
     */
    public Optional<RuntimeWorkflow> adoptNameBinding(
            DefinitionKey key, Map<String, String> nameBinding, String requiredPlanHash) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nameBinding, "nameBinding");
        Objects.requireNonNull(requiredPlanHash, "requiredPlanHash");
        if (sealed) return Optional.empty();
        WorkflowDefinition<?, ?> def = retainedDefinition(key);
        if (def == null) return Optional.empty();
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

    private WorkflowDefinition<?, ?> retainedDefinition(DefinitionKey key) {
        rwLock.readLock().lock();
        try {
            var registered = sources.get(new ScopedId(key.scopeKey(), key.definitionId()));
            return registered == null ? null : registered.get(key.definitionVersion());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * A code definition as the application registered it.
     *
     * @param runtime the registered runtime
     * @param definition the definition object that was registered
     * @param nameBinding the binary name of the state class and of every signal and decision payload class of the
     *     plan, mapped to the name its plan hash used
     */
    public record CodeRegistration(
            RuntimeWorkflow runtime, WorkflowDefinition<?, ?> definition, Map<String, String> nameBinding) {}

    /**
     * Returns the code definitions with the definition objects and name bindings they were registered with.
     *
     * <p>INTERNAL: the engine's bootstrap records the code in the definition store with it; applications never call
     * it. {@link #codeDefinitions()} lists the same runtimes.
     *
     * @return immutable snapshot of the registrations, ordered by definition id and version; never null
     */
    public List<CodeRegistration> codeRegistrations() {
        rwLock.readLock().lock();
        try {
            List<CodeRegistration> result = new ArrayList<>();
            for (var entry : byDefinition.entrySet()) {
                for (var version : entry.getValue().entrySet()) {
                    WorkflowDefinition<?, ?> definition =
                            sources.get(entry.getKey()).get(version.getKey());
                    Map<String, String> binding = bindings.get(entry.getKey()).get(version.getKey());
                    result.add(new CodeRegistration(version.getValue(), definition, binding));
                }
            }
            result.sort(Comparator.comparing((CodeRegistration registration) ->
                            registration.runtime().plan().definitionId())
                    .thenComparingLong(
                            registration -> registration.runtime().plan().definitionVersion()));
            return List.copyOf(result);
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
        return "'" + EchoedNames.bounded(definitionId) + "' v" + definitionVersion + " in scope '"
                + EchoedNames.bounded(scopeKey) + "'";
    }

    /** Atomic publish phase: the write lock is held for the entire multi-step publish. */
    private void publish(
            WorkflowDefinition<?, ?> def,
            String scopeKey,
            String definitionId,
            long definitionVersion,
            RuntimeWorkflow rw,
            Map<String, String> nameBinding) {
        ScopedId scopedId = new ScopedId(scopeKey, definitionId);
        rwLock.writeLock().lock();
        try {
            // Reject exact duplicates, and a version whose contract differs from the earlier versions of its id
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
                            + " has another state type than the earlier versions of '"
                            + EchoedNames.bounded(definitionId)
                            + "' in that scope; every version of a definition in a scope must have the same state"
                            + " type");
                }
            }
            byDefinition
                    .computeIfAbsent(scopedId, k -> new ConcurrentSkipListMap<>())
                    .put(definitionVersion, rw);
            sources.computeIfAbsent(scopedId, k -> new HashMap<>()).put(definitionVersion, def);
            knownClasses
                    .computeIfAbsent(def.getClass(), k -> new HashMap<>())
                    .merge(
                            definitionId,
                            new KnownClass(definitionVersion, def.contract()),
                            (known, added) -> added.version() > known.version() ? added : known);
            bindings.computeIfAbsent(scopedId, k -> new HashMap<>()).put(definitionVersion, nameBinding);
        } finally {
            rwLock.writeLock().unlock();
        }
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
        requireExtension(
                defaultRw.contractName(), defaultRw.stateType(), scopeOwned, scopeKey, definitionId, scopeOwnedVersion);
    }

    private void requireExtension(
            String defaultContractName,
            Class<?> defaultStateType,
            RuntimeWorkflow scopeOwned,
            String scopeKey,
            String definitionId,
            long scopeOwnedVersion) {
        if (!defaultContractName.equals(scopeOwned.contractName()) || defaultStateType.equals(scopeOwned.stateType())) {
            return;
        }
        String subject = "definition " + describe(scopeKey, definitionId, scopeOwnedVersion)
                + " has another state type than the default scope's '" + EchoedNames.bounded(definitionId)
                + "' of the same contract";
        if (stateCompatibility.isEmpty()) {
            throw new WorkflowDefinitionException(subject
                    + "; the state comparison is unavailable (no WorkflowStateCompatibility is bound), so"
                    + " differing state types are refused");
        }
        Optional<String> incompatible =
                stateCompatibility.orElseThrow().firstIncompatibility(defaultStateType, scopeOwned.stateType());
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
     *   <li>For every {@link WaitSignalNode} with a non-null {@code correlationKeyCallbackId}: the
     *       id must resolve via {@link WorkflowCallbackRegistry#correlationKeyResolver(CallbackId)}.</li>
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
                    if (wsn.correlationKeyCallbackId() != null) {
                        CallbackId keyId = wsn.correlationKeyCallbackId();
                        try {
                            callbacks.correlationKeyResolver(keyId);
                        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                            throw new WorkflowDefinitionException("WaitSignalNode '" + wsn.stepId() + "' in " + label
                                    + " declares a correlation key but no correlationKeyResolver is registered"
                                    + " for callback '" + keyId.value() + "'");
                        }
                    }
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
     * <p>A key the registry does not hold is answered from the bound {@link DocumentPlanSource} if its plan is
     * resident there now; nothing is loaded.
     *
     * @throws WorkflowDefinitionNotFoundException (a {@link WorkflowVersionPinUnavailableException}) if neither the
     *     registry nor the source holds the version; the message names the scope, the definition id and the version
     */
    @Override
    public RuntimeWorkflow resolve(DefinitionKey key) {
        return resolve(key.scopeKey(), key.definitionId(), key.definitionVersion());
    }

    private RuntimeWorkflow resolve(String scopeKey, String definitionId, long version) {
        RuntimeWorkflow rw = lookup(scopeKey, definitionId, version);
        if (rw == null) {
            rw = resident(new DefinitionKey(scopeKey, definitionId, version)).orElse(null);
        }
        if (rw == null) {
            throw new WorkflowDefinitionNotFoundException(new DefinitionKey(scopeKey, definitionId, version));
        }
        return rw;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A key the registry does not hold is answered from the bound {@link DocumentPlanSource} only if its plan is
     * resident there now; nothing is loaded, built or read.
     */
    @Override
    public Optional<RuntimeWorkflow> find(DefinitionKey key) {
        RuntimeWorkflow held = lookup(key.scopeKey(), key.definitionId(), key.definitionVersion());
        return held != null ? Optional.of(held) : resident(key);
    }

    private Optional<RuntimeWorkflow> resident(DefinitionKey key) {
        DocumentPlanSource source = documentPlanSource;
        return source == null ? Optional.empty() : source.resident(key);
    }

    /**
     * Marks this registry as the registry of a recording-only node: {@link #load(DefinitionKey)} is refused from
     * then on.
     *
     * <p>INTERNAL: called only when the registry is assembled for a node configured with
     * {@code workflow.executor.enabled=false}; applications never call it.
     */
    public void markRecordingOnly() {
        this.recordingOnly = true;
    }

    /**
     * Binds the source of the plans of document keys that this registry does not hold.
     *
     * <p>INTERNAL: called only by the engine when it wires its plan cache; applications never call it. The source
     * is set once per engine, and a later call replaces it.
     *
     * @param source the source that {@link #load(DefinitionKey)}, {@link #find(DefinitionKey)} and
     *     {@link #resolve(DefinitionKey)} consult for keys the registry does not hold
     */
    public void bindDocumentPlanSource(DocumentPlanSource source) {
        this.documentPlanSource = Objects.requireNonNull(source, "source");
    }

    /**
     * {@inheritDoc}
     *
     * <p>A key the registry holds, which is every code definition and every document registered at startup, is
     * answered here. Any other key is handed to the bound {@link DocumentPlanSource}; without one the future fails
     * with {@link WorkflowDefinitionNotFoundException}. On a recording-only node, which holds no workflow code and
     * builds no plan, the future fails with {@link WorkflowNodeRoleException}.
     */
    @Override
    public Future<RuntimeWorkflow> load(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        if (recordingOnly) {
            return Future.failedFuture(new WorkflowNodeRoleException("load"));
        }
        RuntimeWorkflow held = lookup(key.scopeKey(), key.definitionId(), key.definitionVersion());
        if (held != null) {
            return Future.succeededFuture(held);
        }
        DocumentPlanSource source = documentPlanSource;
        if (source == null) {
            return Future.failedFuture(new WorkflowDefinitionNotFoundException(key));
        }
        try {
            return source.load(key);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Builds the runtime of a definition under the registration rules that concern the definition alone, and
     * publishes nothing.
     *
     * <p>INTERNAL: the engine's plan cache builds the plan of a stored document with it. The plan graph, signal and
     * callback checks and every contributed {@link WorkflowDefinitionValidator} run as they do for
     * {@link #register(WorkflowDefinition)}; the comparisons with the versions already registered do not, because nothing
     * is registered. The classes of the definition enter the shared type-name index, which only adds
     * names, and stay there when the build succeeds.
     *
     * @param scopeKey the scope that owns the definition
     * @param def the definition to build
     * @param nameBinding the binary name of a state or payload class mapped to the name its plan hash used; empty
     *     names every class by its current logical name
     * @return the runtime of the definition; it is not registered
     * @throws WorkflowDefinitionException if the plan is invalid or a validator refuses it
     */
    public RuntimeWorkflow build(String scopeKey, WorkflowDefinition<?, ?> def, Map<String, String> nameBinding) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(nameBinding, "nameBinding");
        requireValidScope(scopeKey, def.definitionId(), def.definitionVersion());
        WorkflowTypeNames names =
                nameBinding.isEmpty() ? typeNames : DefaultWorkflowTypeNames.bound(typeNames, nameBinding);
        Compiled compiled = compile(scopeKey, def, names);
        Runnable undoIndexing = indexCandidate(def, compiled.builder());
        try {
            RegisteredWorkflowDefinition candidate = new RegisteredWorkflowDefinition(
                    compiled.runtime().plan(), compiled.builder().startPayloadType());
            for (WorkflowDefinitionValidator validator : definitionValidators.get()) {
                validator.validate(candidate);
            }
            return compiled.runtime();
        } catch (RuntimeException | Error e) {
            undoIndexing.run();
            throw e;
        }
    }

    /**
     * The result of comparing a scope-owned plan with the default scope's definition of its id.
     *
     * @param defaultStateTypeName the logical state type name of the default scope's definition the plan was compared
     *     with, or {@code null} when the default scope holds none
     * @param accepted {@code true} when the plan's state type is the default scope's or extends it, as a
     *     {@link WorkflowStateCompatibility} decides
     */
    public record StateComparison(
            @jakarta.annotation.Nullable String defaultStateTypeName, boolean accepted) {}

    /**
     * Compares the state type of a scope-owned plan with the default scope's definition of the same id.
     *
     * <p>INTERNAL: the engine's plan cache evaluates the plans it builds with it; applications never call it.
     *
     * @param scopeOwned the plan of a version of a scope other than the default one
     * @return the comparison; accepted when the default scope holds no definition of the id
     */
    public StateComparison compareWithDefault(RuntimeWorkflow scopeOwned) {
        Objects.requireNonNull(scopeOwned, "scopeOwned");
        rwLock.readLock().lock();
        try {
            var defaults = byDefinition.get(
                    new ScopedId(WorkflowScopes.DEFAULT, scopeOwned.plan().definitionId()));
            if (defaults == null || defaults.isEmpty()) {
                return new StateComparison(null, true);
            }
            RuntimeWorkflow defaultRw = defaults.firstEntry().getValue();
            try {
                requireExtension(
                        defaultRw,
                        scopeOwned,
                        scopeOwned.definitionScopeKey(),
                        scopeOwned.plan().definitionId(),
                        scopeOwned.plan().definitionVersion());
                return new StateComparison(defaultRw.plan().stateTypeName(), true);
            } catch (WorkflowDefinitionException refused) {
                return new StateComparison(defaultRw.plan().stateTypeName(), false);
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Compares the state type of a scope-owned plan with the default scope's current definition as its stored row
     * names it, which holds for a document of the default scope although none is registered.
     *
     * <p>INTERNAL: the engine reads the default scope's current row and passes its names; applications never call
     * it. A state type name that no class resolves cannot be compared and is refused, so the comparison fails closed.
     *
     * @param scopeOwned the plan of a version of a scope other than the default one
     * @param defaultContractName the logical contract name of the default scope's current row
     * @param defaultStateTypeName the logical state type name of the default scope's current row
     * @return the comparison, which names {@code defaultStateTypeName}
     */
    public StateComparison compareWithDefault(
            RuntimeWorkflow scopeOwned, String defaultContractName, String defaultStateTypeName) {
        Objects.requireNonNull(scopeOwned, "scopeOwned");
        Objects.requireNonNull(defaultContractName, "defaultContractName");
        Objects.requireNonNull(defaultStateTypeName, "defaultStateTypeName");
        Optional<Class<?>> defaultState = typeNames.find(defaultStateTypeName);
        if (!defaultContractName.equals(scopeOwned.contractName())) {
            return new StateComparison(defaultStateTypeName, true);
        }
        if (defaultState.isEmpty()) {
            return new StateComparison(defaultStateTypeName, false);
        }
        try {
            requireExtension(
                    defaultContractName,
                    defaultState.orElseThrow(),
                    scopeOwned,
                    scopeOwned.definitionScopeKey(),
                    scopeOwned.plan().definitionId(),
                    scopeOwned.plan().definitionVersion());
            return new StateComparison(defaultStateTypeName, true);
        } catch (WorkflowDefinitionException refused) {
            return new StateComparison(defaultStateTypeName, false);
        }
    }

    /**
     * The logical state type name of the default scope's definition of the id, which a scope-owned plan is compared
     * with.
     *
     * <p>INTERNAL: the engine's plan cache reads it to see whether an earlier {@linkplain #compareWithDefault
     * comparison} is still current; applications never call it.
     *
     * @param definitionId the definition id
     * @return the name, or empty when the default scope holds no definition of the id
     */
    public Optional<String> defaultStateTypeName(String definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        rwLock.readLock().lock();
        try {
            var defaults = byDefinition.get(new ScopedId(WorkflowScopes.DEFAULT, definitionId));
            return defaults == null || defaults.isEmpty()
                    ? Optional.empty()
                    : Optional.of(defaults.firstEntry().getValue().plan().stateTypeName());
        } finally {
            rwLock.readLock().unlock();
        }
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
     * <p>Answered under the read lock from what the registrations recorded. A class that was never registered, or
     * whose registration was refused, is unknown. When several versions of one id share the class, the highest
     * version's contract answers.
     */
    @Override
    public Optional<RegisteredDefinitionClass> definitionClass(Class<?> definitionClass) {
        Objects.requireNonNull(definitionClass, "definitionClass");
        rwLock.readLock().lock();
        try {
            HashMap<String, KnownClass> byId = knownClasses.get(definitionClass);
            if (byId == null || byId.size() != 1) {
                return Optional.empty();
            }
            Map.Entry<String, KnownClass> known = byId.entrySet().iterator().next();
            Class<?> contract = known.getValue().contract();
            return Optional.of(
                    new RegisteredDefinitionClass(known.getKey(), typeNames.contractNameOf(contract), contract));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flattens all per-version inner maps into a single immutable list. The order of entries is
     * deterministic within each definitionId (ascending version), but the order across
     * different definitionIds is not guaranteed.
     */
    @Override
    public Collection<RuntimeWorkflow> codeDefinitions() {
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
}
