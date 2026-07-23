// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

/**
 * Internal functional interface used by the workflow proxy invocation handler to dispatch a
 * method call to the appropriate {@code WorkflowOperations} method.
 *
 * <p>One {@code OperationHandler} is pre-compiled per annotated method in the contract interface
 * by {@link WorkflowProxyValidator#precompileHandlers}. The proxy invocation handler looks up
 * the handler for the called method and delegates to it.
 *
 * <p>The handler receives the raw argument array from the JDK proxy invocation handler and
 * returns the result (a {@code Future<?>}) or throws a {@link RuntimeException}.
 */
@FunctionalInterface
interface OperationHandler {

    /**
     * Invokes the underlying workflow operation with the given method arguments.
     *
     * @param args the method arguments from the proxy invocation; may be null or empty for
     *     zero-argument methods
     * @return the result of the operation, typically a {@code Future<?>}; never null
     * @throws Exception if the invocation fails (the proxy propagates this as-is)
     */
    Object invoke(Object[] args) throws Exception;
}
