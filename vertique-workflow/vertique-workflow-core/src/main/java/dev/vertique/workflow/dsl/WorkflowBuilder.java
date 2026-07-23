// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.plan.BranchRetryPolicy;
import dev.vertique.workflow.plan.BranchStart;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.DecisionNode;
import dev.vertique.workflow.plan.FailNode;
import dev.vertique.workflow.plan.ForkNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.JoinNode;
import dev.vertique.workflow.plan.ReminderSpec;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Fluent DSL for constructing a {@link WorkflowPlan} and populating a
 * {@link WorkflowCallbackRegistry}.
 *
 * <p>The builder allocates a {@link CallbackId} for every Java function passed in, registers the
 * function in the callback registry under that id, and records the corresponding plan node. The
 * resulting plan contains only string ids and {@code CallbackId} references — no executable
 * functions — making it serialization-friendly.
 *
 * <p>The builder is consumed by {@link WorkflowDefinition#define(WorkflowBuilder)} and is not
 * intended for direct use by application code.
 *
 * <p>Usage contract:
 * <ol>
 *   <li>Call {@link #init(Class, Function)} exactly once before any step DSL method.</li>
 *   <li>Call {@link #initialStep(String)} to set the first step id.</li>
 *   <li>Chain step methods ({@link #dispatch}, {@link #waitFor}, {@link #waitForSignal},
 *       {@link #timer}, {@link #timerAt}, {@link #publishAt(String, java.time.Instant)},
 *       {@link #expireAt(String, java.time.Instant)}, {@link #decide}, {@link #complete},
 *       {@link #fail}, {@link #compensate}).</li>
 *   <li>The registry calls {@link #build(String, long, String)} to produce the final
 *       {@link WorkflowPlan} after {@code define()} returns.</li>
 * </ol>
 *
 * @param <S> the workflow state type
 */
public final class WorkflowBuilder<S> {

    /**
     * Stable callback id for the plan-level {@link #subject(Function)} resolver. Plan-level (not
     * step-bound), so it does not flow through the per-step {@link #callbackId(String, String)}
     * helper.
     */
    private static final CallbackId SUBJECT_RESOLVER_ID = new CallbackId("$subject.resolver");

    // --- Internal state ---

    private Class<?> startPayloadTypeInternal;

    @SuppressWarnings("rawtypes")
    private Function initialStateFn;

    private String initialStepIdInternal;
    private boolean initCalled = false;
    private boolean stepCalled = false;

    /** Nodes in declaration order; insertion-ordered. */
    private final List<WorkflowNode> nodes = new ArrayList<>();

    /** Payload factories keyed by CallbackId value. */
    private final Map<String, Function<Object, Object>> payloadFactories = new HashMap<>();

    /** State updaters keyed by CallbackId value. */
    private final Map<String, BiFunction<Object, Object, Object>> stateUpdaters = new HashMap<>();

    /** Decision resolvers keyed by CallbackId value. */
    private final Map<String, Function<Object, String>> decisionResolvers = new HashMap<>();

    /** Fail-message factories keyed by CallbackId value. */
    private final Map<String, Function<Object, String>> failMessageFactories = new HashMap<>();

    /** Timer resolvers keyed by CallbackId value. */
    private final Map<String, Function<Object, Instant>> timerResolvers = new HashMap<>();

    /** State mutators keyed by CallbackId value. */
    private final Map<String, Function<Object, Object>> stateMutators = new HashMap<>();

    /** Signal payload types keyed by signal name; populated by waitFor calls. */
    private final Map<String, Class<?>> signalPayloadTypes = new LinkedHashMap<>();

    /** Decision applicators keyed by CallbackId value; populated by task(...) calls. */
    private final Map<String, BiFunction<Object, Object, Object>> decisionApplicators = new HashMap<>();

    /** Task assignment resolvers keyed by CallbackId value; populated by task(...) calls. */
    private final Map<String, Function<Object, TaskAssignment>> taskAssignmentResolvers = new HashMap<>();

    /** Subject resolvers keyed by CallbackId value; populated by subject(...) call. */
    private final Map<String, Function<Object, WorkflowSubjectRef>> subjectResolvers = new HashMap<>();

    /**
     * Branch-result reducers keyed by CallbackId value; populated by JoinScope terminators
     * (PRD-WF-002). Each reducer has runtime type
     * {@code BiFunction<Object, Map<String, BranchResult>, Object>}.
     */
    private final Map<String, BiFunction<Object, Map<String, BranchResult>, Object>> branchResultReducers =
            new HashMap<>();

    /**
     * The CallbackId of the subject resolver registered via {@link #subject(Function)}, or
     * {@code null} when no subject resolver was configured. At most one subject resolver per
     * definition.
     */
    @Nullable
    private CallbackId subjectResolverCallbackId;

    /**
     * Constructs a new builder.
     *
     * <p>Constructed by the {@code DefaultWorkflowRegistry} before passing to
     * {@link WorkflowDefinition#define(WorkflowBuilder)}.
     */
    public WorkflowBuilder() {}

    // --- Initializer ---

    /**
     * Declares the start-payload type and the function that maps the payload to the workflow's
     * initial state.
     *
     * <p>This method MUST be called exactly once before any step DSL methods. The registry
     * extracts {@code startPayloadType} and {@code initialState} from the builder's internal state
     * after {@code define()} returns.
     *
     * @param <P> the start payload type
     * @param startPayloadType the class of the start payload
     * @param initialState function that maps the start payload to the initial workflow state
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public <P> WorkflowBuilder<S> init(Class<P> startPayloadType, Function<P, S> initialState) {
        if (initCalled) {
            throw new WorkflowDefinitionException("init(...) can only be called once per definition");
        }
        if (stepCalled) {
            throw new WorkflowDefinitionException("init(...) must be called before any step DSL methods");
        }
        this.startPayloadTypeInternal = startPayloadType;
        this.initialStateFn = (Function<Object, Object>) (Function<?, ?>) initialState;
        this.initCalled = true;
        return this;
    }

    // --- Step id anchor ---

    /**
     * Sets the initial step id — the step the engine executes first after {@code start()}.
     *
     * <p>The step id must be declared as a node in the builder before {@link #build(String, long)}
     * is called.
     *
     * @param stepId the id of the first step to execute
     * @return this builder for chaining
     */
    public WorkflowBuilder<S> initialStep(String stepId) {
        if (!initCalled) {
            throw new WorkflowDefinitionException("init(...) must be called before any step DSL methods");
        }
        stepCalled = true;
        this.initialStepIdInternal = stepId;
        return this;
    }

    // --- Step DSL methods ---

    /**
     * Adds a service-dispatch step without compensation.
     *
     * <p>The engine will invoke {@code payloadFactory} with the current state to build the request
     * payload, emit a {@code SERVICE} intent, and immediately advance to {@code nextStepId}.
     *
     * @param stepId unique step identifier within this plan
     * @param targetId service target id registered in the application's service registry
     * @param payloadFactory function that derives the request payload from the current state
     * @param nextStepId step to advance to after the intent is recorded
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> dispatch(
            String stepId, String targetId, Function<S, Object> payloadFactory, String nextStepId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "payload");
        payloadFactories.put(cbId.value(), (Function<Object, Object>) (Function<?, ?>) payloadFactory);
        nodes.add(new ServiceDispatchNode(stepId, targetId, cbId, null, nextStepId));
        return this;
    }

    /**
     * Adds a service-dispatch step with a corresponding compensation step.
     *
     * <p>Behaves like {@link #dispatch(String, String, Function, String)} but also records the
     * {@code compensationStepId} so the engine can roll back this dispatch during the compensation
     * flow.
     *
     * @param stepId unique step identifier within this plan
     * @param targetId service target id registered in the application's service registry
     * @param payloadFactory function that derives the request payload from the current state
     * @param compensationStepId step id of the matching {@link dev.vertique.workflow.plan.CompensationNode}
     * @param nextStepId step to advance to after the intent is recorded
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> dispatchWithCompensation(
            String stepId,
            String targetId,
            Function<S, Object> payloadFactory,
            String compensationStepId,
            String nextStepId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "payload");
        payloadFactories.put(cbId.value(), (Function<Object, Object>) (Function<?, ?>) payloadFactory);
        nodes.add(new ServiceDispatchNode(stepId, targetId, cbId, compensationStepId, nextStepId));
        return this;
    }

    /**
     * Adds a signal-wait step that suspends the workflow until the named signal arrives.
     *
     * <p>The engine sets the instance status to {@code WAITING} and records
     * {@code waitType="SIGNAL"} and {@code waitKey=signalName}. When the signal arrives, the engine
     * calls {@code stateUpdater} with the current state and the typed payload, then advances to
     * {@code nextStepId}.
     *
     * @param <P> the expected signal payload type
     * @param stepId unique step identifier within this plan
     * @param signalName name of the signal this step waits for; must be unique within the plan
     * @param payloadClass the runtime class of the expected signal payload; stored as
     *     {@code payloadTypeName} in the plan node for engine-side coercion
     * @param stateUpdater function that merges the incoming signal payload into the current state
     * @param nextStepId step to advance to after the signal is applied
     * @return this builder for chaining
     * @deprecated since cycle 2 — prefer {@link #waitForSignal(String, String, Class)} which
     *     provides an explicit fluent chain and supports optional timeout branches
     */
    @Deprecated(since = "cycle 2")
    @SuppressWarnings("unchecked")
    public <P> WorkflowBuilder<S> waitFor(
            String stepId,
            String signalName,
            Class<P> payloadClass,
            BiFunction<S, P, S> stateUpdater,
            String nextStepId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "updater");
        stateUpdaters.put(cbId.value(), (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) stateUpdater);
        signalPayloadTypes.put(signalName, payloadClass);
        nodes.add(new WaitSignalNode(stepId, signalName, payloadClass.getName(), cbId, nextStepId, null));
        return this;
    }

    /**
     * Begins a signal-wait step with an explicit step id, signal name, and payload type.
     *
     * <p>The returned {@link SignalWaitBuilder} must have {@code onSignal(...)} called on it
     * before invoking {@code toStepOnSignal(...)}. The resulting step emits a {@link WaitSignalNode}
     * with no timeout branch unless the chain is continued via
     * {@link SignalWaitTerminator#timeoutAfter(Duration)},
     * {@link SignalWaitTerminator#timeoutAt(Instant)}, or
     * {@link SignalWaitTerminator#timeoutFromState(Function)}.
     *
     * <p>Example — simple signal wait:
     * <pre>{@code
     *   wf.waitForSignal("wait-shipped", "order.shipped", ShippedEvent.class)
     *     .onSignal((s, e) -> s.withTracking(e.trackingCode()))
     *     .toStepOnSignal("done")
     *     .build();
     * }</pre>
     *
     * <p>Example — signal wait with timeout:
     * <pre>{@code
     *   wf.waitForSignal("wait-shipped", "order.shipped", ShippedEvent.class)
     *     .onSignal((s, e) -> s.withTracking(e.trackingCode()))
     *     .toStepOnSignal("done")
     *     .timeoutAfter(Duration.ofHours(24))
     *     .onTimeout(s -> s.withTimedOut(true))
     *     .toStepOnTimeout("timed-out");
     * }</pre>
     *
     * @param <P> the expected signal payload type
     * @param stepId unique step identifier within this plan
     * @param signalName name of the signal this step waits for; must be unique within the plan
     * @param payloadType the runtime class of the expected signal payload
     * @return a {@link SignalWaitBuilder} to continue the chain
     */
    public <P> SignalWaitBuilder<P> waitForSignal(String stepId, String signalName, Class<P> payloadType) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        return new SignalWaitBuilder<>(stepId, signalName, payloadType);
    }

    /**
     * Adds a standalone durable-timer step that fires after a fixed delay relative to when the
     * engine enters the step.
     *
     * <p>The engine schedules a durable timer and suspends the workflow. When the timer fires,
     * the engine resumes and advances to {@code nextStepId}. Use {@link TimerStepBuilder#toStep}
     * to complete the step declaration.
     *
     * @param stepId unique step identifier within this plan
     * @param delay duration to wait; must be positive and non-null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder timer(String stepId, Duration delay) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        return new TimerStepBuilder(stepId, new TimerSpec.After(delay));
    }

    /**
     * Adds a standalone durable-timer step that fires at an absolute instant.
     *
     * <p>The engine schedules a durable timer and suspends the workflow. When the timer fires,
     * the engine resumes and advances to {@code nextStepId}. Use {@link TimerStepBuilder#toStep}
     * to complete the step declaration.
     *
     * @param stepId unique step identifier within this plan
     * @param fireAt the absolute UTC instant at which the timer should fire; must not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder timer(String stepId, Instant fireAt) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        return new TimerStepBuilder(stepId, new TimerSpec.At(fireAt));
    }

    /**
     * Adds a standalone durable-timer step whose fire time is computed from the workflow state.
     *
     * <p>The {@code resolver} function is registered in the callback registry under a fresh
     * {@link CallbackId} derived from {@code stepId}. When the engine enters this step it invokes
     * the resolver with the current state to compute the absolute fire instant. Use
     * {@link TimerStepBuilder#toStep} to complete the step declaration.
     *
     * @param stepId unique step identifier within this plan
     * @param resolver function that computes the fire instant from the current workflow state; must
     *     not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    @SuppressWarnings("unchecked")
    public TimerStepBuilder timerAt(String stepId, Function<S, Instant> resolver) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "timerResolver");
        timerResolvers.put(cbId.value(), (Function<Object, Instant>) (Function<?, ?>) resolver);
        return new TimerStepBuilder(stepId, new TimerSpec.FromState(cbId));
    }

    /**
     * Adds a scheduled-publish timer step that fires at an absolute instant.
     *
     * <p>This is DSL sugar over {@link #timer(String, Instant)}: both methods emit the same
     * {@link TimerNode} with a {@link TimerSpec.At} spec. The plan bytes are identical; the method
     * name communicates intent at the call-site ({@code publishAt} signals the step marks when
     * content becomes publicly visible).
     *
     * <p>Example:
     * <pre>{@code
     *   wf.publishAt("schedule-publish", Instant.parse("2026-12-31T23:59:59Z"))
     *     .toStep("published");
     * }</pre>
     *
     * @param stepId unique step identifier within this plan
     * @param fireAt the absolute UTC instant at which the timer should fire; must not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder publishAt(String stepId, Instant fireAt) {
        return timer(stepId, fireAt);
    }

    /**
     * Adds a scheduled-publish timer step whose fire time is computed from the workflow state.
     *
     * <p>This is DSL sugar over {@link #timerAt(String, Function)}: both methods emit the same
     * {@link TimerNode} with a {@link TimerSpec.FromState} spec. The {@code resolver} is registered
     * under a fresh {@link CallbackId} in the callback registry.
     *
     * <p>Example:
     * <pre>{@code
     *   wf.publishAt("schedule-publish", state -> state.scheduledPublishInstant())
     *     .toStep("published");
     * }</pre>
     *
     * @param stepId unique step identifier within this plan
     * @param resolver function that computes the fire instant from the current workflow state; must
     *     not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder publishAt(String stepId, Function<S, Instant> resolver) {
        return timerAt(stepId, resolver);
    }

    /**
     * Adds a scheduled-expiry timer step that fires at an absolute instant.
     *
     * <p>This is DSL sugar over {@link #timer(String, Instant)}: both methods emit the same
     * {@link TimerNode} with a {@link TimerSpec.At} spec. The plan bytes are identical; the method
     * name communicates intent at the call-site ({@code expireAt} signals the step marks when
     * content is no longer visible or valid).
     *
     * <p>Example:
     * <pre>{@code
     *   wf.expireAt("schedule-expire", Instant.parse("2026-12-31T23:59:59Z"))
     *     .toStep("expired");
     * }</pre>
     *
     * @param stepId unique step identifier within this plan
     * @param fireAt the absolute UTC instant at which the timer should fire; must not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder expireAt(String stepId, Instant fireAt) {
        return timer(stepId, fireAt);
    }

    /**
     * Adds a scheduled-expiry timer step whose fire time is computed from the workflow state.
     *
     * <p>This is DSL sugar over {@link #timerAt(String, Function)}: both methods emit the same
     * {@link TimerNode} with a {@link TimerSpec.FromState} spec. The {@code resolver} is registered
     * under a fresh {@link CallbackId} in the callback registry.
     *
     * <p>Example:
     * <pre>{@code
     *   wf.expireAt("schedule-expire", state -> state.expiresAtInstant())
     *     .toStep("expired");
     * }</pre>
     *
     * @param stepId unique step identifier within this plan
     * @param resolver function that computes the fire instant from the current workflow state; must
     *     not be null
     * @return a {@link TimerStepBuilder} to supply the next step id
     */
    public TimerStepBuilder expireAt(String stepId, Function<S, Instant> resolver) {
        return timerAt(stepId, resolver);
    }

    /**
     * Adds a decision step that dynamically resolves the next step from the current state.
     *
     * @param stepId unique step identifier within this plan
     * @param nextStepResolver function that inspects the current state and returns the next step id
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> decide(String stepId, Function<S, String> nextStepResolver) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "resolver");
        decisionResolvers.put(cbId.value(), (Function<Object, String>) (Function<?, ?>) nextStepResolver);
        nodes.add(new DecisionNode(stepId, cbId));
        return this;
    }

    /**
     * Variant of {@link #decide(String, Function)} that uses a caller-supplied {@link CallbackId}
     * instead of allocating the conventional {@code "{stepId}.resolver"} id.
     *
     * <p>The file-defined workflow compiler in {@code vertique-workflow-definition} uses this
     * overload to embed a stable expression/route fingerprint into the callback id so that semantic
     * changes to route expressions propagate to the plan hash (FR-WF-DEF-055, AC #8 of PRD-WF-003).
     *
     * <p>Code-first definitions should continue to use {@link #decide(String, Function)}; this
     * overload is for tools that own the callback-id namespace (e.g., the document compiler).
     *
     * @param stepId unique step identifier within this plan
     * @param nextStepResolver function that inspects the current state and returns the next step id
     * @param explicitCallbackId the callback id to register the resolver under and embed in the
     *     emitted {@link DecisionNode}; must not be null; its value participates in the plan hash
     * @return this builder for chaining
     * @throws NullPointerException if {@code explicitCallbackId} is null
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> decideWithCallbackId(
            String stepId, Function<S, String> nextStepResolver, CallbackId explicitCallbackId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        Objects.requireNonNull(explicitCallbackId, "explicitCallbackId");
        decisionResolvers.put(explicitCallbackId.value(), (Function<Object, String>) (Function<?, ?>) nextStepResolver);
        nodes.add(new DecisionNode(stepId, explicitCallbackId));
        return this;
    }

    /**
     * Adds a terminal complete step that transitions the workflow to {@code COMPLETED}.
     *
     * @param stepId unique step identifier within this plan
     * @return this builder for chaining
     */
    public WorkflowBuilder<S> complete(String stepId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        nodes.add(new CompleteNode(stepId));
        return this;
    }

    /**
     * Adds a terminal fail step that transitions the workflow to {@code FAILED} and triggers
     * compensation.
     *
     * @param stepId unique step identifier within this plan
     * @param errorType application-defined error category persisted on the instance
     * @param messageFactory function that produces a human-readable error message from the current
     *     state
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> fail(String stepId, String errorType, Function<S, String> messageFactory) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "message");
        failMessageFactories.put(cbId.value(), (Function<Object, String>) (Function<?, ?>) messageFactory);
        nodes.add(new FailNode(stepId, errorType, cbId));
        return this;
    }

    /**
     * Adds a compensation step that records a compensating service call for a forward dispatch
     * step.
     *
     * @param stepId unique step identifier within this plan
     * @param forwardStepId step id of the {@link dev.vertique.workflow.plan.ServiceDispatchNode}
     *     this compensation is paired with
     * @param targetId service target id for the compensation call
     * @param payloadFactory function that derives the compensation request payload from the current
     *     state
     * @return this builder for chaining
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> compensate(
            String stepId, String forwardStepId, String targetId, Function<S, Object> payloadFactory) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        CallbackId cbId = callbackId(stepId, "payload");
        payloadFactories.put(cbId.value(), (Function<Object, Object>) (Function<?, ?>) payloadFactory);
        nodes.add(new CompensationNode(stepId, forwardStepId, targetId, cbId));
        return this;
    }

    /**
     * Registers a subject-resolver function that derives the workflow's
     * {@link WorkflowSubjectRef} from the initial state at instance-creation time.
     *
     * <p>The resolver is invoked by the engine during {@code doCreateInstance} when the caller's
     * {@link dev.vertique.workflow.ops.StartCommand} does not supply a subject ref directly
     * (caller-supplied subject ref always takes precedence). If the resolver returns {@code null},
     * the engine fails instance creation with a
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionException}.
     *
     * <p>At most one subject resolver may be registered per definition. A second call to
     * {@code subject(...)} on the same builder throws {@link IllegalStateException} — duplicate
     * registration is almost always a bug (e.g., a {@code WorkflowContributor} accidentally
     * calling the method twice during composition).
     *
     * <p>Example:
     * <pre>{@code
     *   wf.subject(state -> new WorkflowSubjectRef("Article", state.articleId(), state.version()))
     *      .initialStep("start")
     *      ...
     * }</pre>
     *
     * @param resolver function that maps the initial workflow state to a {@link WorkflowSubjectRef};
     *     must not be null; must not return null (a null return is a contract violation that the
     *     engine treats as a {@link dev.vertique.workflow.exception.WorkflowDefinitionException})
     * @return this builder for chaining
     * @throws NullPointerException if {@code resolver} is null
     * @throws IllegalStateException if a subject resolver was already registered on this builder
     */
    @SuppressWarnings("unchecked")
    public WorkflowBuilder<S> subject(Function<S, WorkflowSubjectRef> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (subjectResolverCallbackId != null) {
            throw new IllegalStateException("subject(...) has already been registered for this WorkflowBuilder; "
                    + "duplicate registration is almost always a bug");
        }
        subjectResolvers.put(
                SUBJECT_RESOLVER_ID.value(), (Function<Object, WorkflowSubjectRef>) (Function<?, ?>) resolver);
        subjectResolverCallbackId = SUBJECT_RESOLVER_ID;
        return this;
    }

    // --- PRD-WF-002 fork/join entry points ---

    /**
     * Begins a fan-out fork at the given step id.
     *
     * <p>The returned {@link ForkScope} captures the branch declarations and retry policy. Calling
     * {@link ForkScope#join(String)} terminates the fork and appends the resulting
     * {@link dev.vertique.workflow.plan.ForkNode} to the plan, returning this builder for further
     * chaining.
     *
     * <p>Branch <em>bodies</em> (the actual nodes referenced by each {@code BranchStart}) are
     * authored using the existing builder methods ({@link #dispatch}, {@link #waitFor},
     * {@link #complete}, etc.) — branch nodes are flattened into the canonical
     * {@link dev.vertique.workflow.plan.WorkflowPlan#nodes()} list and identified at runtime by
     * the fork node's {@link dev.vertique.workflow.plan.BranchStart#startStepId()}.
     *
     * @param stepId unique step id for this fork
     * @return a {@link ForkScope} for declaring branches
     */
    public ForkScope<S> fork(String stepId) {
        requireInit();
        return new ForkScope<>(this, stepId);
    }

    /**
     * Begins configuring a fan-in join at the given step id.
     *
     * <p>The returned {@link JoinScope} captures the join policy, reducer, and routes. Calling
     * {@link JoinScope#endJoin()} appends the resulting
     * {@link dev.vertique.workflow.plan.JoinNode} to the plan and returns this builder.
     *
     * @param stepId unique step id for this join (matches the {@code joinStepId} on the matching
     *     {@link dev.vertique.workflow.plan.ForkNode})
     * @return a {@link JoinScope} for configuring the join policy + routes
     */
    public JoinScope<S> join(String stepId) {
        requireInit();
        return new JoinScope<>(this, stepId);
    }

    // --- Internal helpers used by ForkScope / JoinScope ---

    /**
     * Appends a fork node to the builder's flat node list. Called by {@link ForkScope#join(String)}.
     *
     * @param fork the fork node to append
     */
    void appendForkNode(ForkNode fork) {
        stepCalled = true;
        requireUniqueStepId(fork.stepId());
        nodes.add(fork);
    }

    /**
     * Appends a join node to the builder's flat node list and registers its reducer callback.
     * Called by {@link JoinScope#endJoin()}.
     *
     * @param join the join node to append
     * @param reducer the reducer function to register under {@code join.branchResultReducerCallbackId()}
     */
    @SuppressWarnings("unchecked")
    void appendJoinNode(JoinNode join, BiFunction<S, Map<String, BranchResult>, S> reducer) {
        stepCalled = true;
        requireUniqueStepId(join.stepId());
        branchResultReducers.put(
                join.branchResultReducerCallbackId().value(),
                (BiFunction<Object, Map<String, BranchResult>, Object>) (BiFunction<?, ?, ?>) reducer);
        nodes.add(join);
    }

    /**
     * Test-only accessor for the {@code branchResultReducers} map.
     *
     * @return an unmodifiable view of the registered reducers, keyed by callback id value
     */
    Map<String, BiFunction<Object, Map<String, BranchResult>, Object>> branchResultReducerMap() {
        return Map.copyOf(branchResultReducers);
    }

    /**
     * Begins a human-task step with the given step id.
     *
     * <p>The returned {@link TaskBuilder} must have at least one {@code .decision(...)} call and
     * an {@code .assignToX(...)} call before {@link TaskBuilder#build()} is invoked. An optional
     * due-date branch can be added via {@code .dueIn(...)}, {@code .dueAt(...)}, or
     * {@code .dueFromState(...)} — if any one is called, both {@code .onDue(...)} and
     * {@code .toStepOnDue(...)} must also be called.
     *
     * <p>Example — role assignment with two decisions:
     * <pre>{@code
     *   wf.task("review")
     *     .assignToRole("compliance")
     *     .decision("approve", ApprovalPayload.class).onDecision((s, p) -> s).toStep("ship")
     *     .decision("reject", RejectionPayload.class).onDecision((s, p) -> s).toStep("notify")
     *     .build();
     * }</pre>
     *
     * @param stepId unique step identifier within this plan
     * @return a {@link TaskBuilder} to continue the chain
     */
    public TaskBuilder task(String stepId) {
        requireInit();
        stepCalled = true;
        requireUniqueStepId(stepId);
        return new TaskBuilder(stepId);
    }

    // --- Build ---

    /**
     * Builds a {@link WorkflowPlan} omitting {@code stateTypeName} from the plan hash.
     *
     * <p><strong>Package-private.</strong> External callers must use
     * {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry#register(WorkflowDefinition)},
     * which internally calls {@link #build(String, long, String)} with the correct
     * {@code stateTypeName} derived from {@link WorkflowDefinition#stateType()}. Using this
     * two-arg overload directly produces a plan whose {@code planHash} omits the state type, which
     * defeats the drift-detection guarantee introduced in cycle 1. This overload exists only for
     * DSL-level unit tests that do not exercise the registry path.
     *
     * @param definitionId the workflow definition id
     * @param definitionVersion the workflow definition version
     * @return the constructed plan; never null
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if {@code init()} was never
     *     called
     */
    WorkflowPlan build(String definitionId, long definitionVersion) {
        if (!initCalled) {
            throw new WorkflowDefinitionException(
                    "definition '" + definitionId + "' v" + definitionVersion + " did not call wf.init(...)");
        }
        // Default initial step to the first node if not explicitly set
        String effectiveInitialStep = initialStepIdInternal;
        if (effectiveInitialStep == null && !nodes.isEmpty()) {
            effectiveInitialStep = nodes.get(0).stepId();
        }

        validateStepReferences();

        String stateTypeName = ""; // stateType is not known by builder itself; resolved by registry
        // stateTypeName is passed from registry when it calls build; we store it during init
        // Actually builder doesn't directly know stateType — registry provides it separately
        // The plan stateTypeName comes from WorkflowDefinition.stateType().getName() in registry

        String planHash = computePlanHash(
                definitionId, definitionVersion, stateTypeName, effectiveInitialStep, nodes, subjectResolverCallbackId);
        return new WorkflowPlan(
                definitionId,
                definitionVersion,
                planHash,
                stateTypeName,
                effectiveInitialStep,
                List.copyOf(nodes),
                subjectResolverCallbackId);
    }

    /**
     * Builds a {@link WorkflowPlan} with the state type name provided by the registry.
     *
     * <p>This overload is called by {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry}
     * after it knows the state type from the workflow definition.
     *
     * @param definitionId the workflow definition id
     * @param definitionVersion the workflow definition version
     * @param stateTypeName the fully-qualified name of the workflow state type
     * @return the constructed plan; never null
     * @throws WorkflowDefinitionException if {@code init()} was never called
     */
    public WorkflowPlan build(String definitionId, long definitionVersion, String stateTypeName) {
        if (!initCalled) {
            throw new WorkflowDefinitionException(
                    "definition '" + definitionId + "' v" + definitionVersion + " did not call wf.init(...)");
        }
        String effectiveInitialStep = initialStepIdInternal;
        if (effectiveInitialStep == null && !nodes.isEmpty()) {
            effectiveInitialStep = nodes.get(0).stepId();
        }

        validateStepReferences();

        String planHash = computePlanHash(
                definitionId, definitionVersion, stateTypeName, effectiveInitialStep, nodes, subjectResolverCallbackId);
        return new WorkflowPlan(
                definitionId,
                definitionVersion,
                planHash,
                stateTypeName,
                effectiveInitialStep,
                List.copyOf(nodes),
                subjectResolverCallbackId);
    }

    /**
     * Returns the start payload class declared via {@link #init(Class, Function)}.
     *
     * @return the start payload class; may be null if {@code init()} was not called
     */
    public Class<?> startPayloadType() {
        return startPayloadTypeInternal;
    }

    /**
     * Returns the raw-typed initial-state function declared via {@link #init(Class, Function)}.
     *
     * @return the initial-state function; may be null if {@code init()} was not called
     */
    @SuppressWarnings("unchecked")
    public Function<Object, Object> initialState() {
        return (Function<Object, Object>) initialStateFn;
    }

    /**
     * Returns the signal payload types map collected from {@code waitFor} and {@code waitForSignal}
     * calls.
     *
     * @return map from signal name to declared payload class; never null
     */
    public Map<String, Class<?>> signalPayloadTypes() {
        return Map.copyOf(signalPayloadTypes);
    }

    /**
     * Returns an unmodifiable snapshot of the payload-factory map keyed by {@link CallbackId}
     * value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to payload factory; never null
     */
    public Map<String, Function<Object, Object>> payloadFactoryMap() {
        return Map.copyOf(payloadFactories);
    }

    /**
     * Returns an unmodifiable snapshot of the state-updater map keyed by {@link CallbackId} value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to state updater; never null
     */
    public Map<String, BiFunction<Object, Object, Object>> stateUpdaterMap() {
        return Map.copyOf(stateUpdaters);
    }

    /**
     * Returns an unmodifiable snapshot of the decision-resolver map keyed by {@link CallbackId}
     * value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to decision resolver; never null
     */
    public Map<String, Function<Object, String>> decisionResolverMap() {
        return Map.copyOf(decisionResolvers);
    }

    /**
     * Returns an unmodifiable snapshot of the fail-message-factory map keyed by {@link CallbackId}
     * value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to fail message factory; never null
     */
    public Map<String, Function<Object, String>> failMessageFactoryMap() {
        return Map.copyOf(failMessageFactories);
    }

    /**
     * Returns an unmodifiable snapshot of the timer-resolver map keyed by {@link CallbackId} value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to timer resolver; never null
     */
    public Map<String, Function<Object, Instant>> timerResolverMap() {
        return Map.copyOf(timerResolvers);
    }

    /**
     * Returns an unmodifiable snapshot of the state-mutator map keyed by {@link CallbackId} value.
     *
     * <p>Used by the {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} to merge
     * per-definition callbacks into the global registry.
     *
     * @return map from callback id string to state mutator; never null
     */
    public Map<String, Function<Object, Object>> stateMutatorMap() {
        return Map.copyOf(stateMutators);
    }

    /**
     * Returns the callback registry populated by this builder.
     *
     * @return the callback registry holding all registered functions
     */
    public WorkflowCallbackRegistry callbackRegistry() {
        return buildCallbackRegistry();
    }

    /**
     * Builds and returns an {@link InternalCallbackRegistry} from the accumulated state.
     *
     * <p>This method is accessible to the {@code registry} package for merging into the global
     * aggregated registry.
     *
     * @return an immutable snapshot of all registered callbacks
     */
    InternalCallbackRegistry buildCallbackRegistry() {
        return new InternalCallbackRegistry(
                Map.copyOf(payloadFactories),
                Map.copyOf(stateUpdaters),
                Map.copyOf(decisionResolvers),
                Map.copyOf(failMessageFactories),
                Map.copyOf(timerResolvers),
                Map.copyOf(stateMutators),
                Map.copyOf(decisionApplicators),
                Map.copyOf(taskAssignmentResolvers),
                Map.copyOf(subjectResolvers),
                Map.copyOf(branchResultReducers));
    }

    // --- Fluent inner classes ---

    /**
     * Intermediate builder for a standalone timer step. Created by {@link #timer(String, Duration)},
     * {@link #timer(String, Instant)}, or {@link #timerAt(String, Function)}.
     *
     * <p>The timer spec is captured at creation time; call {@link #toStep} to finalise the node
     * and return to the enclosing {@link WorkflowBuilder}.
     */
    public final class TimerStepBuilder {

        private final String stepId;
        private final TimerSpec spec;

        /**
         * Constructs a {@code TimerStepBuilder} for the given step id and spec.
         *
         * @param stepId the step id of this timer node
         * @param spec how the timer's fire time is determined
         */
        private TimerStepBuilder(String stepId, TimerSpec spec) {
            this.stepId = stepId;
            this.spec = spec;
        }

        /**
         * Finalises the timer step by setting its successor and emitting a {@link TimerNode} into
         * the enclosing builder.
         *
         * @param nextStepId the step id to advance to after the timer fires; must refer to a node
         *     declared in the same plan
         * @return the enclosing {@link WorkflowBuilder} for continued chaining
         */
        public WorkflowBuilder<S> toStep(String nextStepId) {
            nodes.add(new TimerNode(stepId, nextStepId, spec));
            return WorkflowBuilder.this;
        }
    }

    /**
     * Intermediate builder for a signal-wait step that captures the signal applicator before the
     * next-step is set. Created by {@link #waitForSignal(String, String, Class)}.
     *
     * @param <P> the expected signal payload type
     */
    public final class SignalWaitBuilder<P> {

        private final String stepId;
        private final String signalName;
        private final Class<P> payloadType;

        /**
         * Constructs a {@code SignalWaitBuilder}.
         *
         * @param stepId the step id of this signal-wait node
         * @param signalName the signal name to wait for
         * @param payloadType the runtime class of the expected signal payload
         */
        private SignalWaitBuilder(String stepId, String signalName, Class<P> payloadType) {
            this.stepId = stepId;
            this.signalName = signalName;
            this.payloadType = payloadType;
        }

        /**
         * Registers the signal applicator that merges the incoming payload into the current state.
         *
         * <p>The applicator is stored in the {@code stateUpdaters} map (Option A) so that the
         * engine's signal-arrival dispatch logic works identically for both the deprecated
         * {@link #waitFor} and the new {@link #waitForSignal} chains.
         *
         * @param applicator function that merges the incoming signal payload into the current state
         * @return a {@link SignalWaitTerminator} to supply the next step id
         */
        @SuppressWarnings("unchecked")
        public SignalWaitTerminator onSignal(BiFunction<S, P, S> applicator) {
            CallbackId cbId = callbackId(stepId, "updater");
            stateUpdaters.put(cbId.value(), (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) applicator);
            signalPayloadTypes.put(signalName, payloadType);
            return new SignalWaitTerminator(stepId, signalName, payloadType, cbId);
        }
    }

    /**
     * Intermediate builder that has captured the signal applicator and next-step id. Allows
     * terminating without a timeout ({@link #build()}) or branching into a timeout specification
     * ({@link #timeoutAfter}, {@link #timeoutAt}, {@link #timeoutFromState}).
     */
    public final class SignalWaitTerminator {

        private final String stepId;
        private final String signalName;
        private final Class<?> payloadType;
        private final CallbackId stateUpdaterCbId;

        /**
         * Constructs a {@code SignalWaitTerminator}.
         *
         * @param stepId the step id of the signal-wait node
         * @param signalName the signal name
         * @param payloadType the payload class
         * @param stateUpdaterCbId callback id for the signal applicator stored in stateUpdaters
         */
        private SignalWaitTerminator(
                String stepId, String signalName, Class<?> payloadType, CallbackId stateUpdaterCbId) {
            this.stepId = stepId;
            this.signalName = signalName;
            this.payloadType = payloadType;
            this.stateUpdaterCbId = stateUpdaterCbId;
        }

        /**
         * Sets the step id to advance to when the signal arrives and captures the signal-wait
         * configuration so far.
         *
         * @param nextStepId step to advance to after the signal is processed
         * @return a {@link SignalWaitWithNextStep} to either finalise or add a timeout branch
         */
        public SignalWaitWithNextStep toStepOnSignal(String nextStepId) {
            return new SignalWaitWithNextStep(stepId, signalName, payloadType, stateUpdaterCbId, nextStepId);
        }
    }

    /**
     * Intermediate builder that has the signal-on path fully specified. Either call
     * {@link #build()} to emit a {@link WaitSignalNode} with no timeout, or call one of the
     * timeout methods to add a timeout branch.
     */
    public final class SignalWaitWithNextStep {

        private final String stepId;
        private final String signalName;
        private final Class<?> payloadType;
        private final CallbackId stateUpdaterCbId;
        private final String nextStepId;

        /**
         * Constructs a {@code SignalWaitWithNextStep}.
         *
         * @param stepId the step id of the signal-wait node
         * @param signalName the signal name
         * @param payloadType the payload class
         * @param stateUpdaterCbId callback id for the signal applicator
         * @param nextStepId step to advance to after the signal is processed
         */
        private SignalWaitWithNextStep(
                String stepId,
                String signalName,
                Class<?> payloadType,
                CallbackId stateUpdaterCbId,
                String nextStepId) {
            this.stepId = stepId;
            this.signalName = signalName;
            this.payloadType = payloadType;
            this.stateUpdaterCbId = stateUpdaterCbId;
            this.nextStepId = nextStepId;
        }

        /**
         * Emits a {@link WaitSignalNode} with no timeout branch and returns to the enclosing
         * {@link WorkflowBuilder}.
         *
         * @return the enclosing {@link WorkflowBuilder} for continued chaining
         */
        public WorkflowBuilder<S> build() {
            nodes.add(
                    new WaitSignalNode(stepId, signalName, payloadType.getName(), stateUpdaterCbId, nextStepId, null));
            return WorkflowBuilder.this;
        }

        /**
         * Adds a relative-delay timeout branch. The workflow takes the timeout path if the named
         * signal does not arrive within {@code delay} from when the engine enters this step.
         *
         * @param delay duration to wait before timing out; must be positive and non-null
         * @return a {@link SignalWaitWithTimeout} to optionally set a timeout state mutator and the
         *     timeout successor step
         */
        public SignalWaitWithTimeout timeoutAfter(Duration delay) {
            return new SignalWaitWithTimeout(
                    stepId, signalName, payloadType, stateUpdaterCbId, nextStepId, new TimerSpec.After(delay));
        }

        /**
         * Adds an absolute-instant timeout branch.
         *
         * @param fireAt the absolute UTC instant at which the timeout fires; must not be null
         * @return a {@link SignalWaitWithTimeout} to optionally set a timeout state mutator and the
         *     timeout successor step
         */
        public SignalWaitWithTimeout timeoutAt(Instant fireAt) {
            return new SignalWaitWithTimeout(
                    stepId, signalName, payloadType, stateUpdaterCbId, nextStepId, new TimerSpec.At(fireAt));
        }

        /**
         * Adds a state-derived timeout branch. The fire instant is computed at step-execution time
         * by invoking {@code resolver} with the current workflow state.
         *
         * @param resolver function that computes the fire instant from the current workflow state;
         *     must not be null
         * @return a {@link SignalWaitWithTimeout} to optionally set a timeout state mutator and the
         *     timeout successor step
         */
        @SuppressWarnings("unchecked")
        public SignalWaitWithTimeout timeoutFromState(Function<S, Instant> resolver) {
            CallbackId cbId = callbackId(stepId, "timeoutResolver");
            timerResolvers.put(cbId.value(), (Function<Object, Instant>) (Function<?, ?>) resolver);
            return new SignalWaitWithTimeout(
                    stepId, signalName, payloadType, stateUpdaterCbId, nextStepId, new TimerSpec.FromState(cbId));
        }
    }

    /**
     * Terminal builder for a signal-wait step that has a timeout branch. Call
     * {@link #onTimeout(Function)} (optional) to register a state mutator for the timeout path,
     * then call {@link #toStepOnTimeout(String)} to emit the node.
     */
    public final class SignalWaitWithTimeout {

        private final String stepId;
        private final String signalName;
        private final Class<?> payloadType;
        private final CallbackId stateUpdaterCbId;
        private final String nextStepId;
        private final TimerSpec timeoutSpec;

        /**
         * Constructs a {@code SignalWaitWithTimeout}.
         *
         * @param stepId the step id of the signal-wait node
         * @param signalName the signal name
         * @param payloadType the payload class
         * @param stateUpdaterCbId callback id for the signal applicator
         * @param nextStepId step to advance to after the signal is processed
         * @param timeoutSpec how the timeout deadline is determined
         */
        private SignalWaitWithTimeout(
                String stepId,
                String signalName,
                Class<?> payloadType,
                CallbackId stateUpdaterCbId,
                String nextStepId,
                TimerSpec timeoutSpec) {
            this.stepId = stepId;
            this.signalName = signalName;
            this.payloadType = payloadType;
            this.stateUpdaterCbId = stateUpdaterCbId;
            this.nextStepId = nextStepId;
            this.timeoutSpec = timeoutSpec;
        }

        /**
         * Registers a state mutator to apply when the timeout path is taken, then returns this
         * builder for chaining.
         *
         * <p>If this method is not called, an identity-function mutator ({@code s -> s}) is
         * registered automatically when {@link #toStepOnTimeout(String)} is called.
         *
         * @param mutator function that transforms the workflow state when the timeout path is taken
         * @return a {@link SignalWaitWithTimeoutMutator} to set the timeout successor step
         */
        @SuppressWarnings("unchecked")
        public SignalWaitWithTimeoutMutator onTimeout(Function<S, S> mutator) {
            CallbackId cbId = callbackId(stepId, "timeoutMutator");
            stateMutators.put(cbId.value(), (Function<Object, Object>) (Function<?, ?>) mutator);
            return new SignalWaitWithTimeoutMutator(
                    stepId, signalName, payloadType, stateUpdaterCbId, nextStepId, timeoutSpec, cbId);
        }

        /**
         * Finalises the signal-wait node without an explicit timeout mutator, registering an
         * identity-function mutator automatically, and returns to the enclosing
         * {@link WorkflowBuilder}.
         *
         * @param timeoutNextStepId step to advance to when the timeout path is taken; must refer to
         *     a node declared in the same plan
         * @return the enclosing {@link WorkflowBuilder} for continued chaining
         */
        public WorkflowBuilder<S> toStepOnTimeout(String timeoutNextStepId) {
            // No explicit onTimeout — register identity mutator
            CallbackId cbId = callbackId(stepId, "timeoutMutator");
            stateMutators.put(cbId.value(), s -> s);
            WaitSignalNode.TimeoutBranch branch =
                    new WaitSignalNode.TimeoutBranch(timeoutSpec, cbId, timeoutNextStepId);
            nodes.add(new WaitSignalNode(
                    stepId, signalName, payloadType.getName(), stateUpdaterCbId, nextStepId, branch));
            return WorkflowBuilder.this;
        }
    }

    /**
     * Final builder for a signal-wait timeout branch that has already captured the timeout mutator.
     */
    public final class SignalWaitWithTimeoutMutator {

        private final String stepId;
        private final String signalName;
        private final Class<?> payloadType;
        private final CallbackId stateUpdaterCbId;
        private final String nextStepId;
        private final TimerSpec timeoutSpec;
        private final CallbackId timeoutMutatorCbId;

        /**
         * Constructs a {@code SignalWaitWithTimeoutMutator}.
         *
         * @param stepId the step id of the signal-wait node
         * @param signalName the signal name
         * @param payloadType the payload class
         * @param stateUpdaterCbId callback id for the signal applicator
         * @param nextStepId step to advance to after the signal is processed
         * @param timeoutSpec how the timeout deadline is determined
         * @param timeoutMutatorCbId callback id for the timeout state mutator
         */
        private SignalWaitWithTimeoutMutator(
                String stepId,
                String signalName,
                Class<?> payloadType,
                CallbackId stateUpdaterCbId,
                String nextStepId,
                TimerSpec timeoutSpec,
                CallbackId timeoutMutatorCbId) {
            this.stepId = stepId;
            this.signalName = signalName;
            this.payloadType = payloadType;
            this.stateUpdaterCbId = stateUpdaterCbId;
            this.nextStepId = nextStepId;
            this.timeoutSpec = timeoutSpec;
            this.timeoutMutatorCbId = timeoutMutatorCbId;
        }

        /**
         * Finalises the signal-wait node with the timeout branch and returns to the enclosing
         * {@link WorkflowBuilder}.
         *
         * @param timeoutNextStepId step to advance to when the timeout path is taken; must refer to
         *     a node declared in the same plan
         * @return the enclosing {@link WorkflowBuilder} for continued chaining
         */
        public WorkflowBuilder<S> toStepOnTimeout(String timeoutNextStepId) {
            WaitSignalNode.TimeoutBranch branch =
                    new WaitSignalNode.TimeoutBranch(timeoutSpec, timeoutMutatorCbId, timeoutNextStepId);
            nodes.add(new WaitSignalNode(
                    stepId, signalName, payloadType.getName(), stateUpdaterCbId, nextStepId, branch));
            return WorkflowBuilder.this;
        }
    }

    // --- Task DSL inner classes ---

    /**
     * Fluent builder for a {@link HumanTaskNode} step.
     *
     * <p>Accumulates assignment, decisions, an optional due-date branch, and an optional reminder
     * spec. Call {@link #build()} to emit the {@link HumanTaskNode} into the enclosing
     * {@link WorkflowBuilder} and return to it for continued chaining.
     *
     * <p>Invariants enforced at {@link #build()}:
     * <ul>
     *   <li>Assignment must be set via one of the {@code assignTo*()} methods.</li>
     *   <li>At least one decision must be added via {@code decision(...)}.</li>
     *   <li>If any due-date field ({@code dueIn}, {@code dueAt}, {@code dueFromState},
     *       {@code onDue}, {@code toStepOnDue}) is set, all three must be set together.</li>
     *   <li>At most one of {@link #reminderAt} or {@link #reminderEvery} may be called per task.
     *       Both null is valid (no reminders).</li>
     * </ul>
     */
    public final class TaskBuilder {

        private final String stepId;

        // Assignment
        private HumanTaskNode.AssignmentSpec assignmentSpec;

        // Decisions (declaration order preserved)
        private final List<HumanTaskNode.TaskDecision> decisions = new ArrayList<>();

        // Due-date branch (all-or-nothing triplet)
        private TimerSpec dueDateSpec;
        private CallbackId onDueMutatorCbId;
        private String dueNextStepId;

        // Null means no reminders (default).
        private ReminderSpec reminders;

        // False means no version-stability enforcement (default).
        private boolean requireVersionStability;

        /**
         * Constructs a {@code TaskBuilder} for the given step id.
         *
         * @param stepId the step id of this human-task node
         */
        private TaskBuilder(String stepId) {
            this.stepId = stepId;
        }

        // --- Assignment methods ---

        /**
         * Assigns the task to a specific user (literal).
         *
         * @param userId the user id; must not be null or blank
         * @return this builder for chaining
         */
        public TaskBuilder assignToUser(String userId) {
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.User(userId);
            return this;
        }

        /**
         * Assigns the task to a user resolved from the workflow state at task-creation time.
         *
         * @param resolver function that returns the user id from the current state; must not be null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskBuilder assignToUser(Function<S, String> resolver) {
            CallbackId cbId = callbackId(stepId, "assignmentResolver");
            taskAssignmentResolvers.put(cbId.value(), (Function<Object, TaskAssignment>)
                    (Function<?, ?>) resolver.andThen(TaskAssignment.User::new));
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.UserFromState(cbId);
            return this;
        }

        /**
         * Assigns the task to a role (literal).
         *
         * @param roleId the role id; must not be null or blank
         * @return this builder for chaining
         */
        public TaskBuilder assignToRole(String roleId) {
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.Role(roleId);
            return this;
        }

        /**
         * Assigns the task to a role resolved from the workflow state at task-creation time.
         *
         * @param resolver function that returns the role id from the current state; must not be null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskBuilder assignToRole(Function<S, String> resolver) {
            CallbackId cbId = callbackId(stepId, "assignmentResolver");
            taskAssignmentResolvers.put(cbId.value(), (Function<Object, TaskAssignment>)
                    (Function<?, ?>) resolver.andThen(TaskAssignment.Role::new));
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.RoleFromState(cbId);
            return this;
        }

        /**
         * Assigns the task to a named queue (literal).
         *
         * @param queueName the queue name; must not be null or blank
         * @return this builder for chaining
         */
        public TaskBuilder assignToQueue(String queueName) {
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.Queue(queueName);
            return this;
        }

        /**
         * Assigns the task to a queue resolved from the workflow state at task-creation time.
         *
         * @param resolver function that returns the queue name from the current state; must not be
         *     null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskBuilder assignToQueue(Function<S, String> resolver) {
            CallbackId cbId = callbackId(stepId, "assignmentResolver");
            taskAssignmentResolvers.put(cbId.value(), (Function<Object, TaskAssignment>)
                    (Function<?, ?>) resolver.andThen(TaskAssignment.Queue::new));
            this.assignmentSpec = new HumanTaskNode.AssignmentSpec.QueueFromState(cbId);
            return this;
        }

        // --- Decision method ---

        /**
         * Begins a decision branch for the given name and payload type.
         *
         * <p>The returned {@link TaskDecisionBuilder} must have {@link TaskDecisionBuilder#onDecision}
         * called before {@link TaskDecisionBuilder#toStep}, which returns control to this
         * {@code TaskBuilder} for additional decisions or finalization.
         *
         * @param <P> the decision payload type
         * @param name the decision name (e.g., {@code "approve"}); must not be blank
         * @param payloadType the runtime class of the decision payload
         * @return a {@link TaskDecisionBuilder} to supply the applicator and next step
         */
        public <P> TaskDecisionBuilder<P> decision(String name, Class<P> payloadType) {
            return new TaskDecisionBuilder<>(this, name, payloadType);
        }

        // --- Due-date methods ---

        /**
         * Sets the task due-date as a fixed delay from task-creation time.
         *
         * @param delay the duration until the task expires; must be positive and non-null
         * @return this builder for chaining
         */
        public TaskBuilder dueIn(Duration delay) {
            this.dueDateSpec = new TimerSpec.After(delay);
            return this;
        }

        /**
         * Sets the task due-date as an absolute instant.
         *
         * @param fireAt the absolute UTC instant at which the task expires; must not be null
         * @return this builder for chaining
         */
        public TaskBuilder dueAt(Instant fireAt) {
            this.dueDateSpec = new TimerSpec.At(fireAt);
            return this;
        }

        /**
         * Sets the task due-date as an instant resolved from the workflow state at task-creation
         * time.
         *
         * @param resolver function that computes the expiry instant from the current workflow state;
         *     must not be null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskBuilder dueFromState(Function<S, Instant> resolver) {
            CallbackId cbId = callbackId(stepId, "dueDateResolver");
            timerResolvers.put(cbId.value(), (Function<Object, Instant>) (Function<?, ?>) resolver);
            this.dueDateSpec = new TimerSpec.FromState(cbId);
            return this;
        }

        /**
         * Registers the state mutator to apply when the due-date fires.
         *
         * @param mutator function that transforms the workflow state when the task expires;
         *     must not be null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskBuilder onDue(Function<S, S> mutator) {
            CallbackId cbId = callbackId(stepId, "dueMutator");
            stateMutators.put(cbId.value(), (Function<Object, Object>) (Function<?, ?>) mutator);
            this.onDueMutatorCbId = cbId;
            return this;
        }

        /**
         * Sets the step to advance to when the due-date fires.
         *
         * @param dueNextStepId the step id; must refer to a node declared in the same plan
         * @return this builder for chaining
         */
        public TaskBuilder toStepOnDue(String dueNextStepId) {
            this.dueNextStepId = dueNextStepId;
            return this;
        }

        // --- Reminder methods ---

        /**
         * Configure one-shot reminders to fire at the given offsets after task creation. Each
         * offset produces one reminder event and one history entry. Reminders are event-only side
         * effects: they do not advance workflow state.
         *
         * <p>At most one of {@link #reminderAt} or {@link #reminderEvery} may be called per task.
         *
         * @param first the first offset (positive, non-zero)
         * @param rest  additional offsets (each positive, non-zero)
         * @return this builder for chaining
         * @throws IllegalArgumentException if any offset is zero or negative
         * @throws IllegalStateException    if a reminder spec was already configured on this task
         */
        public TaskBuilder reminderAt(Duration first, Duration... rest) {
            if (this.reminders != null) {
                throw new IllegalStateException("reminders already configured");
            }
            List<Duration> offsets = new ArrayList<>();
            offsets.add(first);
            for (Duration d : rest) {
                offsets.add(d);
            }
            this.reminders = new ReminderSpec.OneShotOffsets(offsets);
            return this;
        }

        /**
         * Configure a recurring reminder firing every {@code interval} until the task closes or
         * {@code maxFires} is reached.
         *
         * <p>Use the no-bound overload {@link #reminderEvery(Duration)} for unbounded recurring
         * reminders.
         *
         * @param interval the firing interval (positive, non-zero)
         * @param maxFires maximum number of fires; must be &ge; 1
         * @return this builder for chaining
         * @throws IllegalArgumentException if interval is zero or negative or maxFires &lt; 1
         * @throws IllegalStateException    if a reminder spec was already configured on this task
         */
        public TaskBuilder reminderEvery(Duration interval, int maxFires) {
            if (this.reminders != null) {
                throw new IllegalStateException("reminders already configured");
            }
            this.reminders = new ReminderSpec.RecurringInterval(interval, maxFires);
            return this;
        }

        /**
         * Configure unbounded recurring reminders that fire every {@code interval} until the task
         * closes.
         *
         * @param interval the firing interval (positive, non-zero)
         * @return this builder for chaining
         * @throws IllegalArgumentException if interval is zero or negative
         * @throws IllegalStateException    if a reminder spec was already configured on this task
         */
        public TaskBuilder reminderEvery(Duration interval) {
            if (this.reminders != null) {
                throw new IllegalStateException("reminders already configured");
            }
            this.reminders = new ReminderSpec.RecurringInterval(interval, null);
            return this;
        }

        // --- Version stability ---

        /**
         * Requires that the caller supply a {@code reviewedSubjectVersion} matching the snapshot
         * taken at task creation when completing this task.
         *
         * <p>When set, the engine validates
         * {@link dev.vertique.workflow.ops.TaskCompletionCommand#reviewedSubjectVersion()} is
         * non-null and equal to the subject-version recorded at task-creation time. A mismatch
         * throws {@link dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException}. If the
         * workflow has no versioned subject at task creation, the engine throws
         * {@link dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException}.
         *
         * <p>Not calling this method leaves {@code requireVersionStability=false} (the default).
         * In that case the engine <b>ignores {@code reviewedSubjectVersion} for version-stability
         * validation</b>, but the field still participates in the {@code task-complete} idempotency
         * fingerprint — retries with different reviewed-version values surface as
         * {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException}. Pass the same
         * value (typically {@code null}) on every retry of the same logical command. See
         * ADR-0055 (validation contract) and ADR-0057 (fingerprint v2) for details.
         *
         * @return this builder for chaining
         */
        public TaskBuilder requireVersionStability() {
            this.requireVersionStability = true;
            return this;
        }

        // --- Finalization ---

        /**
         * Validates the accumulated task configuration, emits a {@link HumanTaskNode} into the
         * enclosing {@link WorkflowBuilder}, and returns to it for continued chaining.
         *
         * @return the enclosing {@link WorkflowBuilder} for continued chaining
         * @throws WorkflowDefinitionException if no assignment was set, no decisions were declared,
         *     or the due-date triplet is partially set
         */
        public WorkflowBuilder<S> build() {
            if (assignmentSpec == null) {
                throw new WorkflowDefinitionException(
                        "task '" + stepId + "': assignment must be set via assignToUser/Role/Queue(...)");
            }
            if (decisions.isEmpty()) {
                throw new WorkflowDefinitionException(
                        "task '" + stepId + "': at least one decision(...) must be added");
            }
            boolean hasDue = (dueDateSpec != null) || (onDueMutatorCbId != null) || (dueNextStepId != null);
            boolean fullDue = (dueDateSpec != null) && (onDueMutatorCbId != null) && (dueNextStepId != null);
            if (hasDue && !fullDue) {
                throw new WorkflowDefinitionException(
                        "task '" + stepId + "': dueIn/dueAt/dueFromState, onDue, and toStepOnDue must be set"
                                + " together or all omitted");
            }
            nodes.add(new HumanTaskNode(
                    stepId,
                    assignmentSpec,
                    decisions,
                    dueDateSpec,
                    onDueMutatorCbId,
                    dueNextStepId,
                    reminders,
                    requireVersionStability));
            return WorkflowBuilder.this;
        }

        /**
         * Adds a completed {@link HumanTaskNode.TaskDecision} to this builder's decision list.
         * Called by {@link TaskDecisionBuilder#toStep} after the applicator is registered.
         *
         * @param decision the completed decision to add
         */
        void addDecision(HumanTaskNode.TaskDecision decision) {
            decisions.add(decision);
        }
    }

    /**
     * Sub-builder for a single named decision within a {@link TaskBuilder}.
     *
     * <p>Call {@link #onDecision} to register the state applicator, then {@link #toStep} to
     * supply the next step id. {@link #toStep} returns control to the parent {@link TaskBuilder}
     * so additional decisions can be chained.
     *
     * @param <P> the decision payload type
     */
    public final class TaskDecisionBuilder<P> {

        private final TaskBuilder parent;
        private final String name;
        private final Class<P> payloadType;
        private CallbackId applicatorCbId;

        /**
         * Constructs a {@code TaskDecisionBuilder}.
         *
         * @param parent the enclosing {@code TaskBuilder}
         * @param name the decision name
         * @param payloadType the runtime class of the decision payload
         */
        private TaskDecisionBuilder(TaskBuilder parent, String name, Class<P> payloadType) {
            this.parent = parent;
            this.name = name;
            this.payloadType = payloadType;
        }

        /**
         * Registers the applicator function that merges the decision payload into the workflow
         * state.
         *
         * @param applicator function that merges the incoming payload into the current state;
         *     must not be null
         * @return this builder for chaining
         */
        @SuppressWarnings("unchecked")
        public TaskDecisionBuilder<P> onDecision(BiFunction<S, P, S> applicator) {
            CallbackId cbId = callbackId(parent.stepId, "decision." + name + ".applicator");
            decisionApplicators.put(
                    cbId.value(), (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) applicator);
            this.applicatorCbId = cbId;
            return this;
        }

        /**
         * Finalises this decision by setting the next step id, registering the
         * {@link HumanTaskNode.TaskDecision} in the parent builder, and returning to the parent
         * {@link TaskBuilder} for continued chaining.
         *
         * @param nextStepId the step to advance to after this decision is applied; must refer to a
         *     node declared in the same plan
         * @return the parent {@link TaskBuilder} for continued chaining
         * @throws WorkflowDefinitionException if {@link #onDecision} was not called before this
         */
        public TaskBuilder toStep(String nextStepId) {
            if (applicatorCbId == null) {
                throw new WorkflowDefinitionException("task '" + parent.stepId + "' decision '" + name
                        + "': onDecision(...) must be called before toStep(...)");
            }
            parent.addDecision(new HumanTaskNode.TaskDecision(name, payloadType.getName(), applicatorCbId, nextStepId));
            return parent;
        }
    }

    // --- Private helpers ---

    /**
     * Validates that every cross-step reference in the node list points to a declared step.
     *
     * <p>Checked references:
     * <ul>
     *   <li>{@link TimerNode#nextStepId()} — the step the engine advances to after the timer fires</li>
     *   <li>{@link WaitSignalNode.TimeoutBranch#timeoutNextStepId()} — the timeout successor step</li>
     * </ul>
     *
     * <p>This method does not validate {@link ServiceDispatchNode#nextStepId()} or
     * {@link WaitSignalNode#nextStepId()} because those were already accepted at cycle 1 without
     * cross-reference validation; adding them now would be a broader scope change. The cycle-2
     * additions are validated here because their step ids are the first to be explicitly required
     * by the plan spec (§3.9).
     *
     * @throws WorkflowDefinitionException if any referenced step id is not declared in the plan
     */
    private void validateStepReferences() {
        java.util.Set<String> knownStepIds = new java.util.HashSet<>();
        for (WorkflowNode node : nodes) {
            knownStepIds.add(node.stepId());
        }
        for (WorkflowNode node : nodes) {
            switch (node) {
                case TimerNode tn -> {
                    if (!knownStepIds.contains(tn.nextStepId())) {
                        throw new WorkflowDefinitionException("TimerNode '" + tn.stepId()
                                + "' references unknown nextStepId '" + tn.nextStepId() + "'");
                    }
                }
                case WaitSignalNode wsn -> {
                    if (wsn.timeout() != null
                            && !knownStepIds.contains(wsn.timeout().timeoutNextStepId())) {
                        throw new WorkflowDefinitionException(
                                "WaitSignalNode '" + wsn.stepId() + "' timeout branch references unknown"
                                        + " timeoutNextStepId '" + wsn.timeout().timeoutNextStepId() + "'");
                    }
                }
                default -> {
                    // no cross-step references to validate for other node types
                }
            }
        }
    }

    /**
     * Throws {@link WorkflowDefinitionException} if {@link #init} has not been called yet.
     */
    private void requireInit() {
        if (!initCalled) {
            throw new WorkflowDefinitionException("init(...) must be called before any step DSL methods");
        }
    }

    /**
     * Throws {@link WorkflowDefinitionException} if a node with the given {@code stepId} already
     * exists in the builder's node list.
     *
     * @param stepId the step id to check for uniqueness
     */
    private void requireUniqueStepId(String stepId) {
        for (WorkflowNode existing : nodes) {
            if (existing.stepId().equals(stepId)) {
                throw new WorkflowDefinitionException(
                        "duplicate stepId '" + stepId + "'; each step must have a unique id within the plan");
            }
        }
    }

    /**
     * Allocates a deterministic {@link CallbackId} from a step id and a role suffix.
     *
     * <p>The id value is {@code "<stepId>.<role>"} which is stable across builder runs given the
     * same DSL invocation sequence, ensuring that {@code planHash} is deterministic.
     *
     * @param stepId the step id
     * @param role the role suffix (e.g., {@code "payload"}, {@code "updater"})
     * @return the allocated callback id
     */
    private static CallbackId callbackId(String stepId, String role) {
        return new CallbackId(stepId + "." + role);
    }

    /**
     * Computes a SHA-256 hex digest over the plan's canonical content.
     *
     * <p>Only string-valued fields are included: definitionId, definitionVersion, stateTypeName,
     * initialStepId, and for each node: its simple class name and all string/CallbackId fields in
     * declaration order. {@code Function} instances and {@code Class} references are excluded so the
     * hash is deterministic across JVMs.
     *
     * <p>Including {@code stateTypeName} ensures that a state-type change without a version bump is
     * detected as hash drift by the engine's plan-hash drift check.
     *
     * <p>Cycle-2 additions: {@link TimerNode} contributes a variant tag and its payload. For
     * {@link WaitSignalNode} with a non-null {@code timeout}, the timeout spec and mutator callback
     * id are appended. Cycle-1 plans that have no {@link TimerNode}s and no timeout branches hash
     * identically to before.
     *
     * @param definitionId the definition id
     * @param definitionVersion the definition version
     * @param stateTypeName the fully-qualified state type name; may be empty but not null
     * @param initialStepId the initial step id
     * @param nodes the ordered list of nodes
     * @return lowercase hex SHA-256 digest; 64 characters
     */
    /**
     * Test-only helper that computes the canonical plan hash for a hand-crafted
     * {@link WorkflowPlan}. Used by drift coverage tests that exercise nodes (e.g., {@code
     * ForkNode}/{@code JoinNode}) before their fluent DSL methods exist on this builder. Not part
     * of the public DSL surface; do not call from production code.
     *
     * @param plan a fully-populated plan record (the {@code planHash} field of the input is
     *     ignored — the helper computes a fresh hash from the other fields)
     * @return lowercase hex SHA-256 digest; 64 characters
     */
    public static String computePlanHashForTesting(WorkflowPlan plan) {
        return computePlanHash(
                plan.definitionId(),
                plan.definitionVersion(),
                plan.stateTypeName(),
                plan.initialStepId(),
                plan.nodes(),
                plan.subjectResolverCallbackId());
    }

    private static String computePlanHash(
            String definitionId,
            long definitionVersion,
            String stateTypeName,
            String initialStepId,
            List<WorkflowNode> nodes,
            @Nullable CallbackId subjectResolverCallbackId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, definitionId);
            update(digest, Long.toString(definitionVersion));
            update(digest, stateTypeName != null ? stateTypeName : "");
            update(digest, initialStepId != null ? initialStepId : "");
            for (WorkflowNode node : nodes) {
                // Include the concrete type name so node-type changes affect the hash
                update(digest, node.getClass().getSimpleName());
                update(digest, node.stepId());
                switch (node) {
                    case ServiceDispatchNode n -> {
                        update(digest, n.targetId());
                        update(digest, n.payloadCallbackId().value());
                        update(digest, n.compensationStepId() != null ? n.compensationStepId() : "");
                        update(digest, n.nextStepId());
                    }
                    case WaitSignalNode n -> {
                        update(digest, n.signalName());
                        update(digest, n.payloadTypeName());
                        update(digest, n.stateUpdaterCallbackId().value());
                        update(digest, n.nextStepId());
                        // Cycle-2: include timeout branch if present
                        if (n.timeout() != null) {
                            updateTimerSpec(digest, n.timeout().timeout());
                            update(
                                    digest,
                                    n.timeout().onTimeoutMutatorCallbackId().value());
                            update(digest, n.timeout().timeoutNextStepId());
                        }
                    }
                    case TimerNode n -> {
                        update(digest, n.nextStepId());
                        updateTimerSpec(digest, n.spec());
                    }
                    case DecisionNode n -> {
                        update(digest, n.nextStepResolverCallbackId().value());
                    }
                    case CompleteNode ignored -> {
                        // no extra fields
                    }
                    case FailNode n -> {
                        update(digest, n.errorType());
                        update(digest, n.messageFactoryCallbackId().value());
                    }
                    case CompensationNode n -> {
                        update(digest, n.forwardStepId());
                        update(digest, n.targetId());
                        update(digest, n.payloadCallbackId().value());
                    }
                    case ForkNode n -> {
                        // PRD-WF-002 A.4.8: branches in declaration order, joinStepId, retry policy.
                        update(digest, Integer.toString(n.branches().size()));
                        for (BranchStart b : n.branches()) {
                            update(digest, b.branchId());
                            update(digest, b.startStepId());
                            update(digest, b.raceSafety().name());
                        }
                        update(digest, n.joinStepId());
                        BranchRetryPolicy rp = n.retryPolicy();
                        update(digest, Integer.toString(rp.maxAttempts()));
                        update(digest, Long.toString(rp.initialDelay().toMillis()));
                        update(digest, rp.backoff().name());
                    }
                    case JoinNode n -> {
                        // PRD-WF-002 A.4.8: policy variant, reducer callback, next/failure routes.
                        update(digest, n.policy().getClass().getSimpleName());
                        update(digest, n.branchResultReducerCallbackId().value());
                        update(digest, n.nextStepId());
                        update(digest, n.failureStepId() != null ? n.failureStepId() : "");
                    }
                    case HumanTaskNode n -> {
                        updateAssignmentSpec(digest, n.assignment());
                        // Decisions hashed in declared order (NOT sorted) — order is part of the plan contract.
                        update(digest, Integer.toString(n.decisions().size()));
                        for (HumanTaskNode.TaskDecision d : n.decisions()) {
                            update(digest, d.name());
                            update(digest, d.payloadTypeName());
                            update(digest, d.applicatorCallbackId().value());
                            update(digest, d.nextStepId());
                        }
                        if (n.dueDate() == null) {
                            update(digest, "no-due");
                        } else {
                            update(digest, "due");
                            updateTimerSpec(digest, n.dueDate());
                            update(digest, n.onDueMutatorCallbackId().value());
                            update(digest, n.dueNextStepId());
                        }
                        // Cycle-4: reminders — only emit bytes when non-null so that
                        // reminders=null produces IDENTICAL bytes to the cycle-3 hash
                        // (no "no-reminders" sentinel is written; the field is simply absent).
                        if (n.reminders() != null) {
                            updateReminderSpec(digest, n.reminders());
                        }
                        // Cycle-5: requireVersionStability — only emit bytes when true so that
                        // requireVersionStability=false produces IDENTICAL bytes to cycle-4 hash
                        // (no false-sentinel is written; the field is simply absent).
                        if (n.requireVersionStability()) {
                            update(digest, "requireVersionStability");
                        }
                    }
                    default -> update(digest, "");
                }
            }
            // Cycle-5: subject resolver — only emit bytes when non-null so that
            // subjectResolverCallbackId=null produces IDENTICAL bytes to the cycle-4 hash
            // (no sentinel is written; the field is simply absent).
            if (subjectResolverCallbackId != null) {
                update(digest, "subjectResolver");
                update(digest, subjectResolverCallbackId.value());
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Updates the digest with a {@link TimerSpec}, including a variant tag and its payload fields.
     *
     * <p>The variant tag ({@code "At"}, {@code "After"}, or {@code "FromState"}) prevents different
     * spec types with coincidentally equal payloads from hashing identically. Payload values are
     * serialized as deterministic strings.
     *
     * @param digest the digest to update
     * @param spec the timer spec to incorporate
     */
    /**
     * Updates the digest with a {@link HumanTaskNode.AssignmentSpec}, including a variant tag
     * and either the literal value (User/Role/Queue) or the {@code CallbackId} (FromState
     * variants). The variant tag prevents a literal {@code Role("alice")} from colliding with a
     * literal {@code User("alice")}, and a {@code RoleFromState(cb)} from colliding with a
     * {@code UserFromState(cb)}.
     *
     * @param digest the digest to update
     * @param spec the assignment spec to incorporate
     */
    private static void updateAssignmentSpec(MessageDigest digest, HumanTaskNode.AssignmentSpec spec) {
        switch (spec) {
            case HumanTaskNode.AssignmentSpec.User u -> {
                update(digest, "User");
                update(digest, u.userId());
            }
            case HumanTaskNode.AssignmentSpec.UserFromState u -> {
                update(digest, "UserFromState");
                update(digest, u.resolverCallbackId().value());
            }
            case HumanTaskNode.AssignmentSpec.Role r -> {
                update(digest, "Role");
                update(digest, r.roleId());
            }
            case HumanTaskNode.AssignmentSpec.RoleFromState r -> {
                update(digest, "RoleFromState");
                update(digest, r.resolverCallbackId().value());
            }
            case HumanTaskNode.AssignmentSpec.Queue q -> {
                update(digest, "Queue");
                update(digest, q.queueName());
            }
            case HumanTaskNode.AssignmentSpec.QueueFromState q -> {
                update(digest, "QueueFromState");
                update(digest, q.resolverCallbackId().value());
            }
        }
    }

    /**
     * Updates the digest with a {@link ReminderSpec}, including a variant tag and its payload
     * fields. The variant tag ({@code "OneShotOffsets"} or {@code "RecurringInterval"}) prevents
     * different spec types from colliding. Only called when {@code reminders != null}; callers that
     * omit this call for a null reminders field preserve cycle-3 plan-hash continuity.
     *
     * <p>For {@link ReminderSpec.OneShotOffsets}: emits the count of offsets followed by each
     * offset's seconds and nanos. Because {@link ReminderSpec.OneShotOffsets} canonicalizes its
     * input list to ascending order at construction time, two declarations like
     * {@code reminderAt(2h, 30m)} and {@code reminderAt(30m, 2h)} produce identical specs and
     * therefore identical plan hashes — declaration order does NOT affect the hash. Adding,
     * removing, or changing any offset value DOES flip the hash.
     *
     * <p>For {@link ReminderSpec.RecurringInterval}: emits the interval seconds, interval nanos,
     * and a maxFires marker ({@code "unbounded"} when null; the integer string otherwise) so that
     * bounded vs. unbounded and differing maxFires values each produce a distinct hash.
     *
     * @param digest the digest to update
     * @param spec   the non-null reminder spec to incorporate
     */
    private static void updateReminderSpec(MessageDigest digest, ReminderSpec spec) {
        switch (spec) {
            case ReminderSpec.OneShotOffsets o -> {
                update(digest, "OneShotOffsets");
                update(digest, Integer.toString(o.offsetsFromTaskCreation().size()));
                for (java.time.Duration d : o.offsetsFromTaskCreation()) {
                    update(digest, Long.toString(d.getSeconds()));
                    update(digest, Integer.toString(d.getNano()));
                }
            }
            case ReminderSpec.RecurringInterval r -> {
                update(digest, "RecurringInterval");
                update(digest, Long.toString(r.interval().getSeconds()));
                update(digest, Integer.toString(r.interval().getNano()));
                update(digest, r.maxFires() == null ? "unbounded" : Integer.toString(r.maxFires()));
            }
        }
    }

    private static void updateTimerSpec(MessageDigest digest, TimerSpec spec) {
        switch (spec) {
            case TimerSpec.At at -> {
                // Hash both epochSecond and nano so sub-millisecond differences flip the planHash.
                // Long.toString(toEpochMilli()) would silently lose nanosecond precision.
                update(digest, "At");
                update(digest, Long.toString(at.fireAt().getEpochSecond()));
                update(digest, Integer.toString(at.fireAt().getNano()));
            }
            case TimerSpec.After after -> {
                // Hash seconds + nanos rather than toNanos() so ridiculously-long durations
                // (>~292 years) don't trip ArithmeticException at hash time.
                update(digest, "After");
                update(digest, Long.toString(after.delay().getSeconds()));
                update(digest, Integer.toString(after.delay().getNano()));
            }
            case TimerSpec.FromState fromState -> {
                update(digest, "FromState");
                update(digest, fromState.resolverCallbackId().value());
            }
        }
    }

    /**
     * Updates the digest with a string field, prefixed by its length to prevent prefix-extension
     * collisions.
     *
     * @param digest the digest to update
     * @param value the string value to incorporate
     */
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Prefix with length (as 4-byte big-endian) to avoid concatenation ambiguity
        int len = bytes.length;
        digest.update((byte) (len >>> 24));
        digest.update((byte) (len >>> 16));
        digest.update((byte) (len >>> 8));
        digest.update((byte) len);
        digest.update(bytes);
    }

    // --- InternalCallbackRegistry ---

    /**
     * Package-accessible {@link WorkflowCallbackRegistry} implementation backed by immutable maps
     * built by the enclosing builder. The maps are accessible from the {@code registry} package
     * for merging into the global aggregated registry.
     */
    record InternalCallbackRegistry(
            Map<String, Function<Object, Object>> payloadFactories,
            Map<String, BiFunction<Object, Object, Object>> stateUpdaters,
            Map<String, Function<Object, String>> decisionResolvers,
            Map<String, Function<Object, String>> failMessageFactories,
            Map<String, Function<Object, Instant>> timerResolvers,
            Map<String, Function<Object, Object>> stateMutators,
            Map<String, BiFunction<Object, Object, Object>> decisionApplicators,
            Map<String, Function<Object, TaskAssignment>> taskAssignmentResolvers,
            Map<String, Function<Object, WorkflowSubjectRef>> subjectResolvers,
            Map<String, BiFunction<Object, Map<String, BranchResult>, Object>> branchResultReducers)
            implements WorkflowCallbackRegistry {

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, Object> payloadFactory(CallbackId id) {
            Function<Object, Object> fn = payloadFactories.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No payload factory for CallbackId: " + id.value());
            return (Function<S, Object>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> BiFunction<S, Object, S> stateUpdater(CallbackId id) {
            BiFunction<Object, Object, Object> fn = stateUpdaters.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No state updater for CallbackId: " + id.value());
            return (BiFunction<S, Object, S>) (BiFunction<?, ?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, String> decisionResolver(CallbackId id) {
            Function<Object, String> fn = decisionResolvers.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No decision resolver for CallbackId: " + id.value());
            return (Function<S, String>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, String> failMessageFactory(CallbackId id) {
            Function<Object, String> fn = failMessageFactories.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No fail message factory for CallbackId: " + id.value());
            return (Function<S, String>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, Instant> timerResolver(CallbackId id) {
            Function<Object, Instant> fn = timerResolvers.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No timer resolver for CallbackId: " + id.value());
            return (Function<S, Instant>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S, P> BiFunction<S, P, S> signalApplicator(CallbackId id) {
            // Option A: signalApplicator delegates to stateUpdater storage
            BiFunction<Object, Object, Object> fn = stateUpdaters.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No signal applicator for CallbackId: " + id.value());
            return (BiFunction<S, P, S>) (BiFunction<?, ?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, S> stateMutator(CallbackId id) {
            Function<Object, Object> fn = stateMutators.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No state mutator for CallbackId: " + id.value());
            return (Function<S, S>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S, P> BiFunction<S, P, S> decisionApplicator(CallbackId id) {
            BiFunction<Object, Object, Object> fn = decisionApplicators.get(id.value());
            if (fn == null) throw new IllegalArgumentException("No decision applicator for CallbackId: " + id.value());
            return (BiFunction<S, P, S>) (BiFunction<?, ?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, TaskAssignment> taskAssignmentResolver(CallbackId id) {
            Function<Object, TaskAssignment> fn = taskAssignmentResolvers.get(id.value());
            if (fn == null)
                throw new IllegalArgumentException("No task assignment resolver for CallbackId: " + id.value());
            return (Function<S, TaskAssignment>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> Function<S, WorkflowSubjectRef> subjectResolver(CallbackId id) {
            Function<Object, WorkflowSubjectRef> fn = subjectResolvers.get(id.value());
            if (fn == null) throw new UnsupportedOperationException("subjectResolver not registered for " + id);
            return (Function<S, WorkflowSubjectRef>) (Function<?, ?>) fn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <S> BiFunction<S, Map<String, BranchResult>, S> branchResultReducer(CallbackId id) {
            BiFunction<Object, Map<String, BranchResult>, Object> fn = branchResultReducers.get(id.value());
            if (fn == null) {
                throw new UnsupportedOperationException("branchResultReducer not registered for " + id);
            }
            return (BiFunction<S, Map<String, BranchResult>, S>) (BiFunction<?, ?, ?>) fn;
        }
    }
}
