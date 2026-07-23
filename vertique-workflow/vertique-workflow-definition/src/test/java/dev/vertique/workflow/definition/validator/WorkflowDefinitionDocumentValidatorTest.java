// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultPayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.FailMessageFactoryContributor;
import dev.vertique.workflow.definition.callbacks.NamedBranchResultReducer;
import dev.vertique.workflow.definition.callbacks.NamedCondition;
import dev.vertique.workflow.definition.callbacks.NamedFailMessageFactory;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateMutator;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.NamedSubjectResolver;
import dev.vertique.workflow.definition.callbacks.NamedTaskAssignmentResolver;
import dev.vertique.workflow.definition.callbacks.NamedTimerResolver;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
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
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link WorkflowDefinitionDocumentValidator}: one test per violation code, a happy-path
 * test that produces zero violations, and accumulation of multiple violations.
 *
 * <p>Each violation-code test builds a minimal in-memory {@link WorkflowDefinitionDocument},
 * runs the validator with a suitably-configured {@link RegisteredIdentifierLookup}, and asserts:
 * <ol>
 *   <li>The returned {@link WorkflowDefinitionViolations} contains at least one entry with the
 *       expected violation code.</li>
 *   <li>The violation's {@code stepId} and {@code routeIndex} are correct.</li>
 *   <li>The violation message contains an expected substring.</li>
 * </ol>
 */
class WorkflowDefinitionDocumentValidatorTest {

    // --- Test fixture types ---

    /** Minimal state type that exists on the classpath. */
    record TestState(String value) {}

    /** A DIFFERENT state type — used for type-mismatch tests. */
    record WrongState(int id) {}

    /** A DIFFERENT payload type — used for initialStateMapper payloadType mismatch tests. */
    record WrongPayload(int id) {}

    /** Minimal start payload type. */
    record TestPayload(String raw) {}

    /** Valid contract annotation that matches the test document id/version. */
    @WorkflowContract(definitionId = "test-wf", definitionVersion = 1)
    interface TestWorkflowContract {}

    /** Contract with a WRONG definitionId — used for mismatch tests. */
    @WorkflowContract(definitionId = "wrong-id", definitionVersion = 1)
    interface WrongIdContract {}

    /** Contract with a WRONG definitionVersion. */
    @WorkflowContract(definitionId = "test-wf", definitionVersion = 99)
    interface WrongVersionContract {}

    /** Contract interface with no @WorkflowContract annotation. */
    interface UnannotatedContract {}

    // --- Shared FQN constants ---

    private static final String STATE_FQN = TestState.class.getName();
    private static final String PAYLOAD_FQN = TestPayload.class.getName();
    private static final String CONTRACT_FQN = TestWorkflowContract.class.getName();
    private static final String DEF_ID = "test-wf";
    private static final long DEF_VER = 1L;

    // --- Shared registry ids used by the full happy-path lookup ---

    private static final String INIT_MAPPER_ID = "test.initMapper";
    private static final String SUBJECT_RESOLVER_ID = "test.subject";
    private static final String PAYLOAD_MAPPER_ID = "test.payloadMapper";
    private static final String STATE_REDUCER_ID = "test.stateReducer";
    private static final String STATE_MUTATOR_ID = "test.stateMutator";
    private static final String TIMER_RESOLVER_ID = "test.timerResolver";
    private static final String FAIL_FACTORY_ID = "test.failFactory";
    private static final String CONDITION_ID = "test.condition";
    private static final String BRANCH_REDUCER_ID = "test.branchReducer";
    private static final String TASK_ASSIGN_RESOLVER_ID = "test.taskAssignResolver";

    // --- Lookup configurations ---

    /** An empty lookup — all registries contain no ids. */
    private RegisteredIdentifierLookup emptyLookup;

    /** A full lookup pre-populated with the ids used by the happy-path fixture. */
    private RegisteredIdentifierLookup fullLookup;

    @BeforeEach
    void setUp() {
        emptyLookup = buildLookup(Set.of());

        fullLookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of(
                        b -> b.register(new NamedPayloadMapper<>(PAYLOAD_MAPPER_ID, TestState.class, s -> s.value())))),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of(
                        b -> b.register(new NamedStateReducer<>(STATE_REDUCER_ID, TestState.class, (s, e) -> s)))),
                new DefaultStateMutatorRegistry(
                        Set.of(b -> b.register(new NamedStateMutator<>(STATE_MUTATOR_ID, TestState.class, s -> s)))),
                new DefaultTimerResolverRegistry(Set.of(b ->
                        b.register(new NamedTimerResolver<>(TIMER_RESOLVER_ID, TestState.class, s -> Instant.EPOCH)))),
                new DefaultFailMessageFactoryRegistry(Set.of(
                        b -> b.register(new NamedFailMessageFactory<>(FAIL_FACTORY_ID, TestState.class, s -> "fail")))),
                new DefaultSubjectResolverRegistry(Set.of(b -> b.register(new NamedSubjectResolver<>(
                        SUBJECT_RESOLVER_ID, TestState.class, s -> new WorkflowSubjectRef("T", "1", null))))),
                new DefaultTaskAssignmentResolverRegistry(Set.of(b -> b.register(new NamedTaskAssignmentResolver<>(
                        TASK_ASSIGN_RESOLVER_ID, TestState.class, s -> new TaskAssignment.User("u1"))))),
                new DefaultBranchResultReducerRegistry(Set.of(
                        b -> b.register(new NamedBranchResultReducer<>(BRANCH_REDUCER_ID, TestState.class, (BiFunction<
                                        TestState, Map<String, dev.vertique.workflow.plan.BranchResult>, TestState>)
                                (s, m) -> s)))),
                new DefaultNamedConditionRegistry(
                        Set.of(b -> b.register(new NamedCondition<>(CONDITION_ID, TestState.class, s -> true)))));
    }

    // --- Helper builders ---

    /** Builds an empty lookup. */
    private static RegisteredIdentifierLookup buildLookup(Set<FailMessageFactoryContributor> ignored) {
        return new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of()),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));
    }

    /** Convenience: build a lookup with only a start-state-mapper id registered. */
    private static RegisteredIdentifierLookup lookupWithMapper(String mapperId) {
        return new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        mapperId, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));
    }

    /**
     * Builds a validator backed by {@code fullLookup} AND a real {@link CelExpressionProfile}.
     * Used for tests that need expression pre-compilation in the validator.
     */
    private WorkflowDefinitionDocumentValidator validatorWithProfile() {
        WorkflowDefinitionMapperFactory mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();
        return new WorkflowDefinitionDocumentValidator(fullLookup, profile);
    }

    /** Builds a valid minimal document (no violations) using the fullLookup ids. */
    private WorkflowDefinitionDocument validDocument() {
        // Two steps: service + complete
        List<StepNode> steps = List.of(
                new ServiceStep("svc", "inventory.reserve", PAYLOAD_MAPPER_ID, null, "done"), new CompleteStep("done"));
        return new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);
    }

    // --- Happy path ---

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("valid document produces zero violations")
        void validDocument_zeroViolations() {
            WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(fullLookup);

            WorkflowDefinitionViolations result = validator.validate(validDocument());

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("validateOrThrow does not throw when document is valid")
        void validateOrThrow_noThrowWhenValid() {
            WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(fullLookup);

            // Should not throw
            validator.validateOrThrow(validDocument());
        }

        @Test
        @DisplayName("validateOrThrow throws WorkflowDefinitionLoadException when violations exist")
        void validateOrThrow_throwsWhenViolations() {
            WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(emptyLookup);

            // Minimal doc with blank definitionId — guaranteed violation
            WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                    "", 1L, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

            assertThatThrownBy(() -> validator.validateOrThrow(doc))
                    .isInstanceOf(WorkflowDefinitionLoadException.class)
                    .satisfies(ex -> assertThat(((WorkflowDefinitionLoadException) ex)
                                    .violations()
                                    .isEmpty())
                            .isFalse());
        }
    }

    // --- Multiple-violation accumulation ---

    @Test
    @DisplayName("multiple violations in one document are all accumulated")
    void multipleViolations_allAccumulated() {
        WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(emptyLookup);

        // Document with blank id + invalid version + unresolvable state type
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "", -1L, "com.DoesNotExist", CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result = validator.validate(doc);

        assertThat(result.size()).isGreaterThanOrEqualTo(3);
        List<String> codes = result.toList().stream().map(Violation::code).toList();
        assertThat(codes).contains("DEFINITION_ID_BLANK", "DEFINITION_VERSION_INVALID", "STATE_TYPE_UNRESOLVABLE");
    }

    // --- Rule 1: DEFINITION_ID_BLANK ---

    @Test
    @DisplayName("DEFINITION_ID_BLANK — null definitionId")
    void rule01_definitionIdNull() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                null, 1L, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        assertViolationCode(result, "DEFINITION_ID_BLANK");
    }

    @Test
    @DisplayName("DEFINITION_ID_BLANK — blank definitionId")
    void rule01_definitionIdBlank() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "  ", 1L, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        assertViolationCode(result, "DEFINITION_ID_BLANK");
    }

    // --- Rule 2: DEFINITION_VERSION_INVALID ---

    @Test
    @DisplayName("DEFINITION_VERSION_INVALID — definitionVersion = 0")
    void rule02_definitionVersionZero() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, 0L, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        assertViolationCode(result, "DEFINITION_VERSION_INVALID");
    }

    @Test
    @DisplayName("DEFINITION_VERSION_INVALID — definitionVersion negative")
    void rule02_definitionVersionNegative() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, -5L, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        assertViolationCode(result, "DEFINITION_VERSION_INVALID");
        assertThat(findFirstByCode(result, "DEFINITION_VERSION_INVALID").message())
                .contains("-5");
    }

    // --- Rule 3: STATE_TYPE_UNRESOLVABLE ---

    @Test
    @DisplayName("STATE_TYPE_UNRESOLVABLE — FQN not on classpath")
    void rule03_stateTypeUnresolvable() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                "com.example.DoesNotExist",
                CONTRACT_FQN,
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "STATE_TYPE_UNRESOLVABLE");
        assertThat(v.stepId()).isNull();
        assertThat(v.message()).contains("com.example.DoesNotExist");
    }

    // --- Rule 4: CONTRACT_UNRESOLVABLE ---

    @Test
    @DisplayName("CONTRACT_UNRESOLVABLE — FQN not on classpath")
    void rule04_contractUnresolvable() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                "com.example.MissingContract",
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "CONTRACT_UNRESOLVABLE");
        assertThat(v.message()).contains("com.example.MissingContract");
    }

    // --- Rule 5: CONTRACT_NOT_ANNOTATED ---

    @Test
    @DisplayName("CONTRACT_NOT_ANNOTATED — contract class lacks @WorkflowContract")
    void rule05_contractNotAnnotated() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                UnannotatedContract.class.getName(),
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        assertViolationCode(result, "CONTRACT_NOT_ANNOTATED");
    }

    // --- Rule 6: CONTRACT_DEFINITION_ID_MISMATCH ---

    @Test
    @DisplayName("CONTRACT_DEFINITION_ID_MISMATCH — annotation definitionId != document definitionId")
    void rule06_contractDefinitionIdMismatch() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                WrongIdContract.class.getName(),
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "CONTRACT_DEFINITION_ID_MISMATCH");
        assertThat(v.message()).contains("wrong-id").contains(DEF_ID);
    }

    // --- Rule 7: CONTRACT_DEFINITION_VERSION_MISMATCH ---

    @Test
    @DisplayName("CONTRACT_DEFINITION_VERSION_MISMATCH — annotation definitionVersion != document version")
    void rule07_contractDefinitionVersionMismatch() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                WrongVersionContract.class.getName(),
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "CONTRACT_DEFINITION_VERSION_MISMATCH");
        assertThat(v.message()).contains("99").contains("1");
    }

    // --- Rule 8: START_PAYLOAD_TYPE_UNRESOLVABLE ---

    @Test
    @DisplayName("START_PAYLOAD_TYPE_UNRESOLVABLE — FQN not on classpath")
    void rule08_startPayloadTypeUnresolvable() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                CONTRACT_FQN,
                "com.example.MissingPayload",
                INIT_MAPPER_ID,
                null,
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "START_PAYLOAD_TYPE_UNRESOLVABLE");
        assertThat(v.message()).contains("com.example.MissingPayload");
    }

    // --- Rule 9: INITIAL_STATE_MAPPER_UNKNOWN ---

    @Test
    @DisplayName("INITIAL_STATE_MAPPER_UNKNOWN — id not registered")
    void rule09_initialStateMapperUnknown() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, "unknown.mapper", null, "svc", List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "INITIAL_STATE_MAPPER_UNKNOWN");
        assertThat(v.message()).contains("unknown.mapper");
    }

    // --- Rule 10: SUBJECT_RESOLVER_UNKNOWN ---

    @Test
    @DisplayName("SUBJECT_RESOLVER_UNKNOWN — id present but not registered")
    void rule10_subjectResolverUnknown() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                CONTRACT_FQN,
                PAYLOAD_FQN,
                INIT_MAPPER_ID,
                "unknown.subject",
                "svc",
                List.of());

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "SUBJECT_RESOLVER_UNKNOWN");
        assertThat(v.message()).contains("unknown.subject");
    }

    // --- Rule 11: INITIAL_STEP_UNKNOWN ---

    @Test
    @DisplayName("INITIAL_STEP_UNKNOWN — initialStep does not match any step id")
    void rule11_initialStepUnknown() {
        List<StepNode> steps = List.of(new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "ghost-step", steps);

        WorkflowDefinitionViolations result =
                new WorkflowDefinitionDocumentValidator(lookupWithMapper(INIT_MAPPER_ID)).validate(doc);

        assertViolationCode(result, "INITIAL_STEP_UNKNOWN");
    }

    // --- Rule 12: STEP_ID_DUPLICATE ---

    @Test
    @DisplayName("STEP_ID_DUPLICATE — same step id twice")
    void rule12_stepIdDuplicate() {
        List<StepNode> steps = List.of(new CompleteStep("done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "done", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(emptyLookup).validate(doc);

        Violation v = findFirstByCode(result, "STEP_ID_DUPLICATE");
        assertThat(v.stepId()).isEqualTo("done");
    }

    // --- Rule 13: STEP_NEXT_UNKNOWN ---

    @Test
    @DisplayName("STEP_NEXT_UNKNOWN — ServiceStep.next does not resolve")
    void rule13_stepNextUnknown_service() {
        List<StepNode> steps =
                List.of(new ServiceStep("svc", "target", PAYLOAD_MAPPER_ID, null, "ghost"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STEP_NEXT_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("svc");
        assertThat(v.message()).contains("ghost");
    }

    // --- Rule 14: STEP_TARGET_SERVICE_FORMAT ---

    @Test
    @DisplayName("STEP_TARGET_SERVICE_FORMAT — blank target on ServiceStep")
    void rule14_stepTargetBlankService() {
        List<StepNode> steps =
                List.of(new ServiceStep("svc", "", PAYLOAD_MAPPER_ID, null, "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STEP_TARGET_SERVICE_FORMAT");
        assertThat(v.stepId()).isEqualTo("svc");
    }

    @Test
    @DisplayName("STEP_TARGET_SERVICE_FORMAT — blank target on CompensationStep")
    void rule14_stepTargetBlankCompensation() {
        List<StepNode> steps = List.of(
                new ServiceStep("svc", "inv.reserve", PAYLOAD_MAPPER_ID, "comp", "done"),
                new CompensationStep("comp", "svc", "", PAYLOAD_MAPPER_ID),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STEP_TARGET_SERVICE_FORMAT");
        assertThat(v.stepId()).isEqualTo("comp");
    }

    // --- Rule 15: PAYLOAD_MAPPER_UNKNOWN ---

    @Test
    @DisplayName("PAYLOAD_MAPPER_UNKNOWN — ServiceStep payloadMapper not registered")
    void rule15_payloadMapperUnknown() {
        List<StepNode> steps = List.of(
                new ServiceStep("svc", "inv.reserve", "unknown.mapper", null, "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "PAYLOAD_MAPPER_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("svc");
        assertThat(v.message()).contains("unknown.mapper");
    }

    // --- Rule 16: PAYLOAD_TYPE_UNRESOLVABLE ---

    @Test
    @DisplayName("PAYLOAD_TYPE_UNRESOLVABLE — WaitSignalStep payloadType not on classpath")
    void rule16_payloadTypeUnresolvableWaitSignal() {
        List<StepNode> steps = List.of(
                new WaitSignalStep("wait", "sig", "com.Missing", STATE_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "wait", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "PAYLOAD_TYPE_UNRESOLVABLE");
        assertThat(v.stepId()).isEqualTo("wait");
    }

    // --- Rule 17: STATE_REDUCER_UNKNOWN ---

    @Test
    @DisplayName("STATE_REDUCER_UNKNOWN — WaitSignalStep stateReducer not registered")
    void rule17_stateReducerUnknown() {
        List<StepNode> steps = List.of(
                new WaitSignalStep("wait", "sig", PAYLOAD_FQN, "unknown.reducer", "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "wait", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STATE_REDUCER_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("wait");
        assertThat(v.message()).contains("unknown.reducer");
    }

    // --- Rule 18: STATE_MUTATOR_UNKNOWN ---

    @Test
    @DisplayName("STATE_MUTATOR_UNKNOWN — WaitSignalStep timeout onTimeoutMutator not registered")
    void rule18_stateMutatorUnknown_timeout() {
        WaitSignalStep.TimeoutBlock timeout = new WaitSignalStep.TimeoutBlock("PT1H", "unknown.mutator", "done");
        List<StepNode> steps = List.of(
                new WaitSignalStep("wait", "sig", PAYLOAD_FQN, STATE_REDUCER_ID, "done", timeout),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "wait", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STATE_MUTATOR_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("wait");
        assertThat(v.message()).contains("unknown.mutator");
    }

    @Test
    @DisplayName("STATE_MUTATOR_UNKNOWN — HumanTaskStep due onDueMutator not registered")
    void rule18_stateMutatorUnknown_due() {
        HumanTaskStep.DueBlock due = new HumanTaskStep.DueBlock("PT24H", "unknown.mutator", "done");
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), due, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "STATE_MUTATOR_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("task");
    }

    // --- Rule 19: TIMER_RESOLVER_UNKNOWN ---

    @Test
    @DisplayName("TIMER_RESOLVER_UNKNOWN — TimerStep fireAt ref: id not registered")
    void rule19_timerResolverUnknown_timer() {
        List<StepNode> steps =
                List.of(new TimerStep("t", "ref:unknown.timerResolver", "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "t", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "TIMER_RESOLVER_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("t");
        assertThat(v.message()).contains("unknown.timerResolver");
    }

    @Test
    @DisplayName("non-ref timer expression is NOT flagged as TIMER_RESOLVER_UNKNOWN")
    void rule19_nonRefTimerExpression_noViolation() {
        List<StepNode> steps = List.of(new TimerStep("t", "PT1H", "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "t", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        assertThat(result.toList().stream().map(Violation::code).toList()).doesNotContain("TIMER_RESOLVER_UNKNOWN");
    }

    // --- Rule 20: FAIL_MESSAGE_FACTORY_UNKNOWN ---

    @Test
    @DisplayName("FAIL_MESSAGE_FACTORY_UNKNOWN — FailStep messageFactory not registered")
    void rule20_failMessageFactoryUnknown() {
        List<StepNode> steps = List.of(new FailStep("fail", "err", "unknown.factory"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fail", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FAIL_MESSAGE_FACTORY_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("fail");
        assertThat(v.message()).contains("unknown.factory");
    }

    // --- Rule 21: TASK_DECISION_NAME_DUPLICATE ---

    @Test
    @DisplayName("TASK_DECISION_NAME_DUPLICATE — same decision name twice in one human-task")
    void rule21_taskDecisionNameDuplicate() {
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock d1 =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        HumanTaskStep.TaskDecisionBlock d2 =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(d1, d2), null, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "TASK_DECISION_NAME_DUPLICATE");
        assertThat(v.stepId()).isEqualTo("task");
        assertThat(v.message()).contains("approve");
    }

    // --- Rule 22: TASK_DECISION_APPLICATOR_UNKNOWN ---

    @Test
    @DisplayName("TASK_DECISION_APPLICATOR_UNKNOWN — applicator id not in StateReducerRegistry")
    void rule22_taskDecisionApplicatorUnknown() {
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, "unknown.applicator", "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "TASK_DECISION_APPLICATOR_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("task");
        assertThat(v.message()).contains("unknown.applicator");
    }

    // --- Rule 23: ASSIGNMENT_MODE_INVALID ---

    @Test
    @DisplayName("ASSIGNMENT_MODE_INVALID — unknown mode value")
    void rule23_assignmentModeInvalid() {
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("team", "legal", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "ASSIGNMENT_MODE_INVALID");
        assertThat(v.stepId()).isEqualTo("task");
        assertThat(v.message()).contains("team");
    }

    // --- Rule 24: ASSIGNMENT_VALUE_MISSING ---

    @Test
    @DisplayName("ASSIGNMENT_VALUE_MISSING — literal mode without value")
    void rule24_assignmentValueMissing() {
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", null, null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "ASSIGNMENT_VALUE_MISSING");
        assertThat(v.stepId()).isEqualTo("task");
    }

    // --- Rule 25: ASSIGNMENT_RESOLVER_UNKNOWN ---

    @Test
    @DisplayName("ASSIGNMENT_RESOLVER_UNKNOWN — state-derived mode with unknown resolver")
    void rule25_assignmentResolverUnknown() {
        HumanTaskStep.AssignmentBlock assignment =
                new HumanTaskStep.AssignmentBlock("role-from-state", null, "unknown.resolver");
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "ASSIGNMENT_RESOLVER_UNKNOWN");
        assertThat(v.message()).contains("unknown.resolver");
    }

    // --- Rule 26: REMINDER_SHAPE_INVALID ---

    @Test
    @DisplayName("REMINDER_SHAPE_INVALID — both offsets and interval set")
    void rule26_reminderBothSet() {
        HumanTaskStep.ReminderBlock reminder = new HumanTaskStep.ReminderBlock(List.of("-PT24H"), "PT6H");
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, reminder, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        assertViolationCode(result, "REMINDER_SHAPE_INVALID");
    }

    @Test
    @DisplayName("REMINDER_SHAPE_INVALID — offsets list is empty")
    void rule26_reminderEmptyOffsets() {
        HumanTaskStep.ReminderBlock reminder = new HumanTaskStep.ReminderBlock(List.of(), null);
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), null, reminder, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        assertViolationCode(result, "REMINDER_SHAPE_INVALID");
    }

    // --- Rule 27: DUE_DATE_PARTIAL ---

    @Test
    @DisplayName("DUE_DATE_PARTIAL — due block missing onDueMutator")
    void rule27_dueDatePartial() {
        // DueBlock(at, onDueMutator, next) — pass null for onDueMutator
        HumanTaskStep.DueBlock due = new HumanTaskStep.DueBlock("PT24H", null, "done");
        HumanTaskStep.AssignmentBlock assignment = new HumanTaskStep.AssignmentBlock("role", "editor", null);
        HumanTaskStep.TaskDecisionBlock decision =
                new HumanTaskStep.TaskDecisionBlock("approve", PAYLOAD_FQN, STATE_REDUCER_ID, "done");
        List<StepNode> steps = List.of(
                new HumanTaskStep("task", "approval", assignment, List.of(decision), due, null, null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "task", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "DUE_DATE_PARTIAL");
        assertThat(v.stepId()).isEqualTo("task");
        assertThat(v.message()).contains("onDueMutator");
    }

    // --- Rule 28: DECISION_DEFAULT_MISSING ---

    @Test
    @DisplayName("DECISION_DEFAULT_MISSING — defaultRoute is null")
    void rule28_decisionDefaultMissing() {
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(), null), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "DECISION_DEFAULT_MISSING");
        assertThat(v.stepId()).isEqualTo("decide");
    }

    // --- Rule 29: DECISION_ROUTE_MIXED_PREDICATE ---

    @Test
    @DisplayName("DECISION_ROUTE_MIXED_PREDICATE — both when and condition set")
    void rule29_bothWhenAndConditionSet() {
        DecisionStep.RouteEntry route = new DecisionStep.RouteEntry("state.x == 1", CONDITION_ID, "done");
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(route), "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "DECISION_ROUTE_MIXED_PREDICATE");
        assertThat(v.stepId()).isEqualTo("decide");
        assertThat(v.routeIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("DECISION_ROUTE_MIXED_PREDICATE — neither when nor condition set")
    void rule29_neitherWhenNorConditionSet() {
        DecisionStep.RouteEntry route = new DecisionStep.RouteEntry(null, null, "done");
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(route), "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "DECISION_ROUTE_MIXED_PREDICATE");
        assertThat(v.routeIndex()).isEqualTo(0);
    }

    // --- Rule 30: DECISION_ROUTE_TO_UNKNOWN ---

    @Test
    @DisplayName("DECISION_ROUTE_TO_UNKNOWN — route to unknown step id")
    void rule30_decisionRouteToUnknown() {
        DecisionStep.RouteEntry route = new DecisionStep.RouteEntry("state.x == 1", null, "ghost");
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(route), "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "DECISION_ROUTE_TO_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("decide");
        assertThat(v.routeIndex()).isEqualTo(0);
    }

    // --- Rule 31: CONDITION_UNKNOWN ---

    @Test
    @DisplayName("CONDITION_UNKNOWN — condition id not registered")
    void rule31_conditionUnknown() {
        DecisionStep.RouteEntry route = new DecisionStep.RouteEntry(null, "unknown.condition", "done");
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(route), "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "CONDITION_UNKNOWN");
        assertThat(v.message()).contains("unknown.condition");
    }

    // --- Rule 32: FORK_BRANCH_ID_DUPLICATE ---

    @Test
    @DisplayName("FORK_BRANCH_ID_DUPLICATE — duplicate branchId in one fork")
    void rule32_forkBranchIdDuplicate() {
        List<ForkStep.BranchEntry> branches =
                List.of(new ForkStep.BranchEntry("b1", "done", null), new ForkStep.BranchEntry("b1", "done", null));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", null),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_BRANCH_ID_DUPLICATE");
        assertThat(v.stepId()).isEqualTo("fork");
        assertThat(v.message()).contains("b1");
    }

    // --- Rule 33: FORK_BRANCH_START_UNKNOWN ---

    @Test
    @DisplayName("FORK_BRANCH_START_UNKNOWN — branch startStep does not resolve")
    void rule33_forkBranchStartUnknown() {
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "ghost-step", null));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", null),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_BRANCH_START_UNKNOWN");
        assertThat(v.message()).contains("ghost-step");
    }

    // --- Rule 34: FORK_JOIN_UNKNOWN ---

    @Test
    @DisplayName("FORK_JOIN_UNKNOWN — fork join does not resolve to any step id")
    void rule34_forkJoinUnknown() {
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", null));
        List<StepNode> steps = List.of(new ForkStep("fork", branches, "ghost-join", null), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_JOIN_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("fork");
    }

    // --- Rule 35: FORK_JOIN_NOT_JOIN_TYPE ---

    @Test
    @DisplayName("FORK_JOIN_NOT_JOIN_TYPE — fork join resolves but target is not a JoinStep")
    void rule35_forkJoinNotJoinType() {
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", null));
        // 'done' is a CompleteStep, not a JoinStep
        List<StepNode> steps = List.of(new ForkStep("fork", branches, "done", null), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_JOIN_NOT_JOIN_TYPE");
        assertThat(v.stepId()).isEqualTo("fork");
        assertThat(v.message()).contains("done");
    }

    // --- Rule 36: FORK_BRANCH_RETRY_INVALID ---

    @Test
    @DisplayName("FORK_BRANCH_RETRY_INVALID — maxAttempts < 1")
    void rule36_forkBranchRetryMaxAttempts() {
        ForkStep.BranchRetryBlock retry = new ForkStep.BranchRetryBlock(0, "PT5S", "EXPONENTIAL");
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", null));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", retry),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_BRANCH_RETRY_INVALID");
        assertThat(v.stepId()).isEqualTo("fork");
        assertThat(v.message()).contains("maxAttempts");
    }

    @Test
    @DisplayName("FORK_BRANCH_RETRY_INVALID — backoff not in {FIXED, EXPONENTIAL}")
    void rule36_forkBranchRetryBackoff() {
        ForkStep.BranchRetryBlock retry = new ForkStep.BranchRetryBlock(3, "PT5S", "LINEAR");
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", null));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", retry),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_BRANCH_RETRY_INVALID");
        assertThat(v.message()).contains("LINEAR");
    }

    // --- Rule 37: FORK_BRANCH_RACE_SAFETY_INVALID ---

    @Test
    @DisplayName("FORK_BRANCH_RACE_SAFETY_INVALID — raceSafety not in allowed values")
    void rule37_forkBranchRaceSafetyInvalid() {
        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", "UNSAFE"));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", null),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "FORK_BRANCH_RACE_SAFETY_INVALID");
        assertThat(v.message()).contains("UNSAFE");
    }

    // --- Rule 38: JOIN_POLICY_INVALID ---

    @Test
    @DisplayName("JOIN_POLICY_INVALID — policy not in allowed values")
    void rule38_joinPolicyInvalid() {
        List<StepNode> steps =
                List.of(new JoinStep("join", "quorum", BRANCH_REDUCER_ID, "done", null), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "join", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "JOIN_POLICY_INVALID");
        assertThat(v.stepId()).isEqualTo("join");
        assertThat(v.message()).contains("quorum");
    }

    // --- Rule 39: JOIN_REDUCER_UNKNOWN ---

    @Test
    @DisplayName("JOIN_REDUCER_UNKNOWN — reducer id not registered")
    void rule39_joinReducerUnknown() {
        List<StepNode> steps = List.of(
                new JoinStep("join", "all-required", "unknown.reducer", "done", null), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "join", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "JOIN_REDUCER_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("join");
        assertThat(v.message()).contains("unknown.reducer");
    }

    // --- Rule 40: COMPENSATION_FORWARD_UNKNOWN ---

    @Test
    @DisplayName("COMPENSATION_FORWARD_UNKNOWN — forwardStep does not resolve")
    void rule40_compensationForwardUnknown() {
        List<StepNode> steps = List.of(new CompensationStep("comp", "ghost-step", "inv.release", PAYLOAD_MAPPER_ID));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "comp", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "COMPENSATION_FORWARD_UNKNOWN");
        assertThat(v.stepId()).isEqualTo("comp");
        assertThat(v.message()).contains("ghost-step");
    }

    // --- Rule 41: COMPENSATION_PAIR_MISMATCH ---

    @Test
    @DisplayName("COMPENSATION_PAIR_MISMATCH — forward step is not a ServiceStep")
    void rule41_compensationForwardNotServiceStep() {
        List<StepNode> steps = List.of(
                new CompleteStep("done"), new CompensationStep("comp", "done", "inv.release", PAYLOAD_MAPPER_ID));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "comp", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "COMPENSATION_PAIR_MISMATCH");
        assertThat(v.stepId()).isEqualTo("comp");
    }

    @Test
    @DisplayName("COMPENSATION_PAIR_MISMATCH — forward ServiceStep compensation != this comp step id")
    void rule41_compensationPairWrongId() {
        List<StepNode> steps = List.of(
                // ServiceStep's compensation is "other-comp", not "comp"
                new ServiceStep("svc", "inv.reserve", PAYLOAD_MAPPER_ID, "other-comp", "done"),
                new CompensationStep("comp", "svc", "inv.release", PAYLOAD_MAPPER_ID),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "COMPENSATION_PAIR_MISMATCH");
        assertThat(v.stepId()).isEqualTo("comp");
        assertThat(v.message()).contains("other-comp");
    }

    // --- Named-callback type-compatibility rules ---

    /** Builds a lookup with only the initMapper registered with WrongState as stateType. */
    private static RegisteredIdentifierLookup lookupWithWrongStateMapper() {
        return new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, WrongState.class, p -> new WrongState(0))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));
    }

    /** Builds a lookup with the initMapper registered with WrongPayload as payloadType. */
    private static RegisteredIdentifierLookup lookupWithWrongPayloadMapper() {
        return new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, WrongPayload.class, TestState.class, p -> new TestState("x"))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));
    }

    @Test
    @DisplayName("INITIAL_STATE_MAPPER_STATE_TYPE_MISMATCH — mapper stateType != document stateType")
    void typeCompat_initialStateMapperStateMismatch() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result =
                new WorkflowDefinitionDocumentValidator(lookupWithWrongStateMapper()).validate(doc);

        Violation v = findFirstByCode(result, "INITIAL_STATE_MAPPER_STATE_TYPE_MISMATCH");
        assertThat(v.message()).contains(STATE_FQN).contains(WrongState.class.getName());
    }

    @Test
    @DisplayName("INITIAL_STATE_MAPPER_PAYLOAD_TYPE_MISMATCH — mapper payloadType != document startPayloadType")
    void typeCompat_initialStateMapperPayloadMismatch() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", List.of());

        WorkflowDefinitionViolations result =
                new WorkflowDefinitionDocumentValidator(lookupWithWrongPayloadMapper()).validate(doc);

        Violation v = findFirstByCode(result, "INITIAL_STATE_MAPPER_PAYLOAD_TYPE_MISMATCH");
        assertThat(v.message()).contains(PAYLOAD_FQN).contains(WrongPayload.class.getName());
    }

    @Test
    @DisplayName("PAYLOAD_MAPPER_STATE_TYPE_MISMATCH — mapper stateType != document stateType on ServiceStep")
    void typeCompat_payloadMapperStateMismatch() {
        // Register a payloadMapper with WrongState
        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of(
                        b -> b.register(new NamedPayloadMapper<>(PAYLOAD_MAPPER_ID, WrongState.class, s -> "x")))),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        List<StepNode> steps = List.of(
                new ServiceStep("svc", "inv.reserve", PAYLOAD_MAPPER_ID, null, "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "svc", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(lookup).validate(doc);

        Violation v = findFirstByCode(result, "PAYLOAD_MAPPER_STATE_TYPE_MISMATCH");
        assertThat(v.stepId()).isEqualTo("svc");
        assertThat(v.message()).contains(STATE_FQN).contains(WrongState.class.getName());
    }

    @Test
    @DisplayName("STATE_REDUCER_STATE_TYPE_MISMATCH — stateReducer stateType != document stateType")
    void typeCompat_stateReducerStateMismatch() {
        // Register a stateReducer with WrongState
        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of(
                        b -> b.register(new NamedStateReducer<>(STATE_REDUCER_ID, WrongState.class, (s, e) -> s)))),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        List<StepNode> steps = List.of(
                new WaitSignalStep("wait", "sig", PAYLOAD_FQN, STATE_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "wait", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(lookup).validate(doc);

        Violation v = findFirstByCode(result, "STATE_REDUCER_STATE_TYPE_MISMATCH");
        assertThat(v.stepId()).isEqualTo("wait");
        assertThat(v.message()).contains(STATE_FQN).contains(WrongState.class.getName());
    }

    @Test
    @DisplayName("FAIL_MESSAGE_FACTORY_STATE_TYPE_MISMATCH — factory stateType != document stateType")
    void typeCompat_failMessageFactoryStateMismatch() {
        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of(
                        b -> b.register(new NamedFailMessageFactory<>(FAIL_FACTORY_ID, WrongState.class, s -> "x")))),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        List<StepNode> steps = List.of(new FailStep("fail", "err", FAIL_FACTORY_ID));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fail", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(lookup).validate(doc);

        Violation v = findFirstByCode(result, "FAIL_MESSAGE_FACTORY_STATE_TYPE_MISMATCH");
        assertThat(v.stepId()).isEqualTo("fail");
        assertThat(v.message()).contains(STATE_FQN).contains(WrongState.class.getName());
    }

    @Test
    @DisplayName("JOIN_REDUCER_STATE_TYPE_MISMATCH — join reducer stateType != document stateType")
    void typeCompat_branchResultReducerStateMismatch() {
        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        INIT_MAPPER_ID, TestPayload.class, TestState.class, p -> new TestState(p.raw()))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of(
                        b -> b.register(new NamedBranchResultReducer<>(BRANCH_REDUCER_ID, WrongState.class, (BiFunction<
                                        WrongState, Map<String, dev.vertique.workflow.plan.BranchResult>, WrongState>)
                                (s, m) -> s)))),
                new DefaultNamedConditionRegistry(Set.of()));

        List<ForkStep.BranchEntry> branches = List.of(new ForkStep.BranchEntry("b1", "done", null));
        List<StepNode> steps = List.of(
                new ForkStep("fork", branches, "join", null),
                new JoinStep("join", "all-required", BRANCH_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "fork", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(lookup).validate(doc);

        Violation v = findFirstByCode(result, "BRANCH_RESULT_REDUCER_STATE_TYPE_MISMATCH");
        assertThat(v.stepId()).isEqualTo("join");
        assertThat(v.message()).contains(STATE_FQN).contains(WrongState.class.getName());
    }

    // --- Rule 43: WHEN_BLANK (already present in the code) and DECISION_ROUTE_WHEN_PARSE_ERROR ---

    @Test
    @DisplayName("DECISION_ROUTE_WHEN_PARSE_ERROR — when expression fails CEL compilation")
    void ruleNew_decisionRouteWhenParseError() {
        // "@@@@" is not valid CEL syntax
        DecisionStep.RouteEntry route = new DecisionStep.RouteEntry("@@@@ not valid CEL", null, "done");
        List<StepNode> steps = List.of(new DecisionStep("decide", List.of(route), "done"), new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "decide", steps);

        WorkflowDefinitionViolations result = validatorWithProfile().validate(doc);

        Violation v = findFirstByCode(result, "DECISION_ROUTE_WHEN_PARSE_ERROR");
        assertThat(v.stepId()).isEqualTo("decide");
        assertThat(v.routeIndex()).isEqualTo(0);
        assertThat(v.message()).contains("decide");
    }

    // --- Rule 42: SIGNAL_NAME_DUPLICATE ---

    @Test
    @DisplayName("SIGNAL_NAME_DUPLICATE — same signal name on two WaitSignalSteps")
    void rule42_signalNameDuplicate() {
        List<StepNode> steps = List.of(
                new WaitSignalStep("wait1", "inv.reserved", PAYLOAD_FQN, STATE_REDUCER_ID, "done", null),
                new WaitSignalStep("wait2", "inv.reserved", PAYLOAD_FQN, STATE_REDUCER_ID, "done", null),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID, DEF_VER, STATE_FQN, CONTRACT_FQN, PAYLOAD_FQN, INIT_MAPPER_ID, null, "wait1", steps);

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "SIGNAL_NAME_DUPLICATE");
        assertThat(v.message()).contains("inv.reserved");
    }

    // --- Suggestion message tests ---

    @Test
    @DisplayName("Unknown id violation message includes Levenshtein suggestions")
    void unknownIdViolation_includesSuggestions() {
        // Use a lookup with "test.initMapper" registered; query with "test.initMaper" (typo)
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                DEF_ID,
                DEF_VER,
                STATE_FQN,
                CONTRACT_FQN,
                PAYLOAD_FQN,
                "test.initMaper",
                null,
                "done",
                List.of(new CompleteStep("done")));

        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(fullLookup).validate(doc);

        Violation v = findFirstByCode(result, "INITIAL_STATE_MAPPER_UNKNOWN");
        assertThat(v.message())
                .as("message should suggest the nearest registered id")
                .contains(INIT_MAPPER_ID);
    }

    // --- Assertion helpers ---

    /** Asserts that the violations aggregate contains at least one entry with the expected code. */
    private static void assertViolationCode(WorkflowDefinitionViolations violations, String expectedCode) {
        assertThat(violations.toList())
                .as(
                        "expected at least one violation with code '%s' but found: %s",
                        expectedCode,
                        violations.toList().stream().map(Violation::code).toList())
                .anyMatch(v -> expectedCode.equals(v.code()));
    }

    /** Returns the first violation with the given code, or fails the test if none is found. */
    private static Violation findFirstByCode(WorkflowDefinitionViolations violations, String code) {
        return violations.toList().stream()
                .filter(v -> code.equals(v.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected violation with code '" + code + "' but found: "
                        + violations.toList().stream().map(Violation::code).toList()));
    }
}
