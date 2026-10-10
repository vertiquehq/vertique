// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.resilience.Resilience;
import io.vertx.core.Vertx;

/**
 * Supplies one {@link Resilience} runtime, over one {@link Vertx}, for tests that construct
 * authorization components by hand and do not exercise the resilience fence themselves.
 *
 * <p>The runtime is created on first use and lives until the test JVM exits.
 */
final class TestResilience {

    private static volatile Resilience shared;

    private TestResilience() {}

    /**
     * Returns the shared runtime.
     *
     * @return the shared runtime; never {@code null}
     */
    static Resilience shared() {
        Resilience existing = shared;
        if (existing != null) {
            return existing;
        }
        synchronized (TestResilience.class) {
            if (shared == null) {
                Vertx vertx = Vertx.vertx();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> vertx.close()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join()));
                shared = Resilience.create(vertx);
            }
            return shared;
        }
    }
}
