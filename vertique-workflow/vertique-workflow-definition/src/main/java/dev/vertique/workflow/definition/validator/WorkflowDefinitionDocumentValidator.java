// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.expression.ExpressionEnv;
import dev.vertique.workflow.definition.expression.ExpressionParseException;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.schema.CompensationStep;
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
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Validates a parsed {@link WorkflowDefinitionDocument} against all structural, semantic, and
 * registry-resolution rules defined in PRD-WF-003 (FR-WF-DEF-101/102/103).
 *
 * <p>The validator always accumulates ALL violations before returning — it never short-circuits on
 * the first error. This ensures document authors receive a complete list of problems in a single
 * parse-validate round-trip, rather than fixing one error only to discover the next.
 *
 * <p>Use {@link #validate(WorkflowDefinitionDocument)} to obtain the full
 * {@link WorkflowDefinitionViolations} aggregate, or {@link #validateOrThrow(WorkflowDefinitionDocument)}
 * to throw a {@link WorkflowDefinitionLoadException} when any violations are present.
 *
 * <h2>Validation rules</h2>
 *
 * <p>The following rule codes are produced (each produces one {@link Violation}):
 * <ol>
 *   <li>{@code DEFINITION_ID_BLANK} — {@code definitionId} null or blank.</li>
 *   <li>{@code DEFINITION_VERSION_INVALID} — {@code definitionVersion < 1}.</li>
 *   <li>{@code STATE_TYPE_UNRESOLVABLE} — {@code stateType} FQN not found on classpath.</li>
 *   <li>{@code CONTRACT_UNRESOLVABLE} — {@code contract} FQN not found on classpath.</li>
 *   <li>{@code CONTRACT_NOT_ANNOTATED} — resolved contract class is not annotated with
 *       {@link WorkflowContract}.</li>
 *   <li>{@code CONTRACT_DEFINITION_ID_MISMATCH} — annotation {@code definitionId()} does not
 *       match the document's {@code definitionId}.</li>
 *   <li>{@code CONTRACT_DEFINITION_VERSION_MISMATCH} — annotation {@code definitionVersion()} does
 *       not match the document's {@code definitionVersion}.</li>
 *   <li>{@code START_PAYLOAD_TYPE_UNRESOLVABLE} — {@code startPayloadType} FQN not found.</li>
 *   <li>{@code INITIAL_STATE_MAPPER_UNKNOWN} — {@code initialStateMapper} id not registered.</li>
 *   <li>{@code SUBJECT_RESOLVER_UNKNOWN} — {@code subjectResolver} present but not registered.</li>
 *   <li>{@code INITIAL_STEP_UNKNOWN} — {@code initialStep} does not match any step id.</li>
 *   <li>{@code STEP_ID_DUPLICATE} — same step id appears more than once in the steps list.</li>
 *   <li>{@code STEP_NEXT_UNKNOWN} — a {@code next} reference does not resolve to any step id.</li>
 *   <li>{@code STEP_TARGET_SERVICE_FORMAT} — {@code target} field is blank on a
 *       {@link ServiceStep} or {@link CompensationStep}. Note: real service-target registry
 *       resolution is application-level and out of scope for this validator.</li>
 *   <li>{@code PAYLOAD_MAPPER_UNKNOWN} — service or compensation {@code payloadMapper} id not
 *       registered.</li>
 *   <li>{@code PAYLOAD_TYPE_UNRESOLVABLE} — wait-signal {@code payloadType} or human-task
 *       decision {@code payloadType} FQN not found on classpath.</li>
 *   <li>{@code STATE_REDUCER_UNKNOWN} — wait-signal {@code stateReducer} id not registered.</li>
 *   <li>{@code STATE_MUTATOR_UNKNOWN} — wait-signal timeout {@code onTimeoutMutator} or
 *       human-task due {@code onDueMutator} id not registered.</li>
 *   <li>{@code TIMER_RESOLVER_UNKNOWN} — when a timer expression starts with {@code "ref:"}, the
 *       referenced timer-resolver id is not registered.</li>
 *   <li>{@code FAIL_MESSAGE_FACTORY_UNKNOWN} — fail step {@code messageFactory} id not
 *       registered.</li>
 *   <li>{@code TASK_DECISION_NAME_DUPLICATE} — same decision {@code name} appears twice in one
 *       human task.</li>
 *   <li>{@code TASK_DECISION_APPLICATOR_UNKNOWN} — task decision {@code applicator} id not
 *       registered in the {@link dev.vertique.workflow.definition.callbacks.StateReducerRegistry}.
 *       Decision applicators share the {@code StateReducerRegistry} namespace because the function
 *       shape ({@code BiFunction<S, Object, S>}) is identical. If a future slice introduces a
 *       dedicated registry, this rule's lookup will be updated.</li>
 *   <li>{@code ASSIGNMENT_MODE_INVALID} — assignment {@code mode} is not one of
 *       {@code "user"}, {@code "role"}, {@code "queue"}, {@code "user-from-state"},
 *       {@code "role-from-state"}, {@code "queue-from-state"}.</li>
 *   <li>{@code ASSIGNMENT_VALUE_MISSING} — for literal assignment modes ({@code user},
 *       {@code role}, {@code queue}), the {@code value} field is null or blank.</li>
 *   <li>{@code ASSIGNMENT_RESOLVER_UNKNOWN} — for state-derived modes ({@code *-from-state}),
 *       the {@code resolver} id is not registered in the
 *       {@link dev.vertique.workflow.definition.callbacks.TaskAssignmentResolverRegistry}.</li>
 *   <li>{@code REMINDER_SHAPE_INVALID} — a {@link HumanTaskStep.ReminderBlock} has both
 *       {@code offsets} and {@code interval} set, or has an empty {@code offsets} list.</li>
 *   <li>{@code DUE_DATE_PARTIAL} — a {@link HumanTaskStep.DueBlock} is present but missing one or
 *       more of {@code at}, {@code onDueMutator}, {@code next}.</li>
 *   <li>{@code DECISION_DEFAULT_MISSING} — a {@link DecisionStep} has no {@code default} route
 *       (FR-WF-DEF-054).</li>
 *   <li>{@code DECISION_ROUTE_MIXED_PREDICATE} — a {@link DecisionStep.RouteEntry} has both
 *       {@code when} and {@code condition} set, or neither (FR-WF-DEF-057). The {@code routeIndex}
 *       field of the {@link Violation} identifies the offending route.</li>
 *   <li>{@code DECISION_ROUTE_TO_UNKNOWN} — a {@link DecisionStep.RouteEntry} {@code to} field
 *       does not resolve to a step id.</li>
 *   <li>{@code CONDITION_UNKNOWN} — a named-condition id is not registered in the
 *       {@link dev.vertique.workflow.definition.callbacks.NamedConditionRegistry}.</li>
 *   <li>{@code FORK_BRANCH_ID_DUPLICATE} — duplicate {@code branchId} within one fork.</li>
 *   <li>{@code FORK_BRANCH_START_UNKNOWN} — branch {@code startStep} does not resolve to a step
 *       id.</li>
 *   <li>{@code FORK_JOIN_UNKNOWN} — fork's {@code join} field does not resolve to a step id.</li>
 *   <li>{@code FORK_JOIN_NOT_JOIN_TYPE} — fork's {@code join} step resolves but the target is not
 *       a {@link JoinStep}.</li>
 *   <li>{@code FORK_BRANCH_RETRY_INVALID} — {@code BranchRetryBlock.maxAttempts < 1}, or
 *       {@code backoff} is not {@code "FIXED"} or {@code "EXPONENTIAL"} when present.</li>
 *   <li>{@code FORK_BRANCH_RACE_SAFETY_INVALID} — branch {@code raceSafety} is present but not
 *       one of {@code "NORMAL"}, {@code "CANCEL_SAFE"}, {@code "IGNORE_LATE_RESULT_SAFE"}.</li>
 *   <li>{@code JOIN_POLICY_INVALID} — {@link JoinStep#policy()} is not one of
 *       {@code "all-required"}, {@code "first-success"}, {@code "first-failure"}.</li>
 *   <li>{@code JOIN_REDUCER_UNKNOWN} — join {@code reducer} id not registered in the
 *       {@link dev.vertique.workflow.definition.callbacks.BranchResultReducerRegistry}.</li>
 *   <li>{@code COMPENSATION_FORWARD_UNKNOWN} — {@link CompensationStep#forwardStep()} does not
 *       resolve to a step id.</li>
 *   <li>{@code COMPENSATION_PAIR_MISMATCH} — the resolved forward step exists but is not a
 *       {@link ServiceStep} whose {@code compensation} field equals this compensation step's id.</li>
 *   <li>{@code SIGNAL_NAME_DUPLICATE} — same {@code signal} name on two
 *       {@link WaitSignalStep}s.</li>
 *   <li>{@code WHEN_BLANK} — a {@link DecisionStep.RouteEntry} has a non-null but blank
 *       {@code when} expression.</li>
 *   <li>{@code DECISION_ROUTE_WHEN_PARSE_ERROR} — a {@link DecisionStep.RouteEntry} {@code when}
 *       expression is syntactically invalid; the message includes the route index, the expression
 *       text, and the parse error detail. Only fired when an {@link ExpressionProfile} is
 *       provided at construction time and the document's {@code stateType} is resolvable. The
 *       validator pre-compiles the expression for early error detection; the compiler re-compiles
 *       it at emit time (CEL parsing is cheap) and can trust that any expression passing validation
 *       will compile cleanly.</li>
 * </ol>
 */
@Singleton
public final class WorkflowDefinitionDocumentValidator {

    // --- Constants ---

    private static final Set<String> VALID_ASSIGNMENT_MODES =
            Set.of("user", "role", "queue", "user-from-state", "role-from-state", "queue-from-state");
    private static final Set<String> LITERAL_ASSIGNMENT_MODES = Set.of("user", "role", "queue");
    private static final Set<String> STATE_DERIVED_ASSIGNMENT_MODES =
            Set.of("user-from-state", "role-from-state", "queue-from-state");
    private static final Set<String> VALID_JOIN_POLICIES = Set.of("all-required", "first-success", "first-failure");
    private static final Set<String> VALID_BACKOFFS = Set.of("FIXED", "EXPONENTIAL");
    private static final Set<String> VALID_RACE_SAFETY_VALUES =
            Set.of("NORMAL", "CANCEL_SAFE", "IGNORE_LATE_RESULT_SAFE");

    /** Maximum number of "did you mean?" nearest-neighbour ids to include in error messages. */
    private static final int SUGGESTION_LIMIT = 5;

    // --- State ---

    private final RegisteredIdentifierLookup lookup;

    /**
     * Optional expression profile used to pre-compile {@code when} expressions during validation.
     * When {@code null}, expression pre-compilation is skipped (only shape/blankness is checked).
     */
    @Nullable
    private final ExpressionProfile profile;

    // --- Construction ---

    /**
     * Constructs the validator with access to all named callback registries and an expression
     * profile for pre-compiling {@code when} expressions in decision routes.
     *
     * <p>When {@code profile} is non-null and the document's {@code stateType} is resolvable,
     * each non-blank {@code when} expression is pre-compiled during validation. A parse error
     * produces a {@code DECISION_ROUTE_WHEN_PARSE_ERROR} violation. The compiler re-compiles the
     * expression at emit time; the cost is paid twice, but CEL parsing is cheap and this ensures
     * structured diagnostics reach authors before the compile phase runs.
     *
     * @param lookup facade over all 10 named callback registries; non-null
     * @param profile expression profile for pre-compiling {@code when} expressions; may be null
     *     (expression pre-compilation is then skipped)
     * @throws NullPointerException if {@code lookup} is null
     */
    @Inject
    public WorkflowDefinitionDocumentValidator(RegisteredIdentifierLookup lookup, @Nullable ExpressionProfile profile) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.profile = profile;
    }

    /**
     * Convenience constructor for tests and non-DI contexts that do not need expression
     * pre-compilation.
     *
     * <p>Equivalent to {@code new WorkflowDefinitionDocumentValidator(lookup, null)}.
     *
     * @param lookup facade over all 10 named callback registries; non-null
     * @throws NullPointerException if {@code lookup} is null
     */
    public WorkflowDefinitionDocumentValidator(RegisteredIdentifierLookup lookup) {
        this(lookup, null);
    }

    // --- Public API ---

    /**
     * Validates a parsed document and returns all accumulated violations.
     *
     * <p>This method NEVER throws — even when violations are found. Callers check
     * {@link WorkflowDefinitionViolations#isEmpty()} and decide what to do.
     *
     * @param doc the document to validate; non-null
     * @return a (possibly empty) violations aggregate; never null
     * @throws NullPointerException if {@code doc} is null
     */
    public WorkflowDefinitionViolations validate(WorkflowDefinitionDocument doc) {
        Objects.requireNonNull(doc, "doc");

        List<Violation> violations = new ArrayList<>();
        String definitionId = doc.definitionId() != null ? doc.definitionId() : "";
        long definitionVersion = doc.definitionVersion();

        // --- Document-level checks ---
        checkDefinitionIdAndVersion(doc, violations);

        // Collect step ids early for forward-reference resolution.
        Set<String> stepIds = collectStepIds(doc);

        // Class-resolution checks that don't depend on step ids.
        // The resolved stateType class is retained for expression pre-compilation in decision
        // steps. When null, the stateType is unresolvable and expression checks short-circuit.
        Class<?> resolvedStateType = checkClassResolvable(
                definitionId,
                definitionVersion,
                null,
                null,
                doc.stateType(),
                "STATE_TYPE_UNRESOLVABLE",
                "`stateType`",
                violations);

        Class<?> contractClass = checkClassResolvable(
                definitionId,
                definitionVersion,
                null,
                null,
                doc.contract(),
                "CONTRACT_UNRESOLVABLE",
                "`contract`",
                violations);
        if (contractClass != null) {
            checkContractAnnotation(definitionId, definitionVersion, contractClass, doc, violations);
        }

        Class<?> resolvedStartPayloadType = checkClassResolvable(
                definitionId,
                definitionVersion,
                null,
                null,
                doc.startPayloadType(),
                "START_PAYLOAD_TYPE_UNRESOLVABLE",
                "`startPayloadType`",
                violations);

        // Registry id checks at the document level
        checkRegistryId(
                definitionId,
                definitionVersion,
                null,
                null,
                doc.initialStateMapper(),
                lookup.startStateMappers().ids(),
                "INITIAL_STATE_MAPPER_UNKNOWN",
                "`initialStateMapper`",
                violations);
        // Type-compatibility check: mapper's payloadType must match the document's startPayloadType,
        // and mapper's stateType must match the document's stateType (exact class match).
        checkInitialStateMapperTypeCompat(
                definitionId,
                definitionVersion,
                doc.initialStateMapper(),
                resolvedStateType,
                resolvedStartPayloadType,
                violations);

        if (doc.subjectResolver() != null) {
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    null,
                    null,
                    doc.subjectResolver(),
                    lookup.subjectResolvers().ids(),
                    "SUBJECT_RESOLVER_UNKNOWN",
                    "`subjectResolver`",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    null,
                    null,
                    doc.subjectResolver(),
                    resolvedStateType,
                    () -> lookup.subjectResolvers()
                            .lookup(doc.subjectResolver())
                            .stateType(),
                    "SUBJECT_RESOLVER_STATE_TYPE_MISMATCH",
                    "`subjectResolver`",
                    violations);
        }

        // Step id checks
        if (stepIds != null) {
            if (doc.initialStep() == null || doc.initialStep().isBlank() || !stepIds.contains(doc.initialStep())) {
                violations.add(new Violation(
                        blankFallback(definitionId),
                        definitionVersion,
                        null,
                        null,
                        "INITIAL_STEP_UNKNOWN",
                        "`initialStep` '" + doc.initialStep() + "' does not match any step id in this document"));
            }
        }

        // --- Step-level checks ---
        checkStepDuplicates(doc, violations);
        checkSignalDuplicates(doc, violations);

        if (doc.steps() != null) {
            for (StepNode step : doc.steps()) {
                checkStep(
                        definitionId,
                        definitionVersion,
                        step,
                        stepIds,
                        doc.steps(),
                        resolvedStateType,
                        resolvedStartPayloadType,
                        violations);
            }
        }

        return new WorkflowDefinitionViolations(violations);
    }

    /**
     * Validates a document and throws {@link WorkflowDefinitionLoadException} if any violations
     * are found.
     *
     * @param doc the document to validate; non-null
     * @throws WorkflowDefinitionLoadException if the document contains any violations
     * @throws NullPointerException if {@code doc} is null
     */
    public void validateOrThrow(WorkflowDefinitionDocument doc) {
        WorkflowDefinitionViolations result = validate(doc);
        if (!result.isEmpty()) {
            throw new WorkflowDefinitionLoadException(result);
        }
    }

    // --- Private implementation helpers ---

    /**
     * Checks that {@code definitionId} is non-blank and {@code definitionVersion >= 1}.
     */
    private static void checkDefinitionIdAndVersion(WorkflowDefinitionDocument doc, List<Violation> violations) {
        String defId = doc.definitionId();
        long defVer = doc.definitionVersion();
        String safeId = (defId == null || defId.isBlank()) ? "<unknown>" : defId;

        if (defId == null || defId.isBlank()) {
            violations.add(new Violation(
                    safeId, defVer, null, null, "DEFINITION_ID_BLANK", "`definitionId` must not be blank"));
        }
        if (defVer < 1) {
            violations.add(new Violation(
                    safeId,
                    defVer,
                    null,
                    null,
                    "DEFINITION_VERSION_INVALID",
                    "`definitionVersion` must be >= 1, but was " + defVer));
        }
    }

    /**
     * Resolves a class by FQN using the context classloader (falling back to the validator's own
     * classloader). Returns the resolved class, or {@code null} if resolution failed (in which
     * case a violation is added).
     *
     * <p>This method uses class lookup only ({@code Class.forName(name, false, cl)}); it does
     * NOT instantiate the class.
     */
    private static Class<?> checkClassResolvable(
            String definitionId,
            long definitionVersion,
            String stepId,
            Integer routeIndex,
            String fqn,
            String code,
            String fieldLabel,
            List<Violation> violations) {
        if (fqn == null || fqn.isBlank()) {
            // A blank FQN is reported by the relevant blank-id rule, not here.
            return null;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = WorkflowDefinitionDocumentValidator.class.getClassLoader();
        }
        try {
            return Class.forName(fqn, false, cl);
        } catch (ClassNotFoundException e) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    stepId,
                    routeIndex,
                    code,
                    fieldLabel + " FQN '" + fqn + "' could not be resolved on the classpath"));
            return null;
        }
    }

    /**
     * Checks that the resolved contract class is annotated with {@link WorkflowContract} and that
     * the annotation's {@code definitionId()} and {@code definitionVersion()} match the document.
     */
    private static void checkContractAnnotation(
            String definitionId,
            long definitionVersion,
            Class<?> contractClass,
            WorkflowDefinitionDocument doc,
            List<Violation> violations) {
        WorkflowContract annotation = contractClass.getAnnotation(WorkflowContract.class);
        if (annotation == null) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    null,
                    null,
                    "CONTRACT_NOT_ANNOTATED",
                    "`contract` class '" + contractClass.getName() + "' is not annotated with @WorkflowContract"));
            return;
        }
        if (!annotation.definitionId().equals(doc.definitionId())) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    null,
                    null,
                    "CONTRACT_DEFINITION_ID_MISMATCH",
                    "`contract` annotation `@WorkflowContract.definitionId()` is '"
                            + annotation.definitionId()
                            + "' but the document's `definitionId` is '"
                            + doc.definitionId() + "'"));
        }
        if (annotation.definitionVersion() != doc.definitionVersion()) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    null,
                    null,
                    "CONTRACT_DEFINITION_VERSION_MISMATCH",
                    "`contract` annotation `@WorkflowContract.definitionVersion()` is "
                            + annotation.definitionVersion()
                            + " but the document's `definitionVersion` is "
                            + doc.definitionVersion()));
        }
    }

    /**
     * Checks that a registry id is present in the supplied known ids set. When absent, adds a
     * violation with nearest-neighbour suggestions.
     */
    private void checkRegistryId(
            String definitionId,
            long definitionVersion,
            String stepId,
            Integer routeIndex,
            String idValue,
            Collection<String> knownIds,
            String code,
            String fieldLabel,
            List<Violation> violations) {
        if (idValue == null || idValue.isBlank()) {
            return;
        }
        if (!knownIds.contains(idValue)) {
            List<String> suggestions = RegisteredIdentifierLookup.nearestIds(idValue, knownIds, SUGGESTION_LIMIT);
            String msg = buildUnknownIdMessage(fieldLabel, idValue, knownIds, suggestions);
            violations.add(
                    new Violation(blankFallback(definitionId), definitionVersion, stepId, routeIndex, code, msg));
        }
    }

    /**
     * Collects all step ids from the document into a set for forward-reference checks.
     * Returns {@code null} (and adds no violations) if {@code doc.steps()} is null.
     */
    private static Set<String> collectStepIds(WorkflowDefinitionDocument doc) {
        if (doc.steps() == null) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (StepNode step : doc.steps()) {
            if (step.id() != null && !step.id().isBlank()) {
                ids.add(step.id());
            }
        }
        return ids;
    }

    /**
     * Adds {@code STEP_ID_DUPLICATE} violations for any step ids that appear more than once.
     */
    private static void checkStepDuplicates(WorkflowDefinitionDocument doc, List<Violation> violations) {
        if (doc.steps() == null) return;
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        String definitionId = blankFallback(doc.definitionId());
        long definitionVersion = doc.definitionVersion();
        for (StepNode step : doc.steps()) {
            String id = step.id();
            if (id == null || id.isBlank()) continue;
            if (!seen.add(id) && reported.add(id)) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        id,
                        null,
                        "STEP_ID_DUPLICATE",
                        "step id '" + id + "' appears more than once in the steps list"));
            }
        }
    }

    /**
     * Adds {@code SIGNAL_NAME_DUPLICATE} violations for any wait-signal steps that share a signal
     * name.
     */
    private static void checkSignalDuplicates(WorkflowDefinitionDocument doc, List<Violation> violations) {
        if (doc.steps() == null) return;
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        String definitionId = blankFallback(doc.definitionId());
        long definitionVersion = doc.definitionVersion();
        for (StepNode step : doc.steps()) {
            if (!(step instanceof WaitSignalStep ws)) continue;
            String sig = ws.signal();
            if (sig == null || sig.isBlank()) continue;
            if (!seen.add(sig) && reported.add(sig)) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        ws.id(),
                        null,
                        "SIGNAL_NAME_DUPLICATE",
                        "signal name '" + sig + "' is already used by another `wait-signal` step in this document"));
            }
        }
    }

    /**
     * Dispatches to the appropriate per-type step validator.
     *
     * @param allSteps the full list of steps in the document; used for compensation-pair checks
     * @param resolvedStateType the resolved document-level state class; {@code null} when
     *     unresolvable (type-compat and expression checks are skipped)
     * @param resolvedStartPayloadType the resolved document-level start payload class; {@code null}
     *     when unresolvable
     */
    private void checkStep(
            String definitionId,
            long definitionVersion,
            StepNode step,
            Set<String> allStepIds,
            List<StepNode> allSteps,
            @Nullable Class<?> resolvedStateType,
            @Nullable Class<?> resolvedStartPayloadType,
            List<Violation> violations) {
        switch (step) {
            case ServiceStep s ->
                checkServiceStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case CompensationStep s ->
                checkCompensationStep(
                        definitionId, definitionVersion, s, allStepIds, allSteps, resolvedStateType, violations);
            case WaitSignalStep s ->
                checkWaitSignalStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case TimerStep s ->
                checkTimerStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case DecisionStep s ->
                checkDecisionStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case HumanTaskStep s ->
                checkHumanTaskStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case ForkStep s ->
                checkForkStep(definitionId, definitionVersion, s, allStepIds, allSteps, resolvedStateType, violations);
            case JoinStep s ->
                checkJoinStep(definitionId, definitionVersion, s, allStepIds, resolvedStateType, violations);
            case FailStep s -> checkFailStep(definitionId, definitionVersion, s, resolvedStateType, violations);
            default -> {
                // CompleteStep — no additional validation needed.
            }
        }
    }

    // --- Per-type step validators ---

    /**
     * Validates a {@link ServiceStep}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkServiceStep(
            String definitionId,
            long definitionVersion,
            ServiceStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();
        checkBlankTarget(definitionId, definitionVersion, sid, step.target(), violations);
        checkRegistryId(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.payloadMapper(),
                lookup.payloadMappers().ids(),
                "PAYLOAD_MAPPER_UNKNOWN",
                "`payloadMapper`",
                violations);
        checkCallbackStateTypeCompat(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.payloadMapper(),
                resolvedStateType,
                () -> lookup.payloadMappers().lookup(step.payloadMapper()).stateType(),
                "PAYLOAD_MAPPER_STATE_TYPE_MISMATCH",
                "`payloadMapper`",
                violations);
        checkStepReference(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.next(),
                "`next`",
                "STEP_NEXT_UNKNOWN",
                allStepIds,
                violations);
    }

    /**
     * Validates a {@link CompensationStep}.
     *
     * @param allSteps the full list of steps in the document; used for compensation-pair checks
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkCompensationStep(
            String definitionId,
            long definitionVersion,
            CompensationStep step,
            Set<String> allStepIds,
            List<StepNode> allSteps,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();
        checkBlankTarget(definitionId, definitionVersion, sid, step.target(), violations);
        checkRegistryId(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.payloadMapper(),
                lookup.payloadMappers().ids(),
                "PAYLOAD_MAPPER_UNKNOWN",
                "`payloadMapper`",
                violations);
        checkCallbackStateTypeCompat(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.payloadMapper(),
                resolvedStateType,
                () -> lookup.payloadMappers().lookup(step.payloadMapper()).stateType(),
                "PAYLOAD_MAPPER_STATE_TYPE_MISMATCH",
                "`payloadMapper`",
                violations);

        // COMPENSATION_FORWARD_UNKNOWN
        if (step.forwardStep() == null || step.forwardStep().isBlank()) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    "COMPENSATION_FORWARD_UNKNOWN",
                    "compensation step '" + sid + "' `forwardStep` is blank"));
        } else if (allStepIds != null && !allStepIds.contains(step.forwardStep())) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    "COMPENSATION_FORWARD_UNKNOWN",
                    "compensation step '" + sid + "' references unknown `forwardStep` '" + step.forwardStep()
                            + "'; known step ids: " + allStepIds));
        } else if (allSteps != null) {
            // COMPENSATION_PAIR_MISMATCH — forward step resolves; verify it is a ServiceStep
            // whose `compensation` equals this compensation step's id.
            checkCompensationPairMismatch(definitionId, definitionVersion, step, allSteps, violations);
        }
    }

    /**
     * Checks that the forward step is a {@link ServiceStep} whose {@code compensation} equals this
     * compensation step's id.
     *
     * <p>Called only when the {@code forwardStep} id has already been confirmed to exist in the
     * document.
     */
    private static void checkCompensationPairMismatch(
            String definitionId,
            long definitionVersion,
            CompensationStep step,
            List<StepNode> allSteps,
            List<Violation> violations) {
        String sid = step.id();
        String forwardId = step.forwardStep();
        for (StepNode candidate : allSteps) {
            if (!forwardId.equals(candidate.id())) continue;
            if (!(candidate instanceof ServiceStep forwardService)) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        sid,
                        null,
                        "COMPENSATION_PAIR_MISMATCH",
                        "compensation step '" + sid + "' `forwardStep` '" + forwardId
                                + "' resolves but is not a `service` step"));
                return;
            }
            if (!sid.equals(forwardService.compensation())) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        sid,
                        null,
                        "COMPENSATION_PAIR_MISMATCH",
                        "compensation step '" + sid + "' `forwardStep` '" + forwardId
                                + "' is a `service` step but its `compensation` field is '"
                                + forwardService.compensation()
                                + "' — expected '" + sid + "'"));
            }
            return;
        }
    }

    /**
     * Validates a {@link WaitSignalStep}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkWaitSignalStep(
            String definitionId,
            long definitionVersion,
            WaitSignalStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();
        checkClassResolvable(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.payloadType(),
                "PAYLOAD_TYPE_UNRESOLVABLE",
                "`payloadType`",
                violations);
        checkRegistryId(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.stateReducer(),
                lookup.stateReducers().ids(),
                "STATE_REDUCER_UNKNOWN",
                "`stateReducer`",
                violations);
        checkCallbackStateTypeCompat(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.stateReducer(),
                resolvedStateType,
                () -> lookup.stateReducers().lookup(step.stateReducer()).stateType(),
                "STATE_REDUCER_STATE_TYPE_MISMATCH",
                "`stateReducer`",
                violations);
        checkStepReference(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.next(),
                "`next`",
                "STEP_NEXT_UNKNOWN",
                allStepIds,
                violations);

        // Timeout block
        if (step.timeout() != null) {
            WaitSignalStep.TimeoutBlock t = step.timeout();
            checkTimerExpression(definitionId, definitionVersion, sid, t.after(), resolvedStateType, violations);
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    t.onTimeoutMutator(),
                    lookup.stateMutators().ids(),
                    "STATE_MUTATOR_UNKNOWN",
                    "`timeout.onTimeoutMutator`",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    t.onTimeoutMutator(),
                    resolvedStateType,
                    () -> lookup.stateMutators().lookup(t.onTimeoutMutator()).stateType(),
                    "STATE_MUTATOR_STATE_TYPE_MISMATCH",
                    "`timeout.onTimeoutMutator`",
                    violations);
            checkStepReference(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    t.next(),
                    "`timeout.next`",
                    "STEP_NEXT_UNKNOWN",
                    allStepIds,
                    violations);
        }
    }

    /**
     * Validates a {@link TimerStep}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkTimerStep(
            String definitionId,
            long definitionVersion,
            TimerStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();
        checkTimerExpression(definitionId, definitionVersion, sid, step.fireAt(), resolvedStateType, violations);
        checkStepReference(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.next(),
                "`next`",
                "STEP_NEXT_UNKNOWN",
                allStepIds,
                violations);
    }

    /**
     * Validates a {@link DecisionStep} and its routes.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     *     (expression pre-compilation is skipped)
     */
    private void checkDecisionStep(
            String definitionId,
            long definitionVersion,
            DecisionStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();

        // DECISION_DEFAULT_MISSING
        if (step.defaultRoute() == null || step.defaultRoute().isBlank()) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    "DECISION_DEFAULT_MISSING",
                    "decision step '" + sid + "' has no `default` route (FR-WF-DEF-054)"));
        } else {
            checkStepReference(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    step.defaultRoute(),
                    "`default`",
                    "STEP_NEXT_UNKNOWN",
                    allStepIds,
                    violations);
        }

        if (step.routes() != null) {
            // Build the expression environment once for the whole decision step if pre-compilation
            // is enabled and the state type is resolvable.
            ExpressionEnv env = buildExpressionEnv(resolvedStateType, step);

            for (int i = 0; i < step.routes().size(); i++) {
                DecisionStep.RouteEntry route = step.routes().get(i);
                checkRouteEntry(definitionId, definitionVersion, sid, i, route, allStepIds, env, violations);
            }
        }
    }

    /**
     * Builds an {@link ExpressionEnv} for the given decision step when the state type is
     * resolvable. Collects all named condition ids referenced by the step's routes.
     *
     * @param resolvedStateType the resolved state class; {@code null} if unresolvable
     * @param step the decision step to inspect
     * @return the expression env for compilation, or {@code null} when pre-compilation is disabled
     */
    @Nullable
    private ExpressionEnv buildExpressionEnv(@Nullable Class<?> resolvedStateType, DecisionStep step) {
        if (profile == null || resolvedStateType == null) {
            return null;
        }
        Set<String> conditionIds = new HashSet<>();
        for (DecisionStep.RouteEntry route : step.routes()) {
            if (route.condition() != null && !route.condition().isBlank()) {
                conditionIds.add(route.condition());
            }
        }
        return new ExpressionEnv(resolvedStateType, conditionIds, Set.of());
    }

    /**
     * Validates a single {@link DecisionStep.RouteEntry}.
     *
     * @param env the compile-time expression environment for {@code when} pre-compilation;
     *     {@code null} when pre-compilation is disabled (stateType unresolvable or no profile)
     */
    private void checkRouteEntry(
            String definitionId,
            long definitionVersion,
            String stepId,
            int routeIndex,
            DecisionStep.RouteEntry route,
            Set<String> allStepIds,
            @Nullable ExpressionEnv env,
            List<Violation> violations) {
        boolean hasWhen = route.when() != null;
        boolean hasCondition = route.condition() != null;

        if (hasWhen && hasCondition) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    routeIndex,
                    "DECISION_ROUTE_MIXED_PREDICATE",
                    "route #" + routeIndex + " of decision step '" + stepId
                            + "' declares both `when` and `condition` — exactly one must be set (FR-WF-DEF-057)"));
        } else if (!hasWhen && !hasCondition) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    routeIndex,
                    "DECISION_ROUTE_MIXED_PREDICATE",
                    "route #" + routeIndex + " of decision step '" + stepId
                            + "' has neither `when` nor `condition` — exactly one must be set (FR-WF-DEF-057)"));
        }

        // WHEN_BLANK
        if (hasWhen && route.when().isBlank()) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    routeIndex,
                    "WHEN_BLANK",
                    "route #" + routeIndex + " of decision step '" + stepId
                            + "' has a `when` expression that is present but blank"));
        } else if (hasWhen && env != null) {
            // DECISION_ROUTE_WHEN_PARSE_ERROR — pre-compile the expression to detect syntax errors
            // before the compile phase. The compiler re-compiles at emit time; CEL parsing is cheap.
            // This check is skipped when the stateType is unresolvable (env == null) to avoid
            // compounding errors.
            checkWhenExpression(definitionId, definitionVersion, stepId, routeIndex, route.when(), env, violations);
        }

        // CONDITION_UNKNOWN + type compatibility
        if (hasCondition) {
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    stepId,
                    routeIndex,
                    route.condition(),
                    lookup.namedConditions().ids(),
                    "CONDITION_UNKNOWN",
                    "`condition`",
                    violations);
            // Note: env carries the resolvedStateType; if env is null, type check is skipped.
            if (env != null) {
                checkCallbackStateTypeCompat(
                        definitionId,
                        definitionVersion,
                        stepId,
                        routeIndex,
                        route.condition(),
                        env.stateType(),
                        () -> lookup.namedConditions().lookup(route.condition()).stateType(),
                        "CONDITION_STATE_TYPE_MISMATCH",
                        "`condition`",
                        violations);
            }
        }

        // DECISION_ROUTE_TO_UNKNOWN
        checkStepReference(
                definitionId,
                definitionVersion,
                stepId,
                routeIndex,
                route.to(),
                "`to`",
                "DECISION_ROUTE_TO_UNKNOWN",
                allStepIds,
                violations);
    }

    /**
     * Attempts to pre-compile a {@code when} expression using the injected
     * {@link ExpressionProfile}. Adds a {@code DECISION_ROUTE_WHEN_PARSE_ERROR} violation if the
     * expression fails to parse.
     *
     * <p>The compiler will re-compile the same expression at emit time. The double-compile cost is
     * intentional and negligible — CEL parsing is cheap and the validator must surface structured
     * diagnostics early.
     */
    private void checkWhenExpression(
            String definitionId,
            long definitionVersion,
            String stepId,
            int routeIndex,
            String whenSource,
            ExpressionEnv env,
            List<Violation> violations) {
        try {
            profile.compile(whenSource, env);
        } catch (ExpressionParseException e) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    stepId,
                    routeIndex,
                    "DECISION_ROUTE_WHEN_PARSE_ERROR",
                    "route #" + routeIndex + " of decision step '" + stepId + "' `when` expression '" + whenSource
                            + "': " + e.getMessage()));
        }
    }

    /**
     * Validates a {@link HumanTaskStep} and all its nested blocks.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkHumanTaskStep(
            String definitionId,
            long definitionVersion,
            HumanTaskStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();

        // Assignment
        if (step.assignment() != null) {
            checkAssignmentBlock(
                    definitionId, definitionVersion, sid, step.assignment(), resolvedStateType, violations);
        }

        // Decisions
        checkTaskDecisions(
                definitionId, definitionVersion, sid, step.decisions(), allStepIds, resolvedStateType, violations);

        // Due date
        if (step.due() != null) {
            checkDueBlock(definitionId, definitionVersion, sid, step.due(), allStepIds, resolvedStateType, violations);
        }

        // Reminders
        if (step.reminders() != null) {
            checkReminderBlock(definitionId, definitionVersion, sid, step.reminders(), violations);
        }
    }

    /**
     * Validates a {@link HumanTaskStep.AssignmentBlock}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkAssignmentBlock(
            String definitionId,
            long definitionVersion,
            String stepId,
            HumanTaskStep.AssignmentBlock assignment,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String mode = assignment.mode();
        if (mode == null || mode.isBlank() || !VALID_ASSIGNMENT_MODES.contains(mode)) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "ASSIGNMENT_MODE_INVALID",
                    "assignment `mode` '" + mode + "' is not valid; expected one of: " + VALID_ASSIGNMENT_MODES));
            return;
        }
        if (LITERAL_ASSIGNMENT_MODES.contains(mode)) {
            if (assignment.value() == null || assignment.value().isBlank()) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        stepId,
                        null,
                        "ASSIGNMENT_VALUE_MISSING",
                        "assignment mode '" + mode + "' requires a non-blank `value` field"));
            }
        } else {
            // state-derived modes require a resolver
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    assignment.resolver(),
                    lookup.taskAssignmentResolvers().ids(),
                    "ASSIGNMENT_RESOLVER_UNKNOWN",
                    "`assignment.resolver`",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    assignment.resolver(),
                    resolvedStateType,
                    () -> lookup.taskAssignmentResolvers()
                            .lookup(assignment.resolver())
                            .stateType(),
                    "TASK_ASSIGNMENT_RESOLVER_STATE_TYPE_MISMATCH",
                    "`assignment.resolver`",
                    violations);
        }
    }

    /**
     * Validates human-task decisions: checks for duplicate names, unknown applicator ids, unknown
     * payload types, unknown next step ids, and applicator stateType compatibility.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkTaskDecisions(
            String definitionId,
            long definitionVersion,
            String stepId,
            List<HumanTaskStep.TaskDecisionBlock> decisions,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        if (decisions == null || decisions.isEmpty()) return;

        Set<String> seenNames = new HashSet<>();
        Set<String> reportedNames = new HashSet<>();
        for (HumanTaskStep.TaskDecisionBlock decision : decisions) {
            String name = decision.name();
            if (name != null && !name.isBlank() && !seenNames.add(name) && reportedNames.add(name)) {
                violations.add(new Violation(
                        definitionId,
                        definitionVersion,
                        stepId,
                        null,
                        "TASK_DECISION_NAME_DUPLICATE",
                        "human-task step '" + stepId + "' has duplicate decision name '" + name + "'"));
            }
            checkClassResolvable(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    decision.payloadType(),
                    "PAYLOAD_TYPE_UNRESOLVABLE",
                    "`decisions[" + name + "].payloadType`",
                    violations);
            // Decision applicators share the StateReducerRegistry namespace.
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    decision.applicator(),
                    lookup.stateReducers().ids(),
                    "TASK_DECISION_APPLICATOR_UNKNOWN",
                    "`decisions[" + name + "].applicator`",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    decision.applicator(),
                    resolvedStateType,
                    () -> lookup.stateReducers().lookup(decision.applicator()).stateType(),
                    "TASK_DECISION_APPLICATOR_STATE_TYPE_MISMATCH",
                    "`decisions[" + name + "].applicator`",
                    violations);
            checkStepReference(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    decision.next(),
                    "`decisions[" + name + "].next`",
                    "STEP_NEXT_UNKNOWN",
                    allStepIds,
                    violations);
        }
    }

    /**
     * Validates a {@link HumanTaskStep.DueBlock}: checks all three required sub-fields.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkDueBlock(
            String definitionId,
            long definitionVersion,
            String stepId,
            HumanTaskStep.DueBlock due,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        boolean atMissing = due.at() == null || due.at().isBlank();
        boolean mutatorMissing =
                due.onDueMutator() == null || due.onDueMutator().isBlank();
        boolean nextMissing = due.next() == null || due.next().isBlank();

        if (atMissing || mutatorMissing || nextMissing) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "DUE_DATE_PARTIAL",
                    "`due` block on step '" + stepId + "' is missing required fields:"
                            + (atMissing ? " `at`" : "")
                            + (mutatorMissing ? " `onDueMutator`" : "")
                            + (nextMissing ? " `next`" : "")));
        } else {
            // Only validate sub-fields when the block is structurally complete
            checkTimerExpression(definitionId, definitionVersion, stepId, due.at(), resolvedStateType, violations);
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    due.onDueMutator(),
                    lookup.stateMutators().ids(),
                    "STATE_MUTATOR_UNKNOWN",
                    "`due.onDueMutator`",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    due.onDueMutator(),
                    resolvedStateType,
                    () -> lookup.stateMutators().lookup(due.onDueMutator()).stateType(),
                    "STATE_MUTATOR_STATE_TYPE_MISMATCH",
                    "`due.onDueMutator`",
                    violations);
            checkStepReference(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    due.next(),
                    "`due.next`",
                    "STEP_NEXT_UNKNOWN",
                    allStepIds,
                    violations);
        }
    }

    /**
     * Validates a {@link HumanTaskStep.ReminderBlock}: exactly one of {@code offsets} or
     * {@code interval} may be set; an empty {@code offsets} list is invalid.
     */
    private static void checkReminderBlock(
            String definitionId,
            long definitionVersion,
            String stepId,
            HumanTaskStep.ReminderBlock reminder,
            List<Violation> violations) {
        boolean hasOffsets = reminder.offsets() != null;
        boolean hasInterval = reminder.interval() != null;

        if (hasOffsets && hasInterval) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "REMINDER_SHAPE_INVALID",
                    "step '" + stepId
                            + "' `reminders` block has both `offsets` and `interval` set — exactly one may be set"));
        } else if (hasOffsets && reminder.offsets().isEmpty()) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "REMINDER_SHAPE_INVALID",
                    "step '" + stepId + "' `reminders.offsets` is present but empty"));
        }
        // Both null = no reminders — valid.
    }

    /**
     * Validates a {@link ForkStep}.
     *
     * @param allSteps the full list of steps in the document; used for FORK_JOIN_NOT_JOIN_TYPE check
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkForkStep(
            String definitionId,
            long definitionVersion,
            ForkStep step,
            Set<String> allStepIds,
            List<StepNode> allSteps,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();

        // FORK_JOIN_UNKNOWN
        if (step.join() == null || step.join().isBlank() || (allStepIds != null && !allStepIds.contains(step.join()))) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    "FORK_JOIN_UNKNOWN",
                    "fork step '" + sid + "' references unknown `join` step '" + step.join() + "'"));
        } else if (allSteps != null) {
            // FORK_JOIN_NOT_JOIN_TYPE — join id resolves but target is not a JoinStep
            for (StepNode candidate : allSteps) {
                if (step.join().equals(candidate.id()) && !(candidate instanceof JoinStep)) {
                    violations.add(new Violation(
                            definitionId,
                            definitionVersion,
                            sid,
                            null,
                            "FORK_JOIN_NOT_JOIN_TYPE",
                            "fork step '" + sid + "' `join` '" + step.join()
                                    + "' resolves to a step of type '"
                                    + candidate.getClass().getSimpleName()
                                    + "' — expected a `join` step"));
                    break;
                }
            }
        }

        // Retry policy
        if (step.retryPolicy() != null) {
            checkBranchRetry(definitionId, definitionVersion, sid, step.retryPolicy(), violations);
        }

        // Branches
        if (step.branches() != null) {
            Set<String> seenBranchIds = new HashSet<>();
            Set<String> reportedBranchIds = new HashSet<>();
            for (ForkStep.BranchEntry branch : step.branches()) {
                String bid = branch.branchId();
                if (bid != null && !bid.isBlank() && !seenBranchIds.add(bid) && reportedBranchIds.add(bid)) {
                    violations.add(new Violation(
                            definitionId,
                            definitionVersion,
                            sid,
                            null,
                            "FORK_BRANCH_ID_DUPLICATE",
                            "fork step '" + sid + "' has duplicate branchId '" + bid + "'"));
                }
                checkStepReference(
                        definitionId,
                        definitionVersion,
                        sid,
                        null,
                        branch.startStep(),
                        "`branches[" + bid + "].startStep`",
                        "FORK_BRANCH_START_UNKNOWN",
                        allStepIds,
                        violations);
                if (branch.raceSafety() != null && !VALID_RACE_SAFETY_VALUES.contains(branch.raceSafety())) {
                    violations.add(new Violation(
                            definitionId,
                            definitionVersion,
                            sid,
                            null,
                            "FORK_BRANCH_RACE_SAFETY_INVALID",
                            "fork branch '" + bid + "' in step '" + sid
                                    + "' has invalid `raceSafety` value '" + branch.raceSafety()
                                    + "'; expected one of: " + VALID_RACE_SAFETY_VALUES));
                }
            }
        }
    }

    /**
     * Validates fork branch retry policy.
     */
    private static void checkBranchRetry(
            String definitionId,
            long definitionVersion,
            String stepId,
            ForkStep.BranchRetryBlock retry,
            List<Violation> violations) {
        if (retry.maxAttempts() < 1) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "FORK_BRANCH_RETRY_INVALID",
                    "fork step '" + stepId + "' `retryPolicy.maxAttempts` must be >= 1, but was "
                            + retry.maxAttempts()));
        }
        if (retry.backoff() != null && !VALID_BACKOFFS.contains(retry.backoff())) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "FORK_BRANCH_RETRY_INVALID",
                    "fork step '" + stepId + "' `retryPolicy.backoff` '" + retry.backoff()
                            + "' is invalid; expected one of: " + VALID_BACKOFFS));
        }
    }

    /**
     * Validates a {@link JoinStep}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkJoinStep(
            String definitionId,
            long definitionVersion,
            JoinStep step,
            Set<String> allStepIds,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        String sid = step.id();

        if (step.policy() == null || step.policy().isBlank() || !VALID_JOIN_POLICIES.contains(step.policy())) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    sid,
                    null,
                    "JOIN_POLICY_INVALID",
                    "join step '" + sid + "' `policy` '" + step.policy() + "' is invalid; expected one of: "
                            + VALID_JOIN_POLICIES));
        }
        checkRegistryId(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.reducer(),
                lookup.branchResultReducers().ids(),
                "JOIN_REDUCER_UNKNOWN",
                "`reducer`",
                violations);
        checkCallbackStateTypeCompat(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.reducer(),
                resolvedStateType,
                () -> lookup.branchResultReducers().lookup(step.reducer()).stateType(),
                "BRANCH_RESULT_REDUCER_STATE_TYPE_MISMATCH",
                "`reducer`",
                violations);
        checkStepReference(
                definitionId,
                definitionVersion,
                sid,
                null,
                step.next(),
                "`next`",
                "STEP_NEXT_UNKNOWN",
                allStepIds,
                violations);
    }

    /**
     * Validates a {@link FailStep}.
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkFailStep(
            String definitionId,
            long definitionVersion,
            FailStep step,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        checkRegistryId(
                definitionId,
                definitionVersion,
                step.id(),
                null,
                step.messageFactory(),
                lookup.failMessageFactories().ids(),
                "FAIL_MESSAGE_FACTORY_UNKNOWN",
                "`messageFactory`",
                violations);
        checkCallbackStateTypeCompat(
                definitionId,
                definitionVersion,
                step.id(),
                null,
                step.messageFactory(),
                resolvedStateType,
                () -> lookup.failMessageFactories()
                        .lookup(step.messageFactory())
                        .stateType(),
                "FAIL_MESSAGE_FACTORY_STATE_TYPE_MISMATCH",
                "`messageFactory`",
                violations);
    }

    // --- Callback type-compatibility helpers ---

    /**
     * Checks that the callback's declared {@code stateType} matches the document's
     * {@code stateType} using exact-class equality ({@link Class#equals}, not
     * {@code isAssignableFrom}).
     *
     * <p>The check is skipped when:
     * <ul>
     *   <li>{@code callbackId} is null or blank (a separate UNKNOWN violation fires instead)</li>
     *   <li>{@code resolvedStateType} is null (the stateType FQN could not be resolved — the
     *       STATE_TYPE_UNRESOLVABLE violation already fires)</li>
     *   <li>The callback id is not registered (the UNKNOWN violation already fires)</li>
     * </ul>
     *
     * @param definitionId the document's definition id (for violation context)
     * @param definitionVersion the document's definition version
     * @param stepId the step id for the violation, or {@code null} for document-level checks
     * @param routeIndex the route index for decision-route violations, or {@code null}
     * @param callbackId the named callback id to look up; skipped if null/blank or unregistered
     * @param resolvedStateType the document's resolved state class; skipped if null
     * @param callbackStateTypeSupplier lazy supplier that returns the callback's declared
     *     stateType; only called when the id is registered
     * @param violationCode the violation code to emit on mismatch
     * @param fieldLabel human-readable field label for the error message
     * @param violations the list to accumulate violations into
     */
    private void checkCallbackStateTypeCompat(
            String definitionId,
            long definitionVersion,
            @Nullable String stepId,
            @Nullable Integer routeIndex,
            @Nullable String callbackId,
            @Nullable Class<?> resolvedStateType,
            Supplier<Class<?>> callbackStateTypeSupplier,
            String violationCode,
            String fieldLabel,
            List<Violation> violations) {
        if (callbackId == null || callbackId.isBlank()) return;
        if (resolvedStateType == null) return;
        // Attempt the lookup; if it throws, the id is not registered and the UNKNOWN violation
        // already fires — skip silently.
        Class<?> callbackStateType;
        try {
            callbackStateType = callbackStateTypeSupplier.get();
        } catch (Exception e) {
            return;
        }
        if (!resolvedStateType.equals(callbackStateType)) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    stepId,
                    routeIndex,
                    violationCode,
                    fieldLabel + " '" + callbackId + "' declares stateType '"
                            + callbackStateType.getName()
                            + "' but document stateType is '"
                            + resolvedStateType.getName() + "'"));
        }
    }

    /**
     * Checks that the initial-state mapper's declared {@code payloadType} matches the document's
     * {@code startPayloadType}, and that its {@code stateType} matches the document's
     * {@code stateType} (exact-class match).
     *
     * <p>Both checks are skipped when the mapper id is blank/unregistered or when the respective
     * resolved class is null.
     *
     * @param definitionId the document's definition id
     * @param definitionVersion the document's definition version
     * @param mapperId the initialStateMapper id declared in the document
     * @param resolvedStateType the document's resolved state class; null when unresolvable
     * @param resolvedStartPayloadType the document's resolved start payload class; null when
     *     unresolvable
     * @param violations the list to accumulate violations into
     */
    private void checkInitialStateMapperTypeCompat(
            String definitionId,
            long definitionVersion,
            @Nullable String mapperId,
            @Nullable Class<?> resolvedStateType,
            @Nullable Class<?> resolvedStartPayloadType,
            List<Violation> violations) {
        if (mapperId == null || mapperId.isBlank()) return;
        if (!lookup.startStateMappers().contains(mapperId)) return;

        try {
            var mapper = lookup.startStateMappers().lookup(mapperId);

            if (resolvedStartPayloadType != null && !resolvedStartPayloadType.equals(mapper.payloadType())) {
                violations.add(new Violation(
                        blankFallback(definitionId),
                        definitionVersion,
                        null,
                        null,
                        "INITIAL_STATE_MAPPER_PAYLOAD_TYPE_MISMATCH",
                        "`initialStateMapper` '" + mapperId + "' declares payloadType '"
                                + mapper.payloadType().getName()
                                + "' but document startPayloadType is '"
                                + resolvedStartPayloadType.getName() + "'"));
            }

            if (resolvedStateType != null && !resolvedStateType.equals(mapper.stateType())) {
                violations.add(new Violation(
                        blankFallback(definitionId),
                        definitionVersion,
                        null,
                        null,
                        "INITIAL_STATE_MAPPER_STATE_TYPE_MISMATCH",
                        "`initialStateMapper` '" + mapperId + "' declares stateType '"
                                + mapper.stateType().getName()
                                + "' but document stateType is '"
                                + resolvedStateType.getName() + "'"));
            }
        } catch (Exception e) {
            // Lookup threw — the UNKNOWN violation fires; skip silently.
        }
    }

    // --- Timer / step-reference helpers ---

    /**
     * If {@code expr} starts with {@code "ref:"}, checks that the referenced timer resolver id is
     * registered and that its {@code stateType} matches the document's {@code stateType}.
     * Non-{@code "ref:"} strings are left unvalidated at this slice — duration/instant parsing is
     * the compiler's job (Slice F).
     *
     * @param resolvedStateType the document's resolved state class; {@code null} when unresolvable
     */
    private void checkTimerExpression(
            String definitionId,
            long definitionVersion,
            String stepId,
            String expr,
            @Nullable Class<?> resolvedStateType,
            List<Violation> violations) {
        if (expr == null || expr.isBlank()) return;
        if (expr.startsWith("ref:")) {
            String resolverId = expr.substring(4).strip();
            checkRegistryId(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    resolverId,
                    lookup.timerResolvers().ids(),
                    "TIMER_RESOLVER_UNKNOWN",
                    "timer `ref` expression",
                    violations);
            checkCallbackStateTypeCompat(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    resolverId,
                    resolvedStateType,
                    () -> lookup.timerResolvers().lookup(resolverId).stateType(),
                    "TIMER_RESOLVER_STATE_TYPE_MISMATCH",
                    "timer `ref` expression",
                    violations);
        }
        // Non-ref strings: defer to Slice F compiler.
    }

    /**
     * Checks that {@code target} is non-blank; adds a {@code STEP_TARGET_SERVICE_FORMAT} violation
     * if blank. Real service-target registry resolution is application-level and out of scope.
     */
    private static void checkBlankTarget(
            String definitionId, long definitionVersion, String stepId, String target, List<Violation> violations) {
        if (target == null || target.isBlank()) {
            violations.add(new Violation(
                    definitionId,
                    definitionVersion,
                    stepId,
                    null,
                    "STEP_TARGET_SERVICE_FORMAT",
                    "step '" + stepId + "' `target` must not be blank"));
        }
    }

    /**
     * Checks that {@code stepRefId} is non-null and resolves to a known step id. Adds a violation
     * if not.
     */
    private static void checkStepReference(
            String definitionId,
            long definitionVersion,
            String stepId,
            Integer routeIndex,
            String stepRefId,
            String fieldLabel,
            String code,
            Set<String> allStepIds,
            List<Violation> violations) {
        if (stepRefId == null || stepRefId.isBlank()) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    stepId,
                    routeIndex,
                    code,
                    fieldLabel + " is blank or missing on step '" + stepId + "'"));
            return;
        }
        if (allStepIds != null && !allStepIds.contains(stepRefId)) {
            violations.add(new Violation(
                    blankFallback(definitionId),
                    definitionVersion,
                    stepId,
                    routeIndex,
                    code,
                    "step '" + stepId + "' " + fieldLabel + " '" + stepRefId
                            + "' does not resolve to any step id in this document; "
                            + "known step ids: " + allStepIds));
        }
    }

    // --- Message-building helpers ---

    /**
     * Builds a "did you mean?" error message for an unknown registry id.
     */
    private static String buildUnknownIdMessage(
            String fieldLabel, String unknownId, Collection<String> knownIds, List<String> suggestions) {
        StringBuilder msg = new StringBuilder();
        msg.append(fieldLabel)
                .append(" references unknown id '")
                .append(unknownId)
                .append("'");
        if (knownIds.isEmpty()) {
            msg.append("; no ids are registered");
        } else {
            msg.append("; known ids: ").append(knownIds);
            if (!suggestions.isEmpty()) {
                msg.append("; did you mean '").append(suggestions.get(0)).append("'?");
            }
        }
        return msg.toString();
    }

    /**
     * Returns a safe fallback for a null/blank definitionId when building violation messages.
     */
    private static String blankFallback(String definitionId) {
        return (definitionId == null || definitionId.isBlank()) ? "<unknown>" : definitionId;
    }
}
