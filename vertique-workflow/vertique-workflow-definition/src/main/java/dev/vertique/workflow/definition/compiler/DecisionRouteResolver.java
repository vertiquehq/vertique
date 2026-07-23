// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import dev.vertique.workflow.registry.CallbackId;
import java.util.Objects;
import java.util.function.Function;

/**
 * Value object returned by {@link DecisionRouteCompiler#compileRoutes}: pairs the synthesized
 * resolver function with the stable fingerprinted {@link CallbackId} that encodes the route table's
 * canonical form.
 *
 * <p>The {@link #fingerprintedCallbackId()} participates in the plan hash via
 * {@link dev.vertique.workflow.dsl.WorkflowBuilder#decideWithCallbackId}, so any semantic change
 * to the route table is automatically reflected in the plan hash (FR-WF-DEF-055, AC #8).
 *
 * @param resolverFn the synthesized {@code Function<Object, String>} that walks the route table in
 *     declaration order and returns the target step id for the first matching route, or the default
 *     if none match; never {@code null}
 * @param fingerprintedCallbackId the stable callback id whose value encodes the route table
 *     fingerprint; never {@code null}
 */
public record DecisionRouteResolver(Function<Object, String> resolverFn, CallbackId fingerprintedCallbackId) {

    /**
     * Compact canonical constructor — validates both components.
     *
     * @param resolverFn non-null resolver function
     * @param fingerprintedCallbackId non-null fingerprinted callback id
     * @throws NullPointerException if either component is null
     */
    public DecisionRouteResolver {
        Objects.requireNonNull(resolverFn, "resolverFn");
        Objects.requireNonNull(fingerprintedCallbackId, "fingerprintedCallbackId");
    }
}
