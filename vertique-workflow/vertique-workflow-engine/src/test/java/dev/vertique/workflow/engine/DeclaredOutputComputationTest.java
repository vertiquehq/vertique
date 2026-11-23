// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Verifies the output computed when an instance completes: the value, and the failure messages, which name the
 * definition, version and step, and the bound that was crossed, but never the value or the text of the cause.
 */
class DeclaredOutputComputationTest {

    private static final String LEAK = "secret-value-7f3a";

    record State(String id) {}

    record Start(String id) {}

    record Out(String value) {}

    @WorkflowContract
    interface Contract {}

    private static RuntimeWorkflow runtime(Function<State, Object> mapper) {
        WorkflowDefinition<State, Contract> definition = new WorkflowDefinition<>() {
            @Override
            public Class<Contract> contract() {
                return Contract.class;
            }

            @Override
            public Class<State> stateType() {
                return State.class;
            }

            @Override
            public String definitionId() {
                return "computation";
            }

            @Override
            public long definitionVersion() {
                return 3L;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void define(WorkflowBuilder<State> wf) {
                wf.init(Start.class, s -> new State(s.id()))
                        .output((Class<Object>) (Class<?>) Object.class, mapper)
                        .initialStep("done")
                        .complete("done");
            }
        };
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(definition);
        return registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "computation", 3L));
    }

    private static WorkflowInstance instance() {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                "computation",
                3L,
                "hash",
                1L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "done",
                null,
                null,
                null,
                "{\"id\":\"" + LEAK + "\"}",
                null,
                null,
                now,
                now,
                null);
    }

    private static RecordTimePayloadValidator validator(int maxPayloadBytes) {
        return new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, maxPayloadBytes));
    }

    private static String compute(RuntimeWorkflow rw, int maxPayloadBytes) {
        return WorkflowTransitionDriver.computeOutput(
                validator(maxPayloadBytes), instance(), new CompleteNode("done"), rw);
    }

    @Test
    void theMapperSeesTheStateAndItsValueIsEncoded() {
        RuntimeWorkflow rw = runtime(s -> new Out(s.id()));

        assertThat(compute(rw, 262144)).isEqualTo("{\"value\":\"" + LEAK + "\"}");
    }

    @Test
    void aThrowingMapperFailsWithoutTheValueOrTheCauseText() {
        RuntimeWorkflow rw = runtime(s -> {
            throw new IllegalStateException("cause text " + s.id());
        });

        assertThatThrownBy(() -> compute(rw, 262144))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasNoCause()
                .message()
                .contains("computation")
                .contains("v3")
                .contains("done")
                .doesNotContain(LEAK)
                .doesNotContain("cause text");
    }

    @Test
    void aNullOutputFails() {
        RuntimeWorkflow rw = runtime(s -> null);

        assertThatThrownBy(() -> compute(rw, 262144))
                .isInstanceOf(WorkflowDefinitionException.class)
                .message()
                .contains("null")
                .contains("computation")
                .contains("done");
    }

    @Test
    void anOutputOverTheByteBoundNamesTheBoundAndNotTheValue() {
        RuntimeWorkflow rw = runtime(s -> new Out(LEAK.repeat(200)));

        assertThatThrownBy(() -> compute(rw, 1024))
                .isInstanceOf(WorkflowDefinitionException.class)
                .message()
                .contains("maxPayloadBytes 1024")
                .contains("computation")
                .contains("done")
                .doesNotContain(LEAK);
    }

    @Test
    void anOutputDeeperThanTheDepthBoundNamesTheBoundAndNotTheValue() {
        RuntimeWorkflow rw = runtime(s -> {
            Map<String, Object> deepest = new HashMap<>();
            deepest.put("leaf", LEAK);
            Object nested = deepest;
            for (int level = 0; level < 70; level++) {
                nested = List.of(nested);
            }
            return new ArrayList<>(List.of(nested));
        });

        assertThatThrownBy(() -> compute(rw, 262144))
                .isInstanceOf(WorkflowDefinitionException.class)
                .message()
                .contains("depth 64")
                .doesNotContain(LEAK);
    }
}
