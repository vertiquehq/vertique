// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.definition.schema.CompensationStep;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.definition.schema.FailStep;
import dev.vertique.workflow.definition.schema.ForkStep;
import dev.vertique.workflow.definition.schema.HumanTaskStep;
import dev.vertique.workflow.definition.schema.JoinStep;
import dev.vertique.workflow.definition.schema.ServiceStep;
import dev.vertique.workflow.definition.schema.TimerStep;
import dev.vertique.workflow.definition.schema.WaitSignalStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowDefinitionParser} correctly deserializes all step variants
 * from YAML and JSON sources, rejects malformed documents, and applies Jackson configuration
 * (unknown properties, unknown step types).
 *
 * <p>Tests use the canonical {@code full.workflow.yaml} and {@code full.workflow.json} fixtures
 * located under {@code src/test/resources/definitions/good/}. Each fixture contains one of
 * every step variant so a single round-trip test exercises all branches of the polymorphic
 * deserialization.
 */
class WorkflowDefinitionParserTest {

    private WorkflowDefinitionParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDefinitionParser(new WorkflowDefinitionMapperFactory());
    }

    // --- Helpers ---

    private byte[] fixture(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Test fixture not found on classpath: " + resourcePath);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test fixture: " + resourcePath, e);
        }
    }

    // --- Top-level document assertions ---

    private void assertDocumentHeader(WorkflowDefinitionDocument doc) {
        assertThat(doc.definitionId()).isEqualTo("order-fulfillment");
        assertThat(doc.definitionVersion()).isEqualTo(1L);
        assertThat(doc.stateType()).isEqualTo("dev.vertique.examples.workflow.order.state.OrderState");
        assertThat(doc.contract()).isEqualTo("dev.vertique.examples.workflow.order.OrderFulfillmentWorkflowV1");
        assertThat(doc.startPayloadType()).isEqualTo("dev.vertique.examples.workflow.order.command.PlaceOrder");
        assertThat(doc.initialStateMapper()).isEqualTo("order.fromPlaceOrder");
        assertThat(doc.subjectResolver()).isEqualTo("order.subject");
        assertThat(doc.initialStep()).isEqualTo("reserve-inventory");
        assertThat(doc.steps()).hasSize(13);
    }

    // --- Step-level assertions ---

    private void assertServiceStep(WorkflowDefinitionDocument doc, int index, String id) {
        assertThat(doc.steps().get(index)).isInstanceOfSatisfying(ServiceStep.class, step -> {
            assertThat(step.id()).isEqualTo(id);
        });
    }

    private void assertAllStepVariants(WorkflowDefinitionDocument doc) {
        // --- Step 0: reserve-inventory (service with compensation) ---
        assertThat(doc.steps().get(0)).isInstanceOfSatisfying(ServiceStep.class, step -> {
            assertThat(step.id()).isEqualTo("reserve-inventory");
            assertThat(step.target()).isEqualTo("inventory.reserve");
            assertThat(step.payloadMapper()).isEqualTo("order.reserveInventoryPayload");
            assertThat(step.compensation()).isEqualTo("release-inventory");
            assertThat(step.next()).isEqualTo("wait-inventory");
        });

        // --- Step 1: release-inventory (compensation) ---
        assertThat(doc.steps().get(1)).isInstanceOfSatisfying(CompensationStep.class, step -> {
            assertThat(step.id()).isEqualTo("release-inventory");
            assertThat(step.forwardStep()).isEqualTo("reserve-inventory");
            assertThat(step.target()).isEqualTo("inventory.release");
            assertThat(step.payloadMapper()).isEqualTo("order.releaseInventoryPayload");
        });

        // --- Step 2: wait-inventory (wait-signal with timeout) ---
        assertThat(doc.steps().get(2)).isInstanceOfSatisfying(WaitSignalStep.class, step -> {
            assertThat(step.id()).isEqualTo("wait-inventory");
            assertThat(step.signal()).isEqualTo("inventory.reserved");
            assertThat(step.payloadType()).isEqualTo("dev.vertique.examples.workflow.order.signal.InventoryReserved");
            assertThat(step.stateReducer()).isEqualTo("order.applyInventoryReserved");
            assertThat(step.next()).isEqualTo("defer-shipment");
            assertThat(step.timeout()).isNotNull();
            assertThat(step.timeout().after()).isEqualTo("PT15M");
            assertThat(step.timeout().onTimeoutMutator()).isEqualTo("order.markInventoryTimedOut");
            assertThat(step.timeout().next()).isEqualTo("cancel-order");
        });

        // --- Step 3: defer-shipment (timer) ---
        assertThat(doc.steps().get(3)).isInstanceOfSatisfying(TimerStep.class, step -> {
            assertThat(step.id()).isEqualTo("defer-shipment");
            assertThat(step.fireAt()).isEqualTo("PT1H");
            assertThat(step.next()).isEqualTo("route-review");
        });

        // --- Step 4: route-review (decision with when + condition routes and default) ---
        assertThat(doc.steps().get(4)).isInstanceOfSatisfying(DecisionStep.class, step -> {
            assertThat(step.id()).isEqualTo("route-review");
            assertThat(step.routes()).hasSize(2);
            assertThat(step.routes().get(0).when()).isEqualTo("review.riskLevel in ['high', 'critical']");
            assertThat(step.routes().get(0).condition()).isNull();
            assertThat(step.routes().get(0).to()).isEqualTo("parallel-reviews");
            assertThat(step.routes().get(1).when()).isNull();
            assertThat(step.routes().get(1).condition()).isEqualTo("policy.lowRiskAutoApprove");
            assertThat(step.routes().get(1).to()).isEqualTo("ship-order");
            assertThat(step.defaultRoute()).isEqualTo("manual-review");
        });

        // --- Step 5: manual-review (human-task with all sub-records) ---
        assertThat(doc.steps().get(5)).isInstanceOfSatisfying(HumanTaskStep.class, step -> {
            assertThat(step.id()).isEqualTo("manual-review");
            assertThat(step.taskType()).isEqualTo("approval");

            assertThat(step.assignment()).isNotNull();
            assertThat(step.assignment().mode()).isEqualTo("role");
            assertThat(step.assignment().value()).isEqualTo("editor");
            assertThat(step.assignment().resolver()).isNull();

            assertThat(step.decisions()).hasSize(2);
            assertThat(step.decisions().get(0).name()).isEqualTo("approve");
            assertThat(step.decisions().get(0).payloadType())
                    .isEqualTo("dev.vertique.examples.workflow.order.task.ApprovePayload");
            assertThat(step.decisions().get(0).applicator()).isEqualTo("review.applyApprove");
            assertThat(step.decisions().get(0).next()).isEqualTo("ship-order");
            assertThat(step.decisions().get(1).name()).isEqualTo("reject");
            assertThat(step.decisions().get(1).applicator()).isEqualTo("review.applyReject");
            assertThat(step.decisions().get(1).next()).isEqualTo("cancel-order");

            assertThat(step.due()).isNotNull();
            assertThat(step.due().at()).isEqualTo("PT24H");
            assertThat(step.due().onDueMutator()).isEqualTo("review.markOverdue");
            assertThat(step.due().next()).isEqualTo("cancel-order");

            assertThat(step.reminders()).isNotNull();
            assertThat(step.reminders().interval()).isEqualTo("PT6H");
            assertThat(step.reminders().offsets()).isNull();

            assertThat(step.requireVersionStability()).isEqualTo(Boolean.TRUE);
        });

        // --- Step 6: parallel-reviews (fork with retry policy) ---
        assertThat(doc.steps().get(6)).isInstanceOfSatisfying(ForkStep.class, step -> {
            assertThat(step.id()).isEqualTo("parallel-reviews");
            assertThat(step.branches()).hasSize(2);
            assertThat(step.branches().get(0).branchId()).isEqualTo("legal");
            assertThat(step.branches().get(0).startStep()).isEqualTo("legal-review");
            assertThat(step.branches().get(0).raceSafety()).isEqualTo("NORMAL");
            assertThat(step.branches().get(1).branchId()).isEqualTo("finance");
            assertThat(step.branches().get(1).startStep()).isEqualTo("finance-review");
            assertThat(step.branches().get(1).raceSafety()).isNull();
            assertThat(step.join()).isEqualTo("reviews-joined");
            assertThat(step.retryPolicy()).isNotNull();
            assertThat(step.retryPolicy().maxAttempts()).isEqualTo(3);
            assertThat(step.retryPolicy().initialDelay()).isEqualTo("PT5S");
            assertThat(step.retryPolicy().backoff()).isEqualTo("EXPONENTIAL");
        });

        // --- Steps 7, 8: legal-review, finance-review (complete) ---
        assertThat(doc.steps().get(7)).isInstanceOfSatisfying(CompleteStep.class, step -> {
            assertThat(step.id()).isEqualTo("legal-review");
        });
        assertThat(doc.steps().get(8)).isInstanceOfSatisfying(CompleteStep.class, step -> {
            assertThat(step.id()).isEqualTo("finance-review");
        });

        // --- Step 9: reviews-joined (join) ---
        assertThat(doc.steps().get(9)).isInstanceOfSatisfying(JoinStep.class, step -> {
            assertThat(step.id()).isEqualTo("reviews-joined");
            assertThat(step.policy()).isEqualTo("all-required");
            assertThat(step.reducer()).isEqualTo("review.combineBranchResults");
            assertThat(step.next()).isEqualTo("ship-order");
            assertThat(step.onFailure()).isEqualTo("cancel-order");
        });

        // --- Step 10: ship-order (service without compensation) ---
        assertThat(doc.steps().get(10)).isInstanceOfSatisfying(ServiceStep.class, step -> {
            assertThat(step.id()).isEqualTo("ship-order");
            assertThat(step.target()).isEqualTo("shipping.create");
            assertThat(step.payloadMapper()).isEqualTo("order.shippingPayload");
            assertThat(step.compensation()).isNull();
            assertThat(step.next()).isEqualTo("done");
        });

        // --- Step 11: done (complete) ---
        assertThat(doc.steps().get(11)).isInstanceOfSatisfying(CompleteStep.class, step -> {
            assertThat(step.id()).isEqualTo("done");
        });

        // --- Step 12: cancel-order (fail) ---
        assertThat(doc.steps().get(12)).isInstanceOfSatisfying(FailStep.class, step -> {
            assertThat(step.id()).isEqualTo("cancel-order");
            assertThat(step.errorType()).isEqualTo("order-cancelled");
            assertThat(step.messageFactory()).isEqualTo("order.cancellationMessage");
        });
    }

    // --- Happy-path round-trip tests ---

    @Nested
    @DisplayName("Happy path — round-trip parsing")
    class HappyPath {

        @Test
        @DisplayName("YAML fixture with all step variants round-trips correctly")
        void roundTripYaml_allStepVariants() {
            byte[] bytes = fixture("definitions/good/full.workflow.yaml");
            WorkflowDefinitionDocument doc = parser.parse(bytes, DocumentFormat.YAML);

            assertDocumentHeader(doc);
            assertAllStepVariants(doc);
        }

        @Test
        @DisplayName("JSON fixture with all step variants round-trips correctly")
        void roundTripJson_allStepVariants() {
            byte[] bytes = fixture("definitions/good/full.workflow.json");
            WorkflowDefinitionDocument doc = parser.parse(bytes, DocumentFormat.JSON);

            assertDocumentHeader(doc);
            assertAllStepVariants(doc);
        }

        @Test
        @DisplayName("JSON-parsed document equals YAML-parsed document")
        void jsonDoc_equalsYamlDoc() {
            WorkflowDefinitionDocument yamlDoc =
                    parser.parse(fixture("definitions/good/full.workflow.yaml"), DocumentFormat.YAML);
            WorkflowDefinitionDocument jsonDoc =
                    parser.parse(fixture("definitions/good/full.workflow.json"), DocumentFormat.JSON);

            assertThat(jsonDoc)
                    .as("JSON-parsed document must be structurally equal to the YAML-parsed document")
                    .isEqualTo(yamlDoc);
        }

        @Test
        @DisplayName("DecisionStep 'default' YAML key maps to 'defaultRoute' Java field")
        void decisionRouteWithDefaultKeyword_parsesCorrectly() {
            byte[] bytes = fixture("definitions/good/full.workflow.yaml");
            WorkflowDefinitionDocument doc = parser.parse(bytes, DocumentFormat.YAML);

            DecisionStep decision = doc.steps().stream()
                    .filter(s -> s instanceof DecisionStep)
                    .map(s -> (DecisionStep) s)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No DecisionStep found in fixture"));

            assertThat(decision.defaultRoute())
                    .as("The YAML key 'default' must map to the 'defaultRoute' Java field")
                    .isEqualTo("manual-review");
        }

        @Test
        @DisplayName("HumanTaskStep.requireVersionStability is null (treated as false) when absent")
        void humanTaskDefaultRequireVersionStability_isFalseWhenAbsent() {
            // Minimal document with a human-task step that omits requireVersionStability
            String yaml = """
                    definitionId: test
                    definitionVersion: 1
                    stateType: dev.example.State
                    contract: dev.example.TestWorkflow
                    startPayloadType: dev.example.Start
                    initialStateMapper: test.init
                    initialStep: review
                    steps:
                      - id: review
                        type: human-task
                        taskType: approval
                        assignment:
                          mode: role
                          value: admin
                        decisions:
                          - name: approve
                            payloadType: dev.example.Approve
                            applicator: test.applyApprove
                            next: done
                      - id: done
                        type: complete
                    """;

            WorkflowDefinitionDocument doc = parser.parse(yaml.getBytes(), DocumentFormat.YAML);
            HumanTaskStep task = (HumanTaskStep) doc.steps().get(0);

            assertThat(task.requireVersionStability())
                    .as("requireVersionStability must be null (treated as false) when absent in the source document")
                    .isNotEqualTo(Boolean.TRUE);
        }
    }

    // --- Error path tests ---

    @Nested
    @DisplayName("Error paths — rejected documents")
    class ErrorPaths {

        @Test
        @DisplayName("Unknown step type 'bogus' causes WorkflowDefinitionParseException")
        void unknownStepType_isRejected() {
            byte[] bytes = fixture("definitions/bad/unknown-step-type.yaml");

            assertThatThrownBy(() -> parser.parse(bytes, DocumentFormat.YAML))
                    .as("A step with an unknown 'type' must cause a WorkflowDefinitionParseException")
                    .isInstanceOf(WorkflowDefinitionParseException.class);
        }

        @Test
        @DisplayName("Unknown top-level field causes WorkflowDefinitionParseException")
        void unknownField_isRejected() {
            byte[] bytes = fixture("definitions/bad/unknown-top-level-field.yaml");

            assertThatThrownBy(() -> parser.parse(bytes, DocumentFormat.YAML))
                    .as("An unknown top-level property must cause a WorkflowDefinitionParseException"
                            + " (FAIL_ON_UNKNOWN_PROPERTIES)")
                    .isInstanceOf(WorkflowDefinitionParseException.class);
        }

        @Test
        @DisplayName("Inline YAML with unknown top-level field is rejected")
        void inlineYamlWithUnknownField_isRejected() {
            String yaml = """
                    definitionId: test
                    definitionVersion: 1
                    stateType: dev.example.State
                    contract: dev.example.Workflow
                    startPayloadType: dev.example.Start
                    initialStateMapper: test.init
                    initialStep: done
                    unexpectedField: surprise
                    steps:
                      - id: done
                        type: complete
                    """;

            assertThatThrownBy(() -> parser.parse(yaml.getBytes(), DocumentFormat.YAML))
                    .isInstanceOf(WorkflowDefinitionParseException.class);
        }

        @Test
        @DisplayName("Inline JSON with unknown top-level field is rejected")
        void inlineJsonWithUnknownField_isRejected() {
            String json = """
                    {
                      "definitionId": "test",
                      "definitionVersion": 1,
                      "stateType": "dev.example.State",
                      "contract": "dev.example.Workflow",
                      "startPayloadType": "dev.example.Start",
                      "initialStateMapper": "test.init",
                      "initialStep": "done",
                      "unexpectedField": "surprise",
                      "steps": [{"id": "done", "type": "complete"}]
                    }
                    """;

            assertThatThrownBy(() -> parser.parse(json.getBytes(), DocumentFormat.JSON))
                    .isInstanceOf(WorkflowDefinitionParseException.class);
        }
    }

    // --- StepNode type coverage ---

    @Nested
    @DisplayName("Step variant types")
    class StepTypes {

        private WorkflowDefinitionDocument doc;

        @BeforeEach
        void parseFixture() {
            doc = parser.parse(fixture("definitions/good/full.workflow.yaml"), DocumentFormat.YAML);
        }

        @Test
        @DisplayName("Fixture contains all 10 step variant types")
        void fixture_containsAllTenStepTypes() {
            List<Class<?>> stepTypes =
                    doc.steps().stream().map(Object::getClass).distinct().toList();

            assertThat(stepTypes)
                    .as("The full fixture must include all 10 step variant types")
                    .containsExactlyInAnyOrder(
                            ServiceStep.class,
                            CompensationStep.class,
                            WaitSignalStep.class,
                            TimerStep.class,
                            DecisionStep.class,
                            HumanTaskStep.class,
                            ForkStep.class,
                            CompleteStep.class,
                            JoinStep.class,
                            FailStep.class);
        }
    }
}
