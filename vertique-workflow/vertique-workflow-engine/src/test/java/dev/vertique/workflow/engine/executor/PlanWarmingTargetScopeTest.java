// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the warm-up of a migration loads the plan of a target scope the instance may migrate into only. */
class PlanWarmingTargetScopeTest {

    private static final String PLAN = "orders";

    /** The start payload of the test workflow. */
    record Start(String ref) {}

    /** The state of the test workflow. */
    record State(String ref) {}

    /** The contract of the test workflow. */
    interface Contract {}

    private static final class OrdersDefinition implements WorkflowDefinition<State, Contract> {
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
            return PLAN;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref()))
                    .initialStep("only")
                    .decide("only", state -> "done")
                    .complete("done");
        }
    }

    private static CommandHead migration(String targetScope) {
        return new CommandHead(
                1L,
                new WorkflowInstanceId(UUID.randomUUID()),
                "MIGRATION",
                WorkflowScopes.DEFAULT,
                PLAN,
                1L,
                null,
                false,
                1L,
                targetScope,
                "acme");
    }

    @Test
    @DisplayName("a migration target outside the definition scope, the start scope and the default scope is not loaded")
    void foreignTargetScopeIsNotLoaded() throws Exception {
        // Given a registry that holds the default plan of orders version 1 and no plan of any other scope
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new OrdersDefinition());

        // When the warm-up runs for migrations to the start scope, to a foreign scope and to the default scope
        Warm.Status startScope = status(registry, "acme");
        Warm.Status foreign = status(registry, "globex");
        Warm.Status defaultScope = status(registry, WorkflowScopes.DEFAULT);

        // Then the start scope's target was looked for and is missing, while the foreign scope was not looked for, and
        // the default scope's target is the plan that is held
        assertThat(startScope).isEqualTo(Warm.Status.NOT_RUNNABLE);
        assertThat(foreign).isEqualTo(Warm.Status.RESIDENT);
        assertThat(defaultScope).isEqualTo(Warm.Status.RESIDENT);
    }

    private static Warm.Status status(DefaultWorkflowRegistry registry, String targetScope) throws Exception {
        return PlanWarming.warm(registry, null, migration(targetScope))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .status();
    }
}
