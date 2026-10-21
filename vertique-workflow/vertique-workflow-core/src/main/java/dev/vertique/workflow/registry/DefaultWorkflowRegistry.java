// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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
 * same contract as every earlier version of its id, and every signal its plan waits for must be
 * declared on that contract.
 *
 * <p>Internal storage:
 * <ul>
 *   <li>{@code byDefinition} — {@link ConcurrentHashMap} keyed by definitionId, inner
 *       {@link ConcurrentSkipListMap} keyed by version; allows efficient lookup of the highest
 *       version via {@code lastEntry()}.</li>
 *   <li>{@code sources} — the registered definition objects per id and version; also the source of
 *       {@link #definitionClass(Class)}.</li>
 *   <li>{@code deprecated} — set of version numbers per definitionId that are marked deprecated;
 *       always a subset of the registered versions and mutated only under {@code writeLock}.</li>
 * </ul>
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #register(WorkflowDefinition)} is safe to call at any time after construction.
 *       Multiple callers can invoke it concurrently; the underlying publication is serialized by
 *       {@code writeLock}.</li>
 *   <li>Reads ({@link #resolveCurrent}, {@link #resolvePinned},
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
 * {@link #resolveCurrent} or {@link #resolvePinned} call) get a coherent per-definition view.
 *
 * <p>Why {@link ReentrantReadWriteLock} rather than lock-free structures alone:
 * {@code register()} is intrinsically multi-step — it checks the new version against the versions
 * of its definition id that are already registered, then inserts it into {@code byDefinition} and
 * {@code sources}. Without a write-lock, two concurrent registrations of one id could both pass the
 * contract check, and a concurrent {@code resolveCurrent()} could observe a half-published
 * version. The write-lock serializes the publish phase, while allowing concurrent reads during
 * the common steady-state.
 */
public final class DefaultWorkflowRegistry implements WorkflowRegistry {

    /** The most undeclared signal names a refusal message repeats. */
    private static final int MAX_REPORTED_SIGNALS = 5;

    // --- Internal storage ---

    /** Keyed by definitionId → (version → RuntimeWorkflow). */
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, RuntimeWorkflow>> byDefinition =
            new ConcurrentHashMap<>();

    /**
     * Keyed by definitionId → (version → the definition that was registered). Guarded by {@link #rwLock}
     * together with {@link #byDefinition}; it holds exactly the registered versions.
     */
    private final HashMap<String, HashMap<Long, WorkflowDefinition<?, ?>>> sources = new HashMap<>();

    /**
     * Keyed by definitionId → versions marked deprecated. Only registered versions are ever present;
     * guarded by {@link #rwLock} together with {@link #byDefinition}.
     */
    private final ConcurrentHashMap<String, Set<Long>> deprecated = new ConcurrentHashMap<>();

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
        Objects.requireNonNull(definitionValidators, "definitionValidators");
        this.forkJoinValidator = forkJoinValidator;
        this.definitionValidators = Set.copyOf(definitionValidators);
        this.typeNames = Objects.requireNonNull(typeNames, "typeNames");
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
     * Registers a workflow definition, builds its plan via the DSL, and stores the resulting
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
     *   <li>Throws if {@code (definitionId, definitionVersion)} is already registered.</li>
     *   <li>Throws if an earlier version of the same definition id names another contract; any
     *       number of definition ids and versions may name one contract.</li>
     *   <li>Stores the {@link RuntimeWorkflow} keyed by {@code (definitionId, definitionVersion)} —
     *       no half-committed state is visible to concurrent readers.</li>
     * </ol>
     *
     * @param def the workflow definition to register
     * @throws WorkflowDefinitionException if the plan is invalid or a duplicate
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void register(WorkflowDefinition<?, ?> def) {
        // --- Pre-publish validation (no lock needed — produces only immutable local values) ---

        WorkflowBuilder builder = new WorkflowBuilder<>();
        def.define(builder);

        String definitionId = def.definitionId();
        long definitionVersion = def.definitionVersion();
        String stateTypeName = typeNames.nameOf(def.stateType());

        // build() validates that init() was called; throws WorkflowDefinitionException if not
        WorkflowPlan plan = builder.build(definitionId, definitionVersion, stateTypeName);

        // PRD-WF-002: fork/join semantic validation runs FIRST so structural fork/join issues
        // surface before the existing graph/signal/callback checks.
        if (forkJoinValidator != null) {
            forkJoinValidator.validate(plan);
        }

        // Validate plan graph shape BEFORE building the nodeById map (so errors surface early)
        validatePlanGraph(plan, definitionId, definitionVersion);

        // Validate signal-name uniqueness
        validateSignalUniqueness(plan, definitionId, definitionVersion);

        // Every awaited signal must be declared on the contract
        validateDeclaredSignals(plan, def.contract(), definitionId, definitionVersion);

        // Build per-definition callbacks and validate callback-registry consistency
        WorkflowCallbackRegistry perDefCallbacks = builder.callbackRegistry();
        validateCallbackConsistency(plan, perDefCallbacks, definitionId, definitionVersion);
        RuntimeWorkflow rw = RuntimeWorkflow.of(
                plan,
                typeNames.contractNameOf(def.contract()),
                def.stateType(),
                builder.startPayloadType(),
                builder.initialState(),
                builder.signalPayloadTypes(),
                perDefCallbacks);

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
            publish(def, definitionId, definitionVersion, rw);
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

    /** Atomic publish phase: the write lock is held for the entire multi-step publish. */
    private void publish(
            WorkflowDefinition<?, ?> def, String definitionId, long definitionVersion, RuntimeWorkflow rw) {
        rwLock.writeLock().lock();
        try {
            // Reject exact duplicates, and a version whose contract differs from its id's earlier versions
            ConcurrentSkipListMap<Long, RuntimeWorkflow> registered = byDefinition.get(definitionId);
            if (registered != null && !registered.isEmpty()) {
                if (registered.containsKey(definitionVersion)) {
                    throw new WorkflowDefinitionException("definition '" + definitionId + "' v" + definitionVersion
                            + " is already registered; duplicate registrations are not allowed");
                }
                String earlierContract = registered.firstEntry().getValue().contractName();
                if (!earlierContract.equals(rw.contractName())) {
                    throw new WorkflowDefinitionException("definition '" + definitionId + "' v" + definitionVersion
                            + " names contract '" + rw.contractName() + "' but earlier versions of '" + definitionId
                            + "' name contract '" + earlierContract
                            + "'; every version of a definition must name the same contract");
                }
            }
            byDefinition
                    .computeIfAbsent(definitionId, k -> new ConcurrentSkipListMap<>())
                    .put(definitionVersion, rw);
            sources.computeIfAbsent(definitionId, k -> new HashMap<>()).put(definitionVersion, def);
        } finally {
            rwLock.writeLock().unlock();
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
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if any structural rule is violated
     */
    private static void validatePlanGraph(WorkflowPlan plan, String definitionId, long definitionVersion) {
        String label = "plan '" + definitionId + "' v" + definitionVersion;

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
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if a required callback is missing
     */
    private static void validateCallbackConsistency(
            WorkflowPlan plan, WorkflowCallbackRegistry callbacks, String definitionId, long definitionVersion) {
        String label = "plan '" + definitionId + "' v" + definitionVersion;
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
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if duplicate signal names are found
     */
    private static void validateSignalUniqueness(WorkflowPlan plan, String definitionId, long definitionVersion) {
        Set<String> seen = new HashSet<>();
        for (var node : plan.nodes()) {
            if (node instanceof WaitSignalNode w) {
                if (!seen.add(w.signalName())) {
                    throw new WorkflowDefinitionException(
                            "duplicate signal name '" + w.signalName() + "' in plan '" + definitionId + "' v"
                                    + definitionVersion
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
     * @param definitionId the definition id (for error messages)
     * @param definitionVersion the definition version (for error messages)
     * @throws WorkflowDefinitionException if the plan waits for a signal the contract does not declare
     */
    private static void validateDeclaredSignals(
            WorkflowPlan plan, Class<?> contract, String definitionId, long definitionVersion) {
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
            throw new WorkflowDefinitionException("definition '" + definitionId + "' v" + definitionVersion
                    + " waits for signals that contract " + contract.getName() + " does not declare: "
                    + describeMissing(missing)
                    + "; declare each awaited signal as a @WorkflowSignal method on the contract");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Selects the highest registered version that is not marked deprecated. Deprecated
     * versions stay available through {@link #resolvePinned(String, long)}.
     *
     * @throws WorkflowDefinitionMissingException if no definition with the given id is registered,
     *     or every registered version of it is deprecated
     */
    @Override
    public RuntimeWorkflow resolveCurrent(String definitionId) {
        rwLock.readLock().lock();
        try {
            NavigableMap<Long, RuntimeWorkflow> versions = byDefinition.get(definitionId);
            if (versions == null || versions.isEmpty()) {
                throw new WorkflowDefinitionMissingException(definitionId);
            }
            Set<Long> marked = deprecated.getOrDefault(definitionId, Set.of());
            for (Map.Entry<Long, RuntimeWorkflow> entry :
                    versions.descendingMap().entrySet()) {
                if (!marked.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new WorkflowDefinitionMissingException(definitionId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws WorkflowVersionPinUnavailableException if the definition id is registered but the
     *     specified version is not found
     * @throws WorkflowDefinitionMissingException if no definition with the given id is registered
     *     at all
     */
    @Override
    public RuntimeWorkflow resolvePinned(String definitionId, long version) {
        rwLock.readLock().lock();
        try {
            NavigableMap<Long, RuntimeWorkflow> versions = byDefinition.get(definitionId);
            if (versions == null || versions.isEmpty()) {
                throw new WorkflowVersionPinUnavailableException(definitionId, version, null);
            }
            RuntimeWorkflow rw = versions.get(version);
            if (rw == null) {
                throw new WorkflowVersionPinUnavailableException(definitionId, version, null);
            }
            return rw;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Derived from the registered definitions under the read lock, so it follows
     * {@link #unregister(String, long)}. When several versions of one id share the class, the
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
            for (Map.Entry<String, HashMap<Long, WorkflowDefinition<?, ?>>> byId : sources.entrySet()) {
                for (Map.Entry<Long, WorkflowDefinition<?, ?>> byVersion :
                        byId.getValue().entrySet()) {
                    if (byVersion.getValue().getClass() != definitionClass) {
                        continue;
                    }
                    if (foundId != null && !foundId.equals(byId.getKey())) {
                        return Optional.empty();
                    }
                    if (foundId == null || byVersion.getKey() > foundVersion) {
                        foundId = byId.getKey();
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
     * deterministic within each definitionId (ascending version), but the order across different
     * definitionIds is not guaranteed.
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
     * idempotent. {@link #unregister(String, long)} clears the mark.
     */
    @Override
    public void markDeprecated(String definitionId, long version) {
        rwLock.writeLock().lock();
        try {
            var versions = byDefinition.get(definitionId);
            if (versions == null || !versions.containsKey(version)) return;
            deprecated
                    .computeIfAbsent(definitionId, k -> ConcurrentHashMap.newKeySet())
                    .add(version);
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
    public boolean isDeprecated(String definitionId, long version) {
        rwLock.readLock().lock();
        try {
            return deprecated.getOrDefault(definitionId, Set.of()).contains(version);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the definition object passed to {@link #register}, read under the same lock as the
     * registration, so it is empty for a version that was never registered and again after
     * {@link #unregister(String, long)}.
     */
    @Override
    public Optional<WorkflowDefinition<?, ?>> registeredDefinition(String definitionId, long version) {
        rwLock.readLock().lock();
        try {
            var registered = sources.get(definitionId);
            return registered == null ? Optional.empty() : Optional.ofNullable(registered.get(version));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Removes the version and its deprecation mark under the publication lock. */
    @Override
    public void unregister(String definitionId, long version) {
        rwLock.writeLock().lock();
        try {
            var versions = byDefinition.get(definitionId);
            if (versions == null || versions.remove(version) == null) return;
            if (versions.isEmpty()) byDefinition.remove(definitionId, versions);
            sources.computeIfPresent(definitionId, (k, registered) -> {
                registered.remove(version);
                return registered.isEmpty() ? null : registered;
            });
            deprecated.computeIfPresent(definitionId, (k, marked) -> {
                marked.remove(version);
                return marked.isEmpty() ? null : marked;
            });
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
