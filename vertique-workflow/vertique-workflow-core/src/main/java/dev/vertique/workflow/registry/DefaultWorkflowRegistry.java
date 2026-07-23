// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.contract.WorkflowContractMetadata;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Default concrete implementation of {@link WorkflowRegistry}.
 *
 * <p>This class is instantiated by {@code WorkflowCoreModule.registry(...)} and is the single
 * in-process registry for all registered workflow definitions. It validates each definition at
 * registration time and rejects invalid plans.
 *
 * <p>Internal storage:
 * <ul>
 *   <li>{@code byDefinition} — {@link ConcurrentHashMap} keyed by definitionId, inner
 *       {@link ConcurrentSkipListMap} keyed by version; allows efficient lookup of the highest
 *       version via {@code lastEntry()}.</li>
 *   <li>{@code byContract} — {@link ConcurrentHashMap} keyed by contract interface class; links
 *       the class to its definition metadata.</li>
 * </ul>
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #register(WorkflowDefinition)} is safe to call at any time after construction.
 *       Multiple callers can invoke it concurrently; the underlying publication is serialized by
 *       {@code writeLock}.</li>
 *   <li>Reads ({@link #resolveCurrent}, {@link #resolvePinned}, {@link #contractMetadata},
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
 * {@code register()} is intrinsically multi-step — it inserts a version into {@code byDefinition},
 * then inserts into {@code byContract}, and rolls back {@code byDefinition} on a contract
 * conflict. Without a write-lock, a concurrent {@code resolveCurrent()} could observe the version
 * between the first insert and the rollback, then see it disappear — an invisible-update
 * anomaly. The write-lock serializes the publish phase, while allowing concurrent reads during
 * the common steady-state.
 */
public final class DefaultWorkflowRegistry implements WorkflowRegistry {

    // --- Internal storage ---

    /** Keyed by definitionId → (version → RuntimeWorkflow). */
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Long, RuntimeWorkflow>> byDefinition =
            new ConcurrentHashMap<>();

    /** Keyed by contract interface class → contract metadata. */
    private final ConcurrentHashMap<Class<?>, WorkflowContractMetadata> byContract = new ConcurrentHashMap<>();

    /**
     * Serializes the multi-step publish phase of {@link #register}.
     * Reads take {@code readLock()}; the publish phase of register takes {@code writeLock()}.
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** Optional fork/join semantic validator; null when none is wired (legacy callers). */
    private final WorkflowPlanValidator forkJoinValidator;

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
        this.forkJoinValidator = forkJoinValidator;
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
     *   <li>Computes {@code planHash}.</li>
     * </ol>
     *
     * <p>Publish phase (inside {@code writeLock}):
     * <ol>
     *   <li>Stores the {@link RuntimeWorkflow} keyed by {@code (definitionId, definitionVersion)};
     *       throws if already present.</li>
     *   <li>Stores {@link WorkflowContractMetadata} keyed by {@code def.contract()}; on conflict,
     *       rolls back the version insert and rethrows — no half-committed state is visible to
     *       concurrent readers.</li>
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
        String stateTypeName = def.stateType().getName();

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

        // Build per-definition callbacks and validate callback-registry consistency
        WorkflowCallbackRegistry perDefCallbacks = builder.callbackRegistry();
        validateCallbackConsistency(plan, perDefCallbacks, definitionId, definitionVersion);
        RuntimeWorkflow rw = RuntimeWorkflow.of(
                plan,
                def.stateType(),
                builder.startPayloadType(),
                builder.initialState(),
                builder.signalPayloadTypes(),
                perDefCallbacks);

        // --- Atomic publish phase (write-lock held for the entire multi-step publish) ---

        rwLock.writeLock().lock();
        try {
            // Store by (definitionId, version) — reject exact duplicates
            ConcurrentSkipListMap<Long, RuntimeWorkflow> versions =
                    byDefinition.computeIfAbsent(definitionId, k -> new ConcurrentSkipListMap<>());
            if (versions.putIfAbsent(definitionVersion, rw) != null) {
                throw new WorkflowDefinitionException("definition '" + definitionId + "' v" + definitionVersion
                        + " is already registered; duplicate registrations are not allowed");
            }

            // Store contract metadata — reject re-binding the same contract class to a different definition
            WorkflowContractMetadata metadata =
                    new WorkflowContractMetadata(def.contract(), definitionId, definitionVersion);
            WorkflowContractMetadata existingContract = byContract.putIfAbsent(def.contract(), metadata);
            if (existingContract != null) {
                // Rollback the version we just inserted so no half-registered state persists.
                versions.remove(definitionVersion);
                throw new WorkflowDefinitionException(
                        "contract " + def.contract().getName()
                                + " is already registered to definition '" + existingContract.definitionId() + "' v"
                                + existingContract.definitionVersion() + "'; cannot rebind to '" + definitionId + "' v"
                                + definitionVersion + "'");
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        // Per-definition callbacks live on `rw.callbacks()` (used by the engine via
        // RuntimeWorkflow.callbacks() during execution). We intentionally do NOT merge them into
        // any global aggregate: callback ids are scoped to {stepId, role} which collides across
        // definitions/versions, and a globally-keyed map would silently overwrite or require
        // callers to know which definition a callback belongs to. Consumers must look up
        // callbacks via {@link RuntimeWorkflow#callbacks()} on a resolved instance.
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
     * {@inheritDoc}
     *
     * @throws WorkflowDefinitionMissingException if no definition with the given id is registered
     */
    @Override
    public RuntimeWorkflow resolveCurrent(String definitionId) {
        rwLock.readLock().lock();
        try {
            NavigableMap<Long, RuntimeWorkflow> versions = byDefinition.get(definitionId);
            if (versions == null || versions.isEmpty()) {
                throw new WorkflowDefinitionMissingException(definitionId);
            }
            return versions.lastEntry().getValue();
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
     */
    @Override
    public WorkflowContractMetadata contractMetadata(Class<?> contractInterface) {
        rwLock.readLock().lock();
        try {
            WorkflowContractMetadata meta = byContract.get(contractInterface);
            if (meta == null) {
                throw new dev.vertique.workflow.exception.WorkflowProxyContractException(
                        "No workflow definition registered for contract: " + contractInterface.getName());
            }
            return meta;
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
}
