// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.Future;

/**
 * Test-fixture service contract whose companion ({@link BrokenContract_ServiceClientProxy}) has a
 * constructor that unconditionally throws, used by {@link ServiceClientFactoryCompanionTest} to
 * prove {@link ServiceClientFactory#create(Class)} fails loudly — rather than silently falling
 * back to the JDK dynamic proxy — when a generated companion is present but cannot be
 * instantiated (CG-015 §4.2, {@code brokenCompanion} unwrap).
 */
@ServiceContract("broken")
interface BrokenContract {

    /**
     * A single trivial operation; never actually dispatched in these tests.
     *
     * @param x the payload string
     * @return a future of the dispatch result
     */
    @ServiceOperation("ping")
    Future<String> ping(String x);
}
