// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultCorrelationKeyResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultPayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedCorrelationKeyResolver;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.ProductionParserFixture;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.StepNode;
import dev.vertique.workflow.definition.schema.WaitSignalStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.definition.validator.Violation;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionViolations;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the correlation key on a {@code wait-signal} document step: a document and the code-first
 * builder compile to the same wait, an absent field leaves the key out, and an unregistered resolver
 * id fails validation with its own code.
 */
class WaitSignalCorrelationKeyCompileTest {

    record State(String carrierRef) {}

    record Place(String id) {}

    record Assigned(String carrier) {}

    @WorkflowContract
    interface OrderContract {}

    private static final String STATE_FQN = State.class.getName();
    private static final String CONTRACT_FQN = OrderContract.class.getName();
    private static final String PLACE_FQN = Place.class.getName();
    private static final String ASSIGNED_FQN = Assigned.class.getName();

    private static final String START_MAPPER = "order.fromPlace";
    private static final String REDUCER = "order.applyAssigned";
    private static final String RESOLVER = "order.carrierRef";

    private RegisteredIdentifierLookup lookup;
    private WorkflowDefinitionCompiler compiler;

    /** One compilation: the builder that holds the callbacks and the plan it produced. */
    private record Compiled(WorkflowBuilder<State> builder, WorkflowPlan plan) {}

    @BeforeEach
    void setUp() {
        lookup = new RegisteredIdentifierLookup(
                new DefaultPayloadMapperRegistry(Set.of()),
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(
                        new NamedStartStateMapper<>(START_MAPPER, Place.class, State.class, p -> new State(p.id()))))),
                new DefaultStateReducerRegistry(
                        Set.of(b -> b.register(new NamedStateReducer<>(REDUCER, State.class, (s, e) -> s)))),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()),
                new DefaultCorrelationKeyResolverRegistry(Set.of(
                        b -> b.register(new NamedCorrelationKeyResolver<>(RESOLVER, State.class, State::carrierRef)))));

        WorkflowDefinitionMapperFactory mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();
        compiler = new WorkflowDefinitionCompiler(
                lookup,
                profile,
                new DecisionRouteCompiler(profile, new DefaultNamedConditionRegistry(Set.of()), mapperFactory));
    }

    private static String yaml(String waitStepExtra) {
        return """
                definitionId: order-wf
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: %s
                initialStep: wait-carrier
                steps:
                  - id: wait-carrier
                    type: wait-signal
                    signal: carrier.assigned
                    payloadType: %s
                    stateReducer: %s
                    next: done
                %s  - id: done
                    type: complete
                """.formatted(STATE_FQN, CONTRACT_FQN, PLACE_FQN, START_MAPPER, ASSIGNED_FQN, REDUCER, waitStepExtra);
    }

    private Compiled compileDocument(String yamlText) {
        WorkflowDefinitionDocument doc =
                ProductionParserFixture.parser().parse(yamlText.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML);
        WorkflowBuilder<State> builder = new WorkflowBuilder<>();
        compiler.emit(builder, State.class, OrderContract.class, doc);
        return new Compiled(builder, builder.build("order-wf", 1L, STATE_FQN));
    }

    private static Compiled buildInCode(boolean keyed) {
        WorkflowBuilder<State> builder = new WorkflowBuilder<>();
        builder.init(Place.class, p -> new State(p.id())).initialStep("wait-carrier");
        var declaration = builder.waitForSignal("wait-carrier", "carrier.assigned", Assigned.class);
        if (keyed) {
            declaration = declaration.correlateBy(State::carrierRef);
        }
        declaration.onSignal((s, e) -> s).toStepOnSignal("done").build().complete("done");
        return new Compiled(builder, builder.build("order-wf", 1L, STATE_FQN));
    }

    private static WaitSignalNode waitOf(Compiled compiled) {
        return compiled.plan().nodes().stream()
                .filter(WaitSignalNode.class::isInstance)
                .map(WaitSignalNode.class::cast)
                .filter(n -> n.stepId().equals("wait-carrier"))
                .findFirst()
                .orElseThrow();
    }

    /** Reads the wait's key callback id and applies the resolved function to {@code state}. */
    private static String keyOf(Compiled compiled, State state) {
        CallbackId id = waitOf(compiled).correlationKeyCallbackId();
        return compiled.builder()
                .callbackRegistry()
                .<State>correlationKeyResolver(id)
                .apply(state);
    }

    private static List<String> stepIds(Compiled compiled) {
        return compiled.plan().nodes().stream().map(WorkflowNode::stepId).toList();
    }

    @Test
    @DisplayName(
            "a document correlationKey compiles to the same wait as correlateBy in code, and an absent field has none")
    void documentKeyCompilesToTheSameWaitAsTheBuilder() {
        // Given: a resolver order.carrierRef over State, a document naming it on a wait-signal step,
        //        and a code definition with the same steps that declares correlateBy(State::carrierRef)
        String keyedYaml = yaml("    correlationKey: " + RESOLVER + "\n");

        // When: both are compiled
        Compiled fromDocument = compileDocument(keyedYaml);
        Compiled fromCode = buildInCode(true);

        // Then: the parsed step carries the resolver id, and both plans have the same steps and signals
        WorkflowDefinitionDocument parsed =
                ProductionParserFixture.parser().parse(keyedYaml.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML);
        assertThat(((WaitSignalStep) parsed.steps().get(0)).correlationKey()).isEqualTo(RESOLVER);
        assertThat(stepIds(fromDocument)).isEqualTo(stepIds(fromCode));
        assertThat(waitOf(fromDocument).signalName()).isEqualTo(waitOf(fromCode).signalName());

        // And: the two waits agree on step id, signal, next step and key callback id
        WaitSignalNode documentWait = waitOf(fromDocument);
        WaitSignalNode codeWait = waitOf(fromCode);
        assertThat(documentWait.stepId()).isEqualTo(codeWait.stepId());
        assertThat(documentWait.nextStepId()).isEqualTo(codeWait.nextStepId());
        assertThat(documentWait.correlationKeyCallbackId()).isNotNull().isEqualTo(codeWait.correlationKeyCallbackId());

        // And: each key callback, applied to one state, returns the same carrierRef
        State state = new State("CR-42");
        assertThat(keyOf(fromDocument, state)).isEqualTo("CR-42");
        assertThat(keyOf(fromCode, state)).isEqualTo("CR-42");

        // And: a document without the field compiles to a wait with no key, and so does a six-argument step
        assertThat(waitOf(compileDocument(yaml(""))).correlationKeyCallbackId()).isNull();
        assertThat(new WaitSignalStep("w", "sig", ASSIGNED_FQN, REDUCER, "done", null).correlationKey())
                .isNull();
    }

    @Test
    @DisplayName(
            "an unregistered correlationKey resolver id fails validation naming the step, the id and the nearest id")
    void unknownResolverIdFailsValidationWithItsCode() {
        // Given: a document whose wait-signal step names order.missing, and a registry holding order.carrierRef
        List<StepNode> steps = List.of(
                new WaitSignalStep(
                        "wait-carrier", "carrier.assigned", ASSIGNED_FQN, REDUCER, "done", null, "order.missing"),
                new CompleteStep("done"));
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "order-wf", 1L, STATE_FQN, CONTRACT_FQN, PLACE_FQN, START_MAPPER, null, "wait-carrier", steps);

        // When: the document validator runs
        WorkflowDefinitionViolations result = new WorkflowDefinitionDocumentValidator(lookup).validate(doc);

        // Then: exactly one violation, with the resolver code, names the step and the unknown id and suggests the known
        // one
        assertThat(result.toList()).hasSize(1);
        Violation violation = result.toList().get(0);
        assertThat(violation.code()).isEqualTo("CORRELATION_KEY_RESOLVER_UNKNOWN");
        assertThat(violation.stepId()).isEqualTo("wait-carrier");
        assertThat(violation.message()).contains("order.missing").contains(RESOLVER);
    }
}
