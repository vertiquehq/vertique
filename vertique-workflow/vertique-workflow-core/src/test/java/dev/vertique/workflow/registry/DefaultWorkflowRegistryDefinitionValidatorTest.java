// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WorkflowDefinitionValidator} hook of {@link DefaultWorkflowRegistry}: the
 * validators run after plan validation and before the definition is published, a throwing
 * validator fails the registration without leaving the key resolvable, and the callback receives
 * the plan and the start payload type only.
 */
class DefaultWorkflowRegistryDefinitionValidatorTest {

    // --- Fixture types ---

    record State(String id) {}

    record StartPayload(String id) {}

    record ApprovePayload(boolean approved) {}

    record RejectPayload(String reason) {}

    @WorkflowContract
    interface ValidatedSagaContract {}

    @WorkflowContract
    interface BrokenSagaContract {}

    @WorkflowContract
    interface ReviewedSagaContract {}

    /** Validator that records every candidate it receives and can run an extra check. */
    static final class RecordingValidator implements WorkflowDefinitionValidator {

        final List<RegisteredWorkflowDefinition> received = new ArrayList<>();
        private final Consumer<RegisteredWorkflowDefinition> check;

        RecordingValidator() {
            this(definition -> {});
        }

        RecordingValidator(Consumer<RegisteredWorkflowDefinition> check) {
            this.check = check;
        }

        @Override
        public void validate(RegisteredWorkflowDefinition definition) {
            received.add(definition);
            check.accept(definition);
        }
    }

    /** Fixture definitions built through the DSL. */
    static final class Definitions {

        private Definitions() {}

        static WorkflowDefinition<State, ValidatedSagaContract> valid() {
            return definition("validated-saga", 1L, ValidatedSagaContract.class, wf -> wf.init(
                            StartPayload.class, cmd -> new State(cmd.id()))
                    .initialStep("done")
                    .complete("done"));
        }

        /** Builds, but its first step names a next step that does not exist. */
        static WorkflowDefinition<State, BrokenSagaContract> brokenGraph() {
            return definition("broken-saga", 1L, BrokenSagaContract.class, wf -> wf.init(
                            StartPayload.class, cmd -> new State(cmd.id()))
                    .initialStep("dispatch")
                    .dispatch("dispatch", "svc", s -> new Object(), "nonexistent")
                    .complete("done"));
        }

        /** Start payload plus one human task with two decisions. */
        static WorkflowDefinition<State, ReviewedSagaContract> humanTask() {
            return definition("reviewed-saga", 3L, ReviewedSagaContract.class, wf -> {
                wf.init(StartPayload.class, cmd -> new State(cmd.id())).initialStep("start");
                wf.dispatch("start", "svc", s -> new Object(), "review")
                        .task("review")
                        .assignToRole("compliance")
                        .decision("approve", ApprovePayload.class)
                        .onDecision((s, p) -> s)
                        .toStep("ship")
                        .decision("reject", RejectPayload.class)
                        .onDecision((s, p) -> s)
                        .toStep("notify")
                        .build()
                        .complete("ship")
                        .complete("notify");
            });
        }

        private static <C> WorkflowDefinition<State, C> definition(
                String id, long version, Class<C> contract, Consumer<WorkflowBuilder<State>> body) {
            return new WorkflowDefinition<>() {
                @Override
                public Class<C> contract() {
                    return contract;
                }

                @Override
                public Class<State> stateType() {
                    return State.class;
                }

                @Override
                public String definitionId() {
                    return id;
                }

                @Override
                public long definitionVersion() {
                    return version;
                }

                @Override
                public void define(WorkflowBuilder<State> wf) {
                    body.accept(wf);
                }
            };
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("validators run after plan validation and before the definition is published")
    void validatorsRunAfterPlanValidationAndBeforePublish() {
        // Given: a validator that asserts, from inside the callback, that the candidate is not yet resolvable
        AtomicReference<WorkflowRegistry> registryRef = new AtomicReference<>();
        RecordingValidator recording = new RecordingValidator(definition -> assertThatThrownBy(() -> registryRef
                        .get()
                        .resolve(new DefinitionKey(
                                WorkflowScopes.DEFAULT,
                                definition.plan().definitionId(),
                                definition.plan().definitionVersion())))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class));
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(recording));
        registryRef.set(registry);

        // When: a valid definition is registered
        registry.register(Definitions.valid());

        // Then: the validator ran once, saw the candidate unpublished, and the definition resolves afterwards
        assertThat(recording.received).hasSize(1);
        assertThat(recording.received.get(0).plan().definitionId()).isEqualTo("validated-saga");
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "validated-saga", 1L))
                        .plan()
                        .definitionId())
                .isEqualTo("validated-saga");

        // Given: a second registry and a definition whose plan graph is broken
        RecordingValidator neverCalled = new RecordingValidator();
        DefaultWorkflowRegistry second = new DefaultWorkflowRegistry(null, Set.of(neverCalled));

        // When / Then: graph validation rejects it first, so the validator is never invoked
        assertThatThrownBy(() -> second.register(Definitions.brokenGraph()))
                .isInstanceOf(WorkflowDefinitionException.class);
        assertThat(neverCalled.received).isEmpty();
    }

    @Test
    @DisplayName("a throwing validator fails the registration and leaves the key unresolvable")
    void throwingValidatorFailsRegistrationAndLeavesTheKeyUnresolvable() {
        // Given: a validator that throws until it is switched to pass
        IllegalStateException boom = new IllegalStateException("boom");
        AtomicBoolean failing = new AtomicBoolean(true);
        RecordingValidator validator = new RecordingValidator(definition -> {
            if (failing.get()) {
                throw boom;
            }
        });
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(validator));
        WorkflowDefinition<State, ValidatedSagaContract> definition = Definitions.valid();

        // When / Then: the very same exception propagates, unwrapped
        assertThatThrownBy(() -> registry.register(definition)).isSameAs(boom);

        // Then: nothing about the definition is resolvable
        assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "validated-saga", 1L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThat(registry.codeRegistrations()).isEmpty();
        assertThat(registry.codeDefinitions()).isEmpty();

        // When: the validator passes and the same definition is registered again
        failing.set(false);
        registry.register(definition);

        // Then: the registration succeeds, so the failed attempt left no half-registered state
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "validated-saga", 1L))
                        .plan()
                        .definitionVersion())
                .isEqualTo(1L);
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "validated-saga", 1L)))
                .isNotNull();
        assertThat(registry.codeRegistrations())
                .singleElement()
                .satisfies(registration -> assertThat(registration.definition()).isSameAs(definition));
    }

    @Test
    @DisplayName("the validator receives the plan and the start payload type and nothing else")
    void validatorReceivesThePlanAndTheStartPayloadTypeWithoutCallbacks() {
        // Given: a registry with a recording validator and a definition with a start payload and a two-decision task
        RecordingValidator recording = new RecordingValidator();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(recording));

        // When: the definition is registered
        registry.register(Definitions.humanTask());

        // Then: the record carries the identity, the start type and both decision payload types
        assertThat(recording.received).hasSize(1);
        RegisteredWorkflowDefinition received = recording.received.get(0);
        WorkflowPlan plan = received.plan();
        assertThat(plan.definitionId()).isEqualTo("reviewed-saga");
        assertThat(plan.definitionVersion()).isEqualTo(3L);
        assertThat(received.startPayloadType()).isEqualTo(StartPayload.class);

        HumanTaskNode review = plan.nodes().stream()
                .filter(HumanTaskNode.class::isInstance)
                .map(HumanTaskNode.class::cast)
                .filter(node -> node.stepId().equals("review"))
                .findFirst()
                .orElseThrow();
        assertThat(review.decisions())
                .extracting(HumanTaskNode.TaskDecision::name, HumanTaskNode.TaskDecision::payloadTypeName)
                .containsExactly(
                        tuple("approve", ApprovePayload.class.getName()),
                        tuple("reject", RejectPayload.class.getName()));

        // Then: it is the plan the registry went on to publish
        assertThat(plan)
                .isEqualTo(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "reviewed-saga", 3L))
                        .plan());
        assertThat(plan.nodes()).extracting(WorkflowNode::stepId).contains("start", "review", "ship", "notify");

        // Then: the record has exactly the plan and the start payload type as components, no callbacks
        RecordComponent[] components = RegisteredWorkflowDefinition.class.getRecordComponents();
        assertThat(components)
                .extracting(RecordComponent::getName, RecordComponent::getType)
                .containsExactly(tuple("plan", WorkflowPlan.class), tuple("startPayloadType", Class.class));
    }

    @Test
    @DisplayName("every contributed validator is invoked once per registration")
    void everyContributedValidatorIsInvokedOncePerRegistration() {
        // Given: two validators
        RecordingValidator first = new RecordingValidator();
        RecordingValidator second = new RecordingValidator();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(first, second));

        // When: a definition is registered
        registry.register(Definitions.valid());

        // Then: both saw it exactly once
        assertThat(first.received).hasSize(1);
        assertThat(second.received).hasSize(1);
    }

    @Test
    @DisplayName("an empty validator set registers exactly as the registry without validators")
    void emptyValidatorSetRegistersNormally() {
        // Given: a registry with an empty validator set
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of());

        // When: a definition is registered
        registry.register(Definitions.valid());

        // Then: it resolves
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "validated-saga", 1L))
                        .plan()
                        .definitionId())
                .isEqualTo("validated-saga");
    }

    @Test
    @DisplayName("a null validator set is rejected at construction")
    void nullValidatorSetIsRejected() {
        assertThatThrownBy(() -> new DefaultWorkflowRegistry(null, null)).isInstanceOf(NullPointerException.class);
    }
}
