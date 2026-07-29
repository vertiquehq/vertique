// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.Future;

/**
 * Test-fixture service contract simulating a stale generated companion (CG-015 §4.2 reachability
 * note): the interface declares a single current operation ({@code "opA"}), but its companion
 * ({@link DriftContract_ServiceClientProxy}) was "compiled" against an older interface version
 * that also had an {@code "opGhost"} operation baked into its constructor.
 *
 * <p>A runtime-complete registry entry for this contract (one operation, {@code "opA"}, matching
 * the current interface) still causes the companion constructor to fail, because the companion's
 * baked id ({@code "opGhost"}) is absent from {@code entry.operations()} — this is the one path
 * through which the factory reaches the generated constructor's own completeness check (the
 * factory-level {@code requireCompleteContract} check only enumerates the *current* interface's
 * methods, so it cannot detect baked-id drift on its own).
 */
@ServiceContract("drift")
interface DriftContract {

    /**
     * The contract's sole current operation.
     *
     * @param x the payload string
     * @return a future of the dispatch result
     */
    @ServiceOperation("opA")
    Future<String> opA(String x);
}
