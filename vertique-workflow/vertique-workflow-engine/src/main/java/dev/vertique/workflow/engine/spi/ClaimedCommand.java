// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.UUID;

/**
 * A command an executor claimed: the proof of the claim is its token, which every later write of the claim presents.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param commandId  the command's row id
 * @param workflowId the instance the command belongs to
 * @param kind       the command kind, for example {@code START}
 * @param token      the claim token the claim set on the command
 * @param attempts   the attempts the command has spent, this claim included
 */
public record ClaimedCommand(long commandId, WorkflowInstanceId workflowId, String kind, UUID token, int attempts) {}
