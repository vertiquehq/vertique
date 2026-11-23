// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the roots, the accessors and the value-free messages of the result exceptions. */
class WorkflowResultExceptionsTest {

    private static final WorkflowInstanceId ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void anUnavailableResultIsAConflictThatNamesTheReasonAndTheStatus() {
        WorkflowResultUnavailableException e = new WorkflowResultUnavailableException(
                ID, WorkflowResultUnavailableException.Reason.NOT_COMPLETED, WorkflowStatus.WAITING);

        assertThat(e).isInstanceOf(WorkflowConflictException.class);
        assertThat(e.reason()).isEqualTo(WorkflowResultUnavailableException.Reason.NOT_COMPLETED);
        assertThat(e.status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(e.instanceId()).isEqualTo(ID);
        assertThat(e).hasMessageContaining("NOT_COMPLETED").hasMessageContaining("WAITING");
    }

    @Test
    void aTypeMismatchIsABusinessRuleFailureThatNamesBothTypes() {
        WorkflowResultTypeMismatchException e =
                new WorkflowResultTypeMismatchException(ID, "order.Receipt", "order.Invoice");

        assertThat(e).isInstanceOf(WorkflowException.class).isNotInstanceOf(WorkflowConflictException.class);
        assertThat(e.storedTypeName()).isEqualTo("order.Receipt");
        assertThat(e.requestedTypeName()).isEqualTo("order.Invoice");
        assertThat(e).hasMessageContaining("order.Receipt").hasMessageContaining("order.Invoice");
    }
}
