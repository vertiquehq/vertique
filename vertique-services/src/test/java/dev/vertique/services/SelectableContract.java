// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.Future;

/**
 * Test-fixture service contract used by {@link ServiceClientFactoryCompanionTest} to prove that
 * {@link ServiceClientFactory#create(Class)} selects a generated companion class when one is
 * present on the classpath (CG-015 §4.2 step 3).
 *
 * <p>The hand-written companion {@link SelectableContract_ServiceClientProxy} mimics the shape the
 * real annotation processor emits (CG-015 §4.1) — this module cannot run its own downstream
 * processor against its own test sources, so the companion is hand-written rather than generated.
 */
@ServiceContract("selectable")
interface SelectableContract {

    /**
     * A single trivial operation dispatched over the event bus.
     *
     * @param x the payload string
     * @return a future of the dispatch result
     */
    @ServiceOperation("ping")
    Future<String> ping(String x);
}
