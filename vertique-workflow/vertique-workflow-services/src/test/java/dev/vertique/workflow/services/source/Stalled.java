// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Futures that never complete on their own, standing for an application stage that stalls. The test
 * fails whatever is still pending in a {@code finally}, so no promise outlives the proof.
 */
final class Stalled {

    private final List<Promise<?>> pending = new CopyOnWriteArrayList<>();

    /** A future that stays pending until {@link #release()}. */
    <V> Future<V> future() {
        Promise<V> promise = Promise.promise();
        pending.add(promise);
        return promise.future();
    }

    /** Fails every future still pending. */
    void release() {
        for (Promise<?> promise : pending) {
            promise.tryFail("released by the test");
        }
    }
}
