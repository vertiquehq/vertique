// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: reads the commands an executor could claim and makes their plans resident, outside any transaction.
 *
 * <p>A candidate whose instance has ended is kept without making any plan resident: its claim settles it without one,
 * so a removed plan cannot leave it unsettled.
 *
 * <p>A peeked command whose plan this node cannot resolve is skipped for this peek and logged at debug level with its
 * ids only, never a payload; nothing is claimed, no attempt is spent and nothing is written, so the command is peeked
 * again on the next pass.
 */
final class CommandPeek {

    private static final Logger log = LoggerFactory.getLogger(CommandPeek.class);

    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowSegments segments;

    CommandPeek(WorkflowCommandRepository<SqlClient> commands, WorkflowSegments segments) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.segments = Objects.requireNonNull(segments, "segments");
    }

    /**
     * Peeks at most {@code limit} claimable commands, oldest availability first, and keeps those whose plan is
     * resident.
     *
     * @param limit the most commands to peek
     * @return a future of the commands to claim
     * @throws NullPointerException if the repository returns no future
     */
    Future<List<CommandHead>> resident(int limit) {
        Future<List<CommandHead>> peeked =
                Objects.requireNonNull(commands.peek(limit), "peek returned null instead of a future");
        return peeked.map(heads -> {
            List<CommandHead> resident = new ArrayList<>();
            if (heads == null) {
                return resident;
            }
            for (CommandHead head : heads) {
                if (head.instanceEnded() || segments.makeResident(head)) {
                    resident.add(head);
                } else {
                    log.debug(
                            "Skipping command {} of instance {}: no plan for {} version {} in scope {}",
                            head.commandId(),
                            head.workflowId().value(),
                            head.definitionId(),
                            head.definitionVersion(),
                            head.definitionScopeKey());
                }
            }
            return resident;
        });
    }
}
