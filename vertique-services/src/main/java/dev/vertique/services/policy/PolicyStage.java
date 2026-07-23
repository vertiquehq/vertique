// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.policy;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import java.util.function.Supplier;

/**
 * A composable stage in the dispatch policy pipeline.
 *
 * <p>Stages are ordered: Timeout (outermost) → CircuitBreaker → Retry (innermost).
 * Each stage wraps the next, forming a chain that terminates with the method invocation.
 */
@FunctionalInterface
public interface PolicyStage {

    /**
     * Executes this policy stage.
     *
     * @param meta operation metadata
     * @param body the incoming request body
     * @param next the next stage in the pipeline (or the method invocation)
     * @return the result of executing the pipeline
     */
    Future<Object> execute(ServiceMethodMeta meta, DispatchEnvelope<?> body, Supplier<Future<Object>> next);
}
