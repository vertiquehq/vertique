// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.Optional;

/**
 * INTERNAL: the durable carrier descriptor of a {@code workflow_commands} row, which the durable context stored in the
 * row's {@code context_json} is encoded for.
 *
 * <p>The address is reproducible from the row's own {@code workflow_id} and {@code seq} columns, so an encoder that
 * binds what it writes to a row can bind it to this one.
 */
final class CommandContextCarrier {

    /** The durable-target kind that tells a command row's carrier from every other row's. */
    static final String TARGET_KIND = "workflow-command";

    /** The durable-context namespace of an identity snapshot, which no command row keeps. */
    static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    private CommandContextCarrier() {
        // static helpers only
    }

    /**
     * Builds the carrier descriptor of a command row.
     *
     * @param workflowId the instance the command belongs to; its raw UUID is the target address
     * @param seq        the command's queue position, the carrier id
     * @return the descriptor identifying that row
     */
    static DurableCarrierDescriptor of(WorkflowInstanceId workflowId, long seq) {
        return new DurableCarrierDescriptor(
                Long.toString(seq),
                new DurableTarget(TARGET_KIND, workflowId.value().toString(), Optional.empty()));
    }
}
