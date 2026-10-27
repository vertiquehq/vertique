// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Status;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.engine.registry.PlanFailures;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: warms the plans of a peeked command through the registry's {@code load} and holds them in the plan cache.
 *
 * <p>The plan the instance is pinned to is loaded and compared with the pin: a plan whose hash differs from the plan
 * hash the head names is not the one the instance runs, so the command is not runnable here. A migration also loads
 * the plan of its target. A plan that cannot run, because no row supplies it or it failed to build, makes the command
 * not runnable; a failure to read it says nothing about the plan and leaves the command for the next peek.
 */
final class PlanWarming {

    private static final Logger log = LoggerFactory.getLogger(PlanWarming.class);

    private PlanWarming() {}

    /**
     * Warms the plans of the command.
     *
     * @param registry the registry whose {@code load} supplies the plans
     * @param cache    the cache that holds the loaded plans for the segment, or {@code null} to hold none
     * @param head     the peeked command
     * @return a future of the warm-up; it never fails
     */
    static Future<Warm> warm(WorkflowRegistry registry, @Nullable PlanCache cache, CommandHead head) {
        DefinitionKey pin;
        try {
            pin = new DefinitionKey(head.definitionScopeKey(), head.definitionId(), head.definitionVersion());
        } catch (IllegalArgumentException e) {
            // a pin no key can name cannot be loaded, so no node can run it
            return Future.succeededFuture(Warm.of(Status.NOT_RUNNABLE));
        }
        List<Runnable> holds = new ArrayList<>(2);
        Future<Warm> warmed;
        try {
            warmed = registry.load(pin).compose(plan -> {
                if (!Deferral.matches(plan, head)) {
                    return Future.succeededFuture(Warm.of(Status.NOT_RUNNABLE));
                }
                hold(cache, holds, pin, plan);
                DefinitionKey target = target(head);
                if (target == null) {
                    return Future.succeededFuture(resident(holds));
                }
                return registry.load(target).map(targetPlan -> {
                    hold(cache, holds, target, targetPlan);
                    return resident(holds);
                });
            });
        } catch (RuntimeException e) {
            warmed = Future.failedFuture(e);
        }
        return warmed.recover(failure -> {
            release(holds);
            if (PlanFailures.isNotRunnable(failure)) {
                return Future.succeededFuture(Warm.of(Status.NOT_RUNNABLE));
            }
            log.debug("A plan could not be loaded for command {}", head.commandId(), failure);
            return Future.succeededFuture(Warm.of(Status.UNAVAILABLE));
        });
    }

    /** The key a migration moves its instance to, or {@code null} when the command is no migration or names none. */
    private static @Nullable DefinitionKey target(CommandHead head) {
        if (head.migrationVersion() == null) {
            return null;
        }
        try {
            return new DefinitionKey(
                    head.migrationScope() != null ? head.migrationScope() : head.definitionScopeKey(),
                    head.definitionId(),
                    head.migrationVersion());
        } catch (IllegalArgumentException e) {
            // a target no key can name is left to the segment, which rejects the migration
            return null;
        }
    }

    private static void hold(@Nullable PlanCache cache, List<Runnable> holds, DefinitionKey key, RuntimeWorkflow plan) {
        if (cache != null) {
            PlanCache.Hold hold = cache.hold(key, plan);
            holds.add(hold::release);
        }
    }

    private static Warm resident(List<Runnable> holds) {
        List<Runnable> taken = List.copyOf(holds);
        return new Warm(Status.RESIDENT, () -> release(taken));
    }

    private static void release(List<Runnable> holds) {
        for (Runnable hold : holds) {
            hold.run();
        }
    }
}
