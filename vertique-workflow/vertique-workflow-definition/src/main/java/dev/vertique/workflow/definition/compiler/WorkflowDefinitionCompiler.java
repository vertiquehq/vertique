// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import dev.vertique.workflow.definition.callbacks.NamedBranchResultReducer;
import dev.vertique.workflow.definition.callbacks.NamedFailMessageFactory;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateMutator;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.NamedSubjectResolver;
import dev.vertique.workflow.definition.callbacks.NamedTaskAssignmentResolver;
import dev.vertique.workflow.definition.callbacks.NamedTimerResolver;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.expression.ExpressionEnv;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.schema.CompensationStep;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.definition.schema.FailStep;
import dev.vertique.workflow.definition.schema.ForkStep;
import dev.vertique.workflow.definition.schema.HumanTaskStep;
import dev.vertique.workflow.definition.schema.JoinStep;
import dev.vertique.workflow.definition.schema.ServiceStep;
import dev.vertique.workflow.definition.schema.StepNode;
import dev.vertique.workflow.definition.schema.TimerStep;
import dev.vertique.workflow.definition.schema.WaitSignalStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.ForkScope;
import dev.vertique.workflow.dsl.JoinScope;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.BranchResult;
import dev.vertique.workflow.plan.BranchRetryPolicy;
import dev.vertique.workflow.plan.BranchRetryPolicy.BackoffStrategy;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Translates a parsed {@link WorkflowDefinitionDocument} into a sequence of
 * {@link WorkflowBuilder} calls, populating the builder with all steps and callbacks derived from
 * the document.
 *
 * <p>The compiler is the only class that knows about both the document schema (in
 * {@code definition.schema}) and the builder DSL (in {@code workflow-core}). It resolves named
 * callback ids from the {@link RegisteredIdentifierLookup} and dispatches each {@link StepNode}
 * variant to the corresponding {@code WorkflowBuilder} method using a {@code switch} on the sealed
 * interface.
 *
 * <p>For decision steps, the compiler delegates to {@link DecisionRouteCompiler} to compile the
 * route table into a synthesized resolver function and a fingerprinted {@link dev.vertique.workflow.registry.CallbackId},
 * then emits the decision node via
 * {@link WorkflowBuilder#decideWithCallbackId(String, Function, dev.vertique.workflow.registry.CallbackId)}.
 *
 * <p>Fork/join coupling: when emitting a {@link ForkStep}, the compiler scans forward in the step
 * list to find the matching {@link JoinStep} by id, configures the fork and join in one chained
 * call, then skips the {@link JoinStep} when its turn comes in the main loop via a {@code Set} of
 * already-emitted step ids.
 *
 * <p>This class does not modify any file in {@code workflow-core} other than driving the public
 * {@link WorkflowBuilder} API. It never accesses private builder state.
 */
@Singleton
public final class WorkflowDefinitionCompiler {

    // --- Dependencies ---

    private final RegisteredIdentifierLookup lookup;
    private final ExpressionProfile profile;
    private final DecisionRouteCompiler decisionRouteCompiler;

    // --- Construction ---

    /**
     * Constructs the compiler with all required collaborators.
     *
     * @param lookup the facade over all 10 named callback registries; non-null
     * @param profile the expression profile used to build the expression environment for decision
     *     steps; non-null
     * @param decisionRouteCompiler the compiler for decision route tables; non-null
     */
    @Inject
    public WorkflowDefinitionCompiler(
            RegisteredIdentifierLookup lookup, ExpressionProfile profile, DecisionRouteCompiler decisionRouteCompiler) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.decisionRouteCompiler = Objects.requireNonNull(decisionRouteCompiler, "decisionRouteCompiler");
    }

    // --- Public API ---

    /**
     * Emits all steps from the given document into the provided (raw-typed) builder.
     *
     * <p>This method is the entry point called by {@link DocumentBackedWorkflowDefinition#define}.
     * It uses a raw-typed builder to avoid unchecked-cast proliferation at the call site; the
     * validator has already confirmed that {@code stateType} and {@code contract} resolve to the
     * correct classes.
     *
     * @param builder the workflow builder to emit steps into; raw-typed, non-null
     * @param stateType the workflow state class resolved from the document; non-null
     * @param contract the workflow contract class resolved from the document; non-null
     * @param doc the document to compile; non-null
     * @throws WorkflowDefinitionException if any named id is not registered or any step variant
     *     cannot be compiled
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void emit(WorkflowBuilder builder, Class<?> stateType, Class<?> contract, WorkflowDefinitionDocument doc) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(doc, "doc");

        // --- Init ---
        NamedStartStateMapper<?, ?> startMapper = lookup.startStateMappers().lookup(doc.initialStateMapper());
        // Raw casts are safe by construction: the validator has confirmed type compatibility via
        // INITIAL_STATE_MAPPER_STATE_TYPE_MISMATCH and INITIAL_STATE_MAPPER_PAYLOAD_TYPE_MISMATCH
        // checks before this compiler is invoked.
        Function startMapperFn = startMapper.mapper();
        Class startPayloadClass = startMapper.payloadType();
        builder.init(startPayloadClass, startMapperFn);

        // --- Optional subject resolver ---
        if (doc.subjectResolver() != null) {
            NamedSubjectResolver<?> subjectResolver = lookup.subjectResolvers().lookup(doc.subjectResolver());
            Function<Object, WorkflowSubjectRef> subjectFn =
                    (Function<Object, WorkflowSubjectRef>) (Function<?, ?>) subjectResolver.resolver();
            builder.subject(subjectFn);
        }

        // --- Initial step ---
        builder.initialStep(doc.initialStep());

        // --- Build per-document expression environment ---
        Set<String> namedConditionIds = collectNamedConditionIds(doc);
        ExpressionEnv env = new ExpressionEnv(stateType, namedConditionIds, Set.of());

        // --- Emit steps, tracking already-emitted ids (for fork/join coupling) ---
        Set<String> emittedStepIds = new HashSet<>();
        for (StepNode node : doc.steps()) {
            if (emittedStepIds.contains(node.id())) {
                // Already emitted as part of a fork/join coupling — skip
                continue;
            }
            emitStep(builder, doc, node, env, emittedStepIds);
        }
    }

    // --- Private step dispatch ---

    /**
     * Dispatches a single {@link StepNode} to the appropriate {@link WorkflowBuilder} method.
     *
     * @param builder the builder to emit the step into
     * @param doc the full document (needed for fork/join forward scan)
     * @param node the step node to emit
     * @param env the expression environment for decision steps
     * @param emittedStepIds the set of step ids already emitted; updated by this method
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitStep(
            WorkflowBuilder builder,
            WorkflowDefinitionDocument doc,
            StepNode node,
            ExpressionEnv env,
            Set<String> emittedStepIds) {
        emittedStepIds.add(node.id());
        switch (node) {
            case ServiceStep step -> emitService(builder, step);
            case WaitSignalStep step -> emitWaitSignal(builder, step);
            case TimerStep step -> emitTimer(builder, step);
            case HumanTaskStep step -> emitHumanTask(builder, step);
            case DecisionStep step -> emitDecision(builder, doc, step, env);
            case CompleteStep step -> builder.complete(step.id());
            case FailStep step -> emitFail(builder, step);
            case CompensationStep step -> emitCompensation(builder, step);
            case ForkStep step -> emitForkJoin(builder, doc, step, env, emittedStepIds);
            case JoinStep step ->
                throw new WorkflowDefinitionException(
                        "join step '" + step.id() + "' encountered outside a fork/join coupling;"
                                + " join steps must be referenced by a fork step's 'join' field");
        }
    }

    /**
     * Emits a {@link ServiceStep} as either a {@code dispatch} or {@code dispatchWithCompensation}
     * call depending on whether a compensation step is specified.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitService(WorkflowBuilder builder, ServiceStep step) {
        NamedPayloadMapper<?> payloadMapper = lookup.payloadMappers().lookup(step.payloadMapper());
        Function<Object, Object> payloadFn = (Function<Object, Object>) (Function<?, ?>) payloadMapper.mapper();

        if (step.compensation() == null) {
            builder.dispatch(step.id(), step.target(), payloadFn, step.next());
        } else {
            builder.dispatchWithCompensation(step.id(), step.target(), payloadFn, step.compensation(), step.next());
        }
    }

    /**
     * Emits a {@link WaitSignalStep} as either a simple {@code waitForSignal} chain (no timeout)
     * or a {@code waitForSignal} chain with a timeout branch.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitWaitSignal(WorkflowBuilder builder, WaitSignalStep step) {
        NamedStateReducer<?> reducer = lookup.stateReducers().lookup(step.stateReducer());
        BiFunction<Object, Object, Object> reducerFn =
                (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) reducer.reducer();

        Class<?> payloadClass = loadClass(step.payloadType(), "signal payload for step '" + step.id() + "'");

        if (step.timeout() == null) {
            builder.waitForSignal(step.id(), step.signal(), payloadClass)
                    .onSignal(reducerFn)
                    .toStepOnSignal(step.next())
                    .build();
        } else {
            WaitSignalStep.TimeoutBlock timeout = step.timeout();
            NamedStateMutator<?> mutator = lookup.stateMutators().lookup(timeout.onTimeoutMutator());
            Function<Object, Object> mutatorFn = (Function<Object, Object>) (Function<?, ?>) mutator.mutator();

            var withNext = builder.waitForSignal(step.id(), step.signal(), payloadClass)
                    .onSignal(reducerFn)
                    .toStepOnSignal(step.next());

            // Determine the timeout spec: Duration, Instant, or ref:id
            String after = timeout.after();
            if (after.startsWith("ref:")) {
                // Strip whitespace so "ref: myId" and "ref:myId" resolve identically — the
                // validator also strips when checking the id, keeping both in sync.
                String resolverId = after.substring(4).strip();
                NamedTimerResolver<?> timerResolver = lookup.timerResolvers().lookup(resolverId);
                Function<Object, Instant> resolverFn =
                        (Function<Object, Instant>) (Function<?, ?>) timerResolver.resolver();
                withNext.timeoutFromState(resolverFn).onTimeout(mutatorFn).toStepOnTimeout(timeout.next());
            } else {
                Duration dur = tryParseDuration(after);
                if (dur != null) {
                    withNext.timeoutAfter(dur).onTimeout(mutatorFn).toStepOnTimeout(timeout.next());
                } else {
                    Instant instant = Instant.parse(after);
                    withNext.timeoutAt(instant).onTimeout(mutatorFn).toStepOnTimeout(timeout.next());
                }
            }
        }
    }

    /**
     * Emits a {@link TimerStep} as a {@code timer} or {@code timerAt} call depending on the
     * {@code fireAt} value: ISO Duration, ISO Instant, or {@code ref:<id>}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitTimer(WorkflowBuilder builder, TimerStep step) {
        String fireAt = step.fireAt();
        if (fireAt.startsWith("ref:")) {
            // Strip whitespace so "ref: myId" and "ref:myId" resolve identically — the
            // validator also strips when checking the id, keeping both in sync.
            String resolverId = fireAt.substring(4).strip();
            NamedTimerResolver<?> resolver = lookup.timerResolvers().lookup(resolverId);
            Function<Object, Instant> resolverFn = (Function<Object, Instant>) (Function<?, ?>) resolver.resolver();
            builder.timerAt(step.id(), resolverFn).toStep(step.next());
        } else {
            Duration dur = tryParseDuration(fireAt);
            if (dur != null) {
                builder.timer(step.id(), dur).toStep(step.next());
            } else {
                Instant instant = Instant.parse(fireAt);
                builder.timer(step.id(), instant).toStep(step.next());
            }
        }
    }

    /**
     * Emits a {@link HumanTaskStep} using the {@link WorkflowBuilder#task(String)} fluent chain.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitHumanTask(WorkflowBuilder builder, HumanTaskStep step) {
        var taskBuilder = builder.task(step.id());

        // --- Assignment ---
        HumanTaskStep.AssignmentBlock assignment = step.assignment();
        switch (assignment.mode()) {
            case "user" -> taskBuilder.assignToUser(assignment.value());
            case "role" -> taskBuilder.assignToRole(assignment.value());
            case "queue" -> taskBuilder.assignToQueue(assignment.value());
            case "user-from-state" -> {
                NamedTaskAssignmentResolver<?> resolver =
                        lookup.taskAssignmentResolvers().lookup(assignment.resolver());
                Function<Object, String> resolverFn = (Function<Object, String>)
                        (Function<?, ?>) resolver.resolver().andThen(ta -> ((TaskAssignment.User) ta).userId());
                // Use the full resolver directly — it already returns TaskAssignment
                NamedTaskAssignmentResolver<?> rawResolver =
                        lookup.taskAssignmentResolvers().lookup(assignment.resolver());
                Function<Object, TaskAssignment> taskAssignmentFn =
                        (Function<Object, TaskAssignment>) (Function<?, ?>) rawResolver.resolver();
                // Emit as user-from-state via a resolver that returns the userId
                taskBuilder.assignToUser(
                        (Function) state -> ((TaskAssignment.User) taskAssignmentFn.apply(state)).userId());
            }
            case "role-from-state" -> {
                NamedTaskAssignmentResolver<?> resolver =
                        lookup.taskAssignmentResolvers().lookup(assignment.resolver());
                Function<Object, TaskAssignment> taskAssignmentFn =
                        (Function<Object, TaskAssignment>) (Function<?, ?>) resolver.resolver();
                taskBuilder.assignToRole(
                        (Function) state -> ((TaskAssignment.Role) taskAssignmentFn.apply(state)).roleId());
            }
            case "queue-from-state" -> {
                NamedTaskAssignmentResolver<?> resolver =
                        lookup.taskAssignmentResolvers().lookup(assignment.resolver());
                Function<Object, TaskAssignment> taskAssignmentFn =
                        (Function<Object, TaskAssignment>) (Function<?, ?>) resolver.resolver();
                taskBuilder.assignToQueue(
                        (Function) state -> ((TaskAssignment.Queue) taskAssignmentFn.apply(state)).queueName());
            }
            default ->
                throw new WorkflowDefinitionException(
                        "human-task '" + step.id() + "': unknown assignment mode '" + assignment.mode() + "'");
        }

        // --- Decisions ---
        for (HumanTaskStep.TaskDecisionBlock decision : step.decisions()) {
            Class<?> payloadClass = loadClass(
                    decision.payloadType(),
                    "decision payload for task '" + step.id() + "' decision '" + decision.name() + "'");
            NamedStateReducer<?> applicator = lookup.stateReducers().lookup(decision.applicator());
            BiFunction<Object, Object, Object> applicatorFn =
                    (BiFunction<Object, Object, Object>) (BiFunction<?, ?, ?>) applicator.reducer();
            taskBuilder
                    .decision(decision.name(), payloadClass)
                    .onDecision(applicatorFn)
                    .toStep(decision.next());
        }

        // --- Optional due date ---
        if (step.due() != null) {
            HumanTaskStep.DueBlock due = step.due();
            NamedStateMutator<?> mutator = lookup.stateMutators().lookup(due.onDueMutator());
            Function<Object, Object> mutatorFn = (Function<Object, Object>) (Function<?, ?>) mutator.mutator();

            String at = due.at();
            if (at.startsWith("ref:")) {
                // Strip whitespace so "ref: myId" and "ref:myId" resolve identically — the
                // validator also strips when checking the id, keeping both in sync.
                String resolverId = at.substring(4).strip();
                NamedTimerResolver<?> resolver = lookup.timerResolvers().lookup(resolverId);
                Function<Object, Instant> resolverFn = (Function<Object, Instant>) (Function<?, ?>) resolver.resolver();
                taskBuilder.dueFromState(resolverFn);
            } else {
                Duration dur = tryParseDuration(at);
                if (dur != null) {
                    taskBuilder.dueIn(dur);
                } else {
                    taskBuilder.dueAt(Instant.parse(at));
                }
            }
            taskBuilder.onDue(mutatorFn).toStepOnDue(due.next());
        }

        // --- Optional reminders ---
        if (step.reminders() != null) {
            HumanTaskStep.ReminderBlock reminders = step.reminders();
            if (reminders.interval() != null) {
                taskBuilder.reminderEvery(Duration.parse(reminders.interval()));
            } else if (reminders.offsets() != null && !reminders.offsets().isEmpty()) {
                Duration first = Duration.parse(reminders.offsets().get(0));
                Duration[] rest = reminders.offsets().stream()
                        .skip(1)
                        .map(Duration::parse)
                        .toArray(Duration[]::new);
                taskBuilder.reminderAt(first, rest);
            }
        }

        // --- Optional version stability ---
        if (Boolean.TRUE.equals(step.requireVersionStability())) {
            taskBuilder.requireVersionStability();
        }

        taskBuilder.build();
    }

    /**
     * Emits a {@link DecisionStep} using the {@link DecisionRouteCompiler} to build the resolver
     * and fingerprinted callback id, then calls
     * {@link WorkflowBuilder#decideWithCallbackId(String, Function, dev.vertique.workflow.registry.CallbackId)}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitDecision(
            WorkflowBuilder builder, WorkflowDefinitionDocument doc, DecisionStep step, ExpressionEnv env) {
        DecisionRouteResolver resolver =
                decisionRouteCompiler.compileRoutes(doc.definitionId(), doc.definitionVersion(), step, env);
        builder.decideWithCallbackId(step.id(), resolver.resolverFn(), resolver.fingerprintedCallbackId());
    }

    /**
     * Emits a {@link FailStep} using the fail message factory from the registry.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitFail(WorkflowBuilder builder, FailStep step) {
        NamedFailMessageFactory<?> factory = lookup.failMessageFactories().lookup(step.messageFactory());
        Function<Object, String> factoryFn = (Function<Object, String>) (Function<?, ?>) factory.factory();
        builder.fail(step.id(), step.errorType(), factoryFn);
    }

    /**
     * Emits a {@link CompensationStep} using the payload mapper from the registry.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitCompensation(WorkflowBuilder builder, CompensationStep step) {
        NamedPayloadMapper<?> mapper = lookup.payloadMappers().lookup(step.payloadMapper());
        Function<Object, Object> mapperFn = (Function<Object, Object>) (Function<?, ?>) mapper.mapper();
        builder.compensate(step.id(), step.forwardStep(), step.target(), mapperFn);
    }

    /**
     * Emits a {@link ForkStep} and its corresponding {@link JoinStep} in one chained call.
     *
     * <p>The matching {@link JoinStep} is located by scanning forward through the document's step
     * list; it is then emitted as part of the fork chain and added to {@code emittedStepIds} so
     * the main loop skips it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void emitForkJoin(
            WorkflowBuilder builder,
            WorkflowDefinitionDocument doc,
            ForkStep forkStep,
            ExpressionEnv env,
            Set<String> emittedStepIds) {
        // Find the matching JoinStep
        JoinStep joinStep = doc.steps().stream()
                .filter(n -> n.id().equals(forkStep.join()) && n instanceof JoinStep)
                .map(JoinStep.class::cast)
                .findFirst()
                .orElseThrow(() -> new WorkflowDefinitionException("fork step '" + forkStep.id() + "' references join '"
                        + forkStep.join() + "' which does not exist or is not a join step"));

        // Build fork scope
        ForkScope forkScope = builder.fork(forkStep.id());

        // Add retry policy if present
        if (forkStep.retryPolicy() != null) {
            ForkStep.BranchRetryBlock rp = forkStep.retryPolicy();
            BackoffStrategy backoff =
                    rp.backoff() != null ? BackoffStrategy.valueOf(rp.backoff()) : BackoffStrategy.FIXED;
            BranchRetryPolicy retryPolicy = BranchRetryPolicy.maxAttempts(rp.maxAttempts())
                    .initialDelay(Duration.parse(rp.initialDelay()))
                    .backoff(backoff)
                    .build();
            forkScope.retry(retryPolicy);
        }

        // Add branches
        for (ForkStep.BranchEntry branch : forkStep.branches()) {
            RaceSafety raceSafety =
                    branch.raceSafety() != null ? RaceSafety.valueOf(branch.raceSafety()) : RaceSafety.NORMAL;
            forkScope.branch(branch.branchId(), branch.startStep(), raceSafety);
        }

        // Join produces a WorkflowBuilder
        WorkflowBuilder afterFork = forkScope.join(joinStep.id());

        // Now configure the join scope
        NamedBranchResultReducer<?> reducer = lookup.branchResultReducers().lookup(joinStep.reducer());
        BiFunction<Object, Map<String, BranchResult>, Object> reducerFn =
                (BiFunction<Object, Map<String, BranchResult>, Object>) (BiFunction<?, ?, ?>) reducer.reducer();

        JoinScope joinScope;
        switch (joinStep.policy()) {
            case "all-required" -> joinScope = afterFork.join(joinStep.id()).allRequired(reducerFn);
            case "first-success" -> joinScope = afterFork.join(joinStep.id()).firstSuccess(reducerFn);
            case "first-failure" -> joinScope = afterFork.join(joinStep.id()).firstFailure(reducerFn);
            default ->
                throw new WorkflowDefinitionException(
                        "join step '" + joinStep.id() + "': unknown policy '" + joinStep.policy() + "'");
        }

        joinScope.toStep(joinStep.next());
        if (joinStep.onFailure() != null) {
            joinScope.onFailure(joinStep.onFailure());
        }
        joinScope.endJoin();

        // Mark join step as emitted so the main loop skips it
        emittedStepIds.add(joinStep.id());
    }

    // --- Expression environment helpers ---

    /**
     * Aggregates all named condition ids referenced in any {@link DecisionStep} route across the
     * document, to build a unified {@link ExpressionEnv} for the whole document.
     *
     * @param doc the document to scan
     * @return the set of all referenced named condition ids; may be empty
     */
    private static Set<String> collectNamedConditionIds(WorkflowDefinitionDocument doc) {
        Set<String> ids = new HashSet<>();
        for (StepNode node : doc.steps()) {
            if (node instanceof DecisionStep decisionStep) {
                for (DecisionStep.RouteEntry route : decisionStep.routes()) {
                    if (route.condition() != null) {
                        ids.add(route.condition());
                    }
                }
            }
        }
        return ids;
    }

    // --- Utility helpers ---

    /**
     * Attempts to parse the given string as an ISO-8601 Duration.
     *
     * @param value the string to parse; non-null
     * @return the parsed {@link Duration}, or {@code null} if the string is not a valid duration
     */
    private static Duration tryParseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Loads a class by name using the current thread's context class loader, wrapping any
     * {@link ClassNotFoundException} in a {@link WorkflowDefinitionException}.
     *
     * @param className the fully-qualified class name; non-null
     * @param context a description of the loading context for error messages
     * @return the resolved class; never null
     * @throws WorkflowDefinitionException if the class cannot be found
     */
    private static Class<?> loadClass(String className, String context) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new WorkflowDefinitionException(
                    "cannot resolve " + context + ": class '" + className + "' not found on classpath", e);
        }
    }
}
