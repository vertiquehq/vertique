// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.services.policy.PolicyStage;
import io.vertx.core.Future;
import java.util.List;
import java.util.function.Supplier;

/**
 * Chains {@link PolicyStage} instances into a composable dispatch pipeline.
 *
 * <p>Built once per operation at registration time by {@code PolicyChainBuilder}.
 * Stages execute from outermost to innermost: the single {@link dev.vertique.services.policy.CircuitBreakerStage}
 * stage handles timeout, retry, and circuit-breaking concerns, followed by the terminal method invocation.
 *
 * <p>The pipeline is constructed by iterating stages in reverse (innermost first) and wrapping
 * each subsequent stage as the {@code next} supplier of the preceding one.
 */
public class DispatchPipeline {

    private final List<PolicyStage> stages;

    /**
     * Creates a new pipeline with the given ordered list of policy stages.
     *
     * @param stages the policy stages in execution order (outermost first)
     */
    public DispatchPipeline(List<PolicyStage> stages) {
        this.stages = List.copyOf(stages);
    }

    /**
     * Executes the pipeline, chaining stages and terminating with the given supplier.
     *
     * <p>Stages are composed so the first stage in the list is outermost and the terminal
     * supplier is innermost.
     *
     * @param meta the operation metadata passed to each stage
     * @param body the incoming request body passed to each stage
     * @param terminal the innermost supplier representing the actual method invocation
     * @return the future result of executing the full pipeline
     */
    public Future<Object> execute(ServiceMethodMeta meta, DispatchEnvelope<?> body, Supplier<Future<Object>> terminal) {
        Supplier<Future<Object>> chain = terminal;
        // Build chain from innermost to outermost
        for (int i = stages.size() - 1; i >= 0; i--) {
            PolicyStage stage = stages.get(i);
            Supplier<Future<Object>> next = chain;
            chain = () -> stage.execute(meta, body, next);
        }
        return chain.get();
    }

    /**
     * Returns {@code true} if this pipeline contains at least one policy stage.
     *
     * @return {@code true} if any stages are present
     */
    public boolean hasStages() {
        return !stages.isEmpty();
    }
}
