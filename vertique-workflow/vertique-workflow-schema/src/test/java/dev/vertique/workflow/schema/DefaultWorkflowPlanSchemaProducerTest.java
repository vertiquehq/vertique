// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.PlanSchemaPayloads.ApprovePayload;
import dev.vertique.workflow.schema.fixtures.PlanSchemaPayloads.OrderStart;
import dev.vertique.workflow.schema.fixtures.PlanSchemaPayloads.RejectPayload;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the producer derives the start and decision schemas stored on definition rows from a plan. */
class DefaultWorkflowPlanSchemaProducerTest {

    private static final String ID = "order-review";
    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L);

    record State(String orderId) {}

    @Test
    @DisplayName("the producer derives the start and decision schemas from the plan")
    void producesStartAndDecisionSchemasFromThePlan() {
        // Given: a registered definition with a start payload and one human task offering two decisions
        PayloadSchemaCache cache = new PayloadSchemaCache();
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(orderReview());
        RuntimeWorkflow plan = registry.resolve(KEY);
        WorkflowPlanSchemaProducer producer = new DefaultWorkflowPlanSchemaProducer(cache);

        // When: the schemas are produced for the plan
        JsonObject start = producer.startSchema(plan);
        Map<String, Map<String, JsonObject>> decisions = producer.decisionSchemas(plan);

        // Then: the start schema requires orderId and types amount as integer
        assertThat(start.getJsonArray("required").getList()).contains("orderId").doesNotContain("amount");
        assertThat(start.getJsonObject("properties").getJsonObject("amount").getString("type"))
                .isEqualTo("integer");

        // And: it equals the schema that start validation uses for the same key
        JsonObject validated = AwaitedSchemas.of(registry, cache)
                .describe(KEY)
                .orElseThrow()
                .startPayload()
                .document();
        assertThat(start).isEqualTo(validated).isEqualTo(Registries.generated(OrderStart.class));

        // And: the decisions are exactly review -> {approve, reject}
        assertThat(decisions).containsOnlyKeys("review");
        assertThat(decisions.get("review")).containsOnlyKeys("approve", "reject");
        assertThat(decisions
                        .get("review")
                        .get("approve")
                        .getJsonObject("properties")
                        .fieldNames())
                .containsExactly("approver");
        assertThat(decisions
                        .get("review")
                        .get("reject")
                        .getJsonObject("properties")
                        .fieldNames())
                .containsExactly("reason");
    }

    @Test
    @DisplayName("a plan with a void start type gets the explicit no-payload schema")
    void voidStartTypeGetsTheNoPayloadSchema() {
        // Given: a registered definition whose start type is void
        PayloadSchemaCache cache = new PayloadSchemaCache();
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(new WorkflowDefinition<State, Definitions.Primary>() {
            @Override
            public Class<Definitions.Primary> contract() {
                return Definitions.Primary.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return "void-start";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(Void.class, start -> new State("none"))
                        .initialStep("done")
                        .complete("done");
            }
        });
        RuntimeWorkflow plan = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "void-start", 1L));

        // When: the start schema is produced
        JsonObject start = new DefaultWorkflowPlanSchemaProducer(cache).startSchema(plan);

        // Then: it is the schema that admits no payload, not a null that stands for a row without a schema
        assertThat(start).isEqualTo(new JsonObject().put("type", "null"));
    }

    private static WorkflowDefinition<State, Definitions.Primary> orderReview() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<Definitions.Primary> contract() {
                return Definitions.Primary.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return ID;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<State> wf) {
                wf.init(OrderStart.class, start -> new State(start.orderId())).initialStep("review");
                wf.task("review")
                        .assignToRole("reviewers")
                        .decision("approve", ApprovePayload.class)
                        .onDecision((state, payload) -> state)
                        .toStep("done")
                        .decision("reject", RejectPayload.class)
                        .onDecision((state, payload) -> state)
                        .toStep("done")
                        .build()
                        .complete("done");
            }
        };
    }
}
