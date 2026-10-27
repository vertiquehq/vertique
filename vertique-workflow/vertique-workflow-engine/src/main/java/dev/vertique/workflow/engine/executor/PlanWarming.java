// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Marks;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Status;
import dev.vertique.workflow.engine.registry.FailingMarks;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.engine.registry.PlanFailures;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: warms the plans of a peeked command through the registry's {@code load} and holds them in the plan cache.
 *
 * <p>The plan the instance is pinned to is loaded and compared with the pin: a plan whose hash differs from the plan
 * hash the head names is not the one the instance runs, so the command is not runnable here. A migration also loads
 * the plan of its target. A plan that cannot run, because no row supplies it or it failed to build, makes the command
 * not runnable; a failure to read it says nothing about the plan and leaves the command for the next peek.
 *
 * <p>The warm-up also takes the evaluation of each plan it holds that came from the plan cache, see
 * {@link FailingMarks}: the failure of the version the command is pinned to, which the segment of a start refuses, and
 * the writes of the marks the evaluations differ in, which the executor runs once the segment or the deferral has
 * ended. A name binding that no class resolves makes the command not runnable and is written the same way.
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
        Warming warming = new Warming(cache);
        Future<Warm> warmed;
        try {
            warmed = registry.load(pin).compose(plan -> {
                if (!Deferral.matches(plan, head)) {
                    return Future.succeededFuture(Warm.of(Status.NOT_RUNNABLE));
                }
                warming.hold(pin, plan, true);
                DefinitionKey target = target(head);
                if (target == null) {
                    return Future.succeededFuture(warming.resident());
                }
                return registry.load(target).map(targetPlan -> {
                    warming.hold(target, targetPlan, false);
                    return warming.resident();
                });
            });
        } catch (RuntimeException e) {
            warmed = Future.failedFuture(e);
        }
        return warmed.recover(failure -> {
            warming.release();
            if (PlanFailures.isNotRunnable(failure)) {
                return Future.succeededFuture(Warm.of(Status.NOT_RUNNABLE, warming.unresolved(failure)));
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

    /** The plans a warm-up holds and the evaluations of their versions. */
    private static final class Warming {

        private final @Nullable PlanCache cache;
        private final List<Runnable> holds = new ArrayList<>(2);
        private final List<Supplier<Future<Void>>> writes = new ArrayList<>(2);
        private @Nullable DefinitionFailure failing;

        Warming(@Nullable PlanCache cache) {
            this.cache = cache;
        }

        /** Holds the plan and takes the evaluation of its version, which is the pinned one when {@code pinned}. */
        void hold(DefinitionKey key, RuntimeWorkflow plan, boolean pinned) {
            if (cache == null) {
                return;
            }
            PlanCache.Hold hold = cache.hold(key, plan);
            holds.add(hold::release);
            cache.evaluation(key).ifPresent(evaluation -> {
                if (pinned) {
                    failing = evaluation.failure().orElse(null);
                }
                writes.add(() -> cache.marks().write(key, evaluation));
            });
        }

        Warm resident() {
            List<Runnable> taken = List.copyOf(holds);
            return new Warm(Status.RESIDENT, () -> release(taken), marks(List.copyOf(writes)));
        }

        /** The marks of a warm-up that failed with the load failure: the evaluations taken, and a binding's own. */
        Marks unresolved(Throwable failure) {
            List<Supplier<Future<Void>>> all = new ArrayList<>(writes);
            if (cache != null && failure instanceof FailingMarks.UnresolvedBindingException binding) {
                all.add(() -> cache.marks().write(binding.key(), binding.evaluation()));
            }
            return marks(all);
        }

        void release() {
            release(holds);
        }

        private Marks marks(List<Supplier<Future<Void>>> toWrite) {
            if (toWrite.isEmpty()) {
                return new Marks(failing, Future::succeededFuture);
            }
            return new Marks(failing, () -> {
                Future<Void> chain = Future.succeededFuture();
                for (Supplier<Future<Void>> write : toWrite) {
                    chain = chain.compose(ignored -> write.get());
                }
                return chain;
            });
        }

        private static void release(List<Runnable> holds) {
            for (Runnable hold : holds) {
                hold.run();
            }
        }
    }
}
