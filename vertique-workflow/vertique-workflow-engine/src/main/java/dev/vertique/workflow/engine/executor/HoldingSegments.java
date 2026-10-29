// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * INTERNAL: the engine's side of a segment with the plan cache's hold on the plans it warmed.
 *
 * <p>Everything but the warm-up is the delegate's. The delegate is read on every call because the engine reaches the
 * executor control through the same graph that builds it.
 */
public final class HoldingSegments implements WorkflowSegments {

    private final Supplier<WorkflowSegments> delegate;
    private final Supplier<PlanCache> cache;

    /**
     * Creates the decorator.
     *
     * @param delegate the engine's segments, read on every call
     * @param cache    the cache that holds the warmed plans, read on every warm-up
     */
    public HoldingSegments(Supplier<WorkflowSegments> delegate, Supplier<PlanCache> cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Future<Warm> warm(CommandHead head) {
        return PlanWarming.warm(registry(), cache.get(), head);
    }

    @Override
    public WorkflowRegistry registry() {
        return delegate.get().registry();
    }

    @Override
    public Future<Outcome> run(CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        return delegate.get().run(head, claim, command, tx);
    }

    @Override
    public Future<Outcome> run(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx, int maxSteps) {
        return delegate.get().run(head, claim, command, tx, maxSteps);
    }

    @Override
    public Future<Void> instanceFailed(
            WorkflowInstanceId instance, long historySequence, String reasonCode, String message, SqlClient tx) {
        return delegate.get().instanceFailed(instance, historySequence, reasonCode, message, tx);
    }
}
