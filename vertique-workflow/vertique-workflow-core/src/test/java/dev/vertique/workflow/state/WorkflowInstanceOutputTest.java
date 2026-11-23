// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the output components of {@link WorkflowInstance}: the previous constructors and every updater. */
class WorkflowInstanceOutputTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private static WorkflowInstance completedWithOutput() {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                "order",
                1L,
                "hash",
                3L,
                WorkflowStatus.COMPLETED,
                null,
                null,
                "done",
                null,
                null,
                null,
                "{\"id\":\"o-1\"}",
                null,
                null,
                NOW,
                NOW,
                null,
                WorkflowScopes.DEFAULT,
                WorkflowScopes.DEFAULT,
                "{\"orderId\":\"o-1\"}",
                "order.Receipt");
    }

    @Test
    void thePreviousConstructorsCarryNoOutput() {
        WorkflowInstance eighteen = new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                "order",
                1L,
                "hash",
                1L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "step",
                null,
                null,
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                null);
        WorkflowInstance twenty = new WorkflowInstance(
                eighteen.id(),
                "order",
                1L,
                "hash",
                1L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "step",
                null,
                null,
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                null,
                "scope",
                "scope");

        assertThat(eighteen.outputJson()).isNull();
        assertThat(eighteen.outputTypeName()).isNull();
        assertThat(twenty.outputJson()).isNull();
        assertThat(twenty.outputTypeName()).isNull();
    }

    @Test
    void everyUpdaterCarriesTheOutput() {
        WorkflowInstance instance = completedWithOutput();

        WorkflowInstance[] updated = {
            instance.withVersion(4L),
            instance.withStatus(WorkflowStatus.FAILED),
            instance.withCurrentStepId("other"),
            instance.withWait(WaitType.SIGNAL, "s"),
            instance.withWait(WaitType.SIGNAL, "s", UUID.randomUUID()),
            instance.withState("{}"),
            instance.withError("type", "message"),
            instance.withUpdatedAt(NOW.plusSeconds(1)),
        };

        for (WorkflowInstance each : updated) {
            assertThat(each.outputJson()).isEqualTo("{\"orderId\":\"o-1\"}");
            assertThat(each.outputTypeName()).isEqualTo("order.Receipt");
        }
    }

    @Test
    void withOutputReplacesBothComponentsAndKeepsTheRest() {
        WorkflowInstance instance = completedWithOutput();

        WorkflowInstance replaced = instance.withOutput("{\"a\":1}", "other.Type");
        WorkflowInstance cleared = instance.withOutput(null, null);

        assertThat(replaced.outputJson()).isEqualTo("{\"a\":1}");
        assertThat(replaced.outputTypeName()).isEqualTo("other.Type");
        assertThat(replaced.withOutput(instance.outputJson(), instance.outputTypeName()))
                .isEqualTo(instance);
        assertThat(cleared.outputJson()).isNull();
        assertThat(cleared.outputTypeName()).isNull();
        assertThat(cleared.stateJson()).isEqualTo(instance.stateJson());
    }
}
