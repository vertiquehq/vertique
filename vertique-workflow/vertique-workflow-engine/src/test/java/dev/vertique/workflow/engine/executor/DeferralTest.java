// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies when a node cannot run a peeked command, and the backoff of a deferral. */
class DeferralTest {

    private static final String PLAN = "orders";
    private static final String OTHER_PLAN = "invoices";

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

    private static CommandHead head(String definitionId, long version, String planHash) {
        return new CommandHead(
                1L,
                new WorkflowInstanceId(UUID.randomUUID()),
                "START",
                WorkflowScopes.DEFAULT,
                definitionId,
                version,
                planHash);
    }

    @Test
    @DisplayName("a command is not runnable without a plan or with another hash, and the backoff caps at five minutes")
    void notRunnableWhenNoPlanOrHashDiffersAndBackoffCapsAtFiveMinutes() {
        // Given a registry with the plan of orders version 1, and the hash that plan has
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new OrdersDefinition());
        String h1 = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, PLAN, 1L))
                .plan()
                .planHash();
        String h2 = "0".repeat(h1.length());
        assertThat(h2).isNotEqualTo(h1);

        // When the runnability of commands with different pins is computed
        // Then the pin of the held plan and hash is runnable, another hash is not, a pin without a hash compares by
        // key and is runnable, and a key the registry does not hold is not
        assertThat(Deferral.runnable(registry, head(PLAN, 1L, h1)))
                .as("held key, held hash")
                .isTrue();
        assertThat(Deferral.runnable(registry, head(PLAN, 1L, h2)))
                .as("held key, other hash")
                .isFalse();
        assertThat(Deferral.runnable(registry, head(PLAN, 1L, null)))
                .as("held key, no hash")
                .isTrue();
        assertThat(Deferral.runnable(registry, head(OTHER_PLAN, 1L, h1)))
                .as("other key")
                .isFalse();
        assertThat(Deferral.runnable(registry, head(PLAN, 2L, h1)))
                .as("other version")
                .isFalse();

        // When the backoff is computed from the count before the deferral
        // Then it doubles from five seconds and is capped at five minutes
        List<Long> expectedSeconds = List.of(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L);
        for (int count = 0; count < expectedSeconds.size(); count++) {
            assertThat(Deferral.backoffMs(count))
                    .as("backoff before count %s", count)
                    .isEqualTo(expectedSeconds.get(count) * 1000L);
        }
        assertThat(Deferral.backoffMs(70)).as("backoff before count 70").isEqualTo(300_000L);
        assertThat(Deferral.backoffMs(Integer.MAX_VALUE))
                .as("backoff before the largest count")
                .isEqualTo(300_000L);
    }
}
