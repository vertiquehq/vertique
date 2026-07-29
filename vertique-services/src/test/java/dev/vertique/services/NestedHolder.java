// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.Future;

/**
 * Host for a nested {@code @ServiceContract} fixture used by {@link ServiceClientFactoryCompanionTest}
 * to prove that flattened companion-name selection composes through
 * {@link ServiceClientFactory#create(Class)} for nested contracts (CG-015 §4.2 step 3).
 *
 * <p>The nested {@link Inner} contract's generated companion is named
 * {@code NestedHolder_Inner_ServiceClientProxy} (using {@code _} as the separator), which matches
 * what {@link dev.vertique.core.util.GeneratedNames#companionFqn} produces. A regression to
 * {@code Class.getName() + suffix} would yield {@code NestedHolder$Inner_ServiceClientProxy} and
 * would miss the stand-in, silently falling back to a JDK dynamic proxy instead.
 */
interface NestedHolder {

    /**
     * Nested contract whose generated companion flattens to
     * {@code NestedHolder_Inner_ServiceClientProxy}.
     */
    @ServiceContract("nested-inner")
    interface Inner {

        /**
         * A single trivial operation dispatched over the event bus.
         *
         * @param x the payload string
         * @return a future of the dispatch result
         */
        @ServiceOperation("ping")
        Future<String> ping(String x);
    }
}
