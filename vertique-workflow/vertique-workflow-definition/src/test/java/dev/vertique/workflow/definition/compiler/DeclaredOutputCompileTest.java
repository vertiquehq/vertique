// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultOutputMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultPayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedOutputMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.ProductionParserFixture;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.StepNode;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionViolations;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.plan.WorkflowPlan;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the {@code outputType} and {@code outputMapper} keys of a document: a document and the code-first builder
 * compile to the same plan, a document without them is unchanged, and each way the pair can be wrong is refused with
 * its own rule code.
 */
class DeclaredOutputCompileTest {

    record State(String id) {}

    record OtherState(String id) {}

    record Place(String id) {}

    record Receipt(String id) {}

    record OtherReceipt(String id) {}

    @WorkflowContract
    interface OrderContract {}

    private static final String STATE_FQN = State.class.getName();
    private static final String CONTRACT_FQN = OrderContract.class.getName();
    private static final String PLACE_FQN = Place.class.getName();
    private static final String RECEIPT_FQN = Receipt.class.getName();
    private static final String START_MAPPER = "order.fromPlace";
    private static final String MAPPER = "order.receipt";

    private RegisteredIdentifierLookup lookup;
    private WorkflowDefinitionCompiler compiler;

    @BeforeEach
    void setUp() {
        lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(
                        new NamedStartStateMapper<>(START_MAPPER, Place.class, State.class, p -> new State(p.id()))))),
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()),
                new dev.vertique.workflow.definition.callbacks.DefaultCorrelationKeyResolverRegistry(Set.of()),
                new DefaultOutputMapperRegistry(Set.of(
                        b -> b.register(
                                new NamedOutputMapper<>(MAPPER, State.class, Receipt.class, s -> new Receipt(s.id()))),
                        b -> b.register(new NamedOutputMapper<>(
                                "other.state", OtherState.class, Receipt.class, s -> new Receipt(s.id()))),
                        b -> b.register(new NamedOutputMapper<>(
                                "other.output", State.class, OtherReceipt.class, s -> new OtherReceipt(s.id()))))));

        WorkflowDefinitionMapperFactory mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();
        compiler = new WorkflowDefinitionCompiler(
                lookup,
                profile,
                new DecisionRouteCompiler(profile, new DefaultNamedConditionRegistry(Set.of()), mapperFactory));
    }

    private static String yaml(String outputKeys) {
        return """
                definitionId: order-wf
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: %s
                %sinitialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(STATE_FQN, CONTRACT_FQN, PLACE_FQN, START_MAPPER, outputKeys);
    }

    private static WorkflowDefinitionDocument parse(String yamlText) {
        return ProductionParserFixture.parser().parse(yamlText.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML);
    }

    private WorkflowPlan compile(WorkflowDefinitionDocument doc) {
        WorkflowBuilder<State> builder = new WorkflowBuilder<>();
        compiler.emit(builder, State.class, OrderContract.class, doc);
        return builder.build("order-wf", 1L, STATE_FQN);
    }

    private static WorkflowPlan inCode(boolean withOutput) {
        WorkflowBuilder<State> builder = new WorkflowBuilder<>();
        builder.init(Place.class, p -> new State(p.id())).initialStep("done").complete("done");
        if (withOutput) {
            builder.output(Receipt.class, s -> new Receipt(s.id()));
        }
        return builder.build("order-wf", 1L, STATE_FQN);
    }

    private static WorkflowDefinitionDocument documentWith(@Nullable String type, String mapper) {
        List<StepNode> steps = List.of(new CompleteStep("done"));
        return new WorkflowDefinitionDocument(
                "order-wf",
                1L,
                STATE_FQN,
                CONTRACT_FQN,
                PLACE_FQN,
                START_MAPPER,
                null,
                "done",
                steps,
                null,
                type,
                mapper);
    }

    private WorkflowDefinitionViolations validate(WorkflowDefinitionDocument doc) {
        return new WorkflowDefinitionDocumentValidator(lookup).validate(doc);
    }

    @Test
    @DisplayName("a document that declares an output compiles to the plan the builder produces, hash included")
    void documentAndBuilderProduceTheSamePlan() {
        // Given a document naming outputType and outputMapper, and a definition in code with the same output
        WorkflowDefinitionDocument doc = parse(yaml("outputType: " + RECEIPT_FQN + "\noutputMapper: " + MAPPER + "\n"));

        // When both are compiled
        WorkflowPlan fromDocument = compile(doc);
        WorkflowPlan fromCode = inCode(true);

        // Then the keys were read, and the plans are equal
        assertThat(doc.outputType()).isEqualTo(RECEIPT_FQN);
        assertThat(doc.outputMapper()).isEqualTo(MAPPER);
        assertThat(fromDocument.outputTypeName()).isEqualTo(RECEIPT_FQN);
        assertThat(fromDocument).isEqualTo(fromCode);
    }

    @Test
    @DisplayName("the compiled definition registers the named mapper, which maps the state to the declared output")
    void compiledDefinitionRegistersTheNamedMapper() {
        // Given a document naming the registered mapper
        WorkflowDefinitionDocument doc = parse(yaml("outputType: " + RECEIPT_FQN + "\noutputMapper: " + MAPPER + "\n"));
        WorkflowBuilder<State> builder = new WorkflowBuilder<>();

        // When it is compiled
        compiler.emit(builder, State.class, OrderContract.class, doc);
        WorkflowPlan plan = builder.build("order-wf", 1L, STATE_FQN);

        // Then the plan's mapper callback applies the registered function
        assertThat(builder.callbackRegistry()
                        .<State>outputMapper(plan.outputMapperCallbackId())
                        .apply(new State("o-1")))
                .isEqualTo(new Receipt("o-1"));
    }

    @Test
    @DisplayName("blank output keys are refused as an incomplete declaration, not left to the compiler")
    void blankKeysAreRefused() {
        assertThat(validate(documentWith("", "")).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_DECLARATION_INCOMPLETE");
        assertThat(validate(documentWith("  ", null)).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_DECLARATION_INCOMPLETE");
        assertThat(validate(documentWith(RECEIPT_FQN, " ")).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_DECLARATION_INCOMPLETE");
    }

    @Test
    @DisplayName("a document without the output keys compiles exactly as before and its plan hash is unchanged")
    void documentWithoutAnOutputIsUnchanged() {
        // Given a document without the keys
        WorkflowDefinitionDocument doc = parse(yaml(""));

        // When it is compiled
        WorkflowPlan fromDocument = compile(doc);

        // Then it has no output and equals the code plan without one
        assertThat(doc.outputType()).isNull();
        assertThat(doc.outputMapper()).isNull();
        assertThat(fromDocument.outputTypeName()).isNull();
        assertThat(fromDocument).isEqualTo(inCode(false));
        assertThat(validate(doc).toList()).isEmpty();
    }

    @Test
    @DisplayName("the previous constructors of the document declare no output")
    void previousConstructorsDeclareNoOutput() {
        WorkflowDefinitionDocument nineArguments = new WorkflowDefinitionDocument(
                "order-wf", 1L, STATE_FQN, CONTRACT_FQN, PLACE_FQN, START_MAPPER, null, "done", List.of());
        WorkflowDefinitionDocument tenArguments = new WorkflowDefinitionDocument(
                "order-wf", 1L, STATE_FQN, CONTRACT_FQN, PLACE_FQN, START_MAPPER, null, "done", List.of(), false);

        assertThat(nineArguments.outputType()).isNull();
        assertThat(nineArguments.outputMapper()).isNull();
        assertThat(tenArguments.outputType()).isNull();
        assertThat(tenArguments.activateOnDeploy()).isFalse();
    }

    @Test
    @DisplayName("a valid output declaration passes validation")
    void aValidDeclarationPasses() {
        assertThat(validate(documentWith(RECEIPT_FQN, MAPPER)).toList()).isEmpty();
    }

    @Test
    @DisplayName("an outputType without an outputMapper, or the reverse, is refused")
    void anIncompletePairIsRefused() {
        assertThat(validate(documentWith(RECEIPT_FQN, null)).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_DECLARATION_INCOMPLETE");
        assertThat(validate(documentWith(null, MAPPER)).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_DECLARATION_INCOMPLETE");
    }

    @Test
    @DisplayName("an unknown output type name or mapper id is refused with its own code")
    void unknownNamesAreRefused() {
        assertThat(validate(documentWith("no.such.Type", MAPPER)).toList())
                .extracting(v -> v.code())
                .contains("OUTPUT_TYPE_UNRESOLVABLE");
        assertThat(validate(documentWith(RECEIPT_FQN, "order.missing")).toList())
                .extracting(v -> v.code())
                .contains("OUTPUT_MAPPER_UNKNOWN");
    }

    @Test
    @DisplayName("a mapper over another state class, or producing another output type, is refused")
    void mismatchedMapperTypesAreRefused() {
        assertThat(validate(documentWith(RECEIPT_FQN, "other.state")).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_MAPPER_STATE_TYPE_MISMATCH");
        assertThat(validate(documentWith(RECEIPT_FQN, "other.output")).toList())
                .extracting(v -> v.code())
                .containsExactly("OUTPUT_MAPPER_OUTPUT_TYPE_MISMATCH");
    }
}
