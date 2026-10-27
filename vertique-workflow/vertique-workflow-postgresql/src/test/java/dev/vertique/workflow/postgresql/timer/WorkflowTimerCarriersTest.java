// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the timer carrier descriptor keeps the kind and the address that timers have always used, so that
 * metadata written by earlier releases still verifies against it.
 */
class WorkflowTimerCarriersTest {

    @Test
    @DisplayName("the descriptor is the workflow-timer carrier addressed by the timer id and the instance id")
    void descriptorHasTheKindAndAddressOfEarlierReleases() {
        UUID timerId = UUID.randomUUID();
        WorkflowInstanceId instance = new WorkflowInstanceId(UUID.randomUUID());

        DurableCarrierDescriptor descriptor = WorkflowTimerCarriers.of(timerId, instance);

        assertThat(WorkflowTimerCarriers.TARGET_KIND).isEqualTo("workflow-timer");
        assertThat(descriptor.carrierId()).isEqualTo(timerId.toString());
        assertThat(descriptor.target().kind()).isEqualTo("workflow-timer");
        assertThat(descriptor.target().address()).isEqualTo(instance.value().toString());
    }
}
