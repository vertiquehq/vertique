// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test {@link OperationPublicationSink} that never wants detail and, on every {@link #mountBuilt}
 * call, records the given {@link MountPublication} and returns the future of a fresh {@link Promise}
 * the test completes later, one call at a time (T006 TP-008).
 */
public final class PromiseControlledSink implements OperationPublicationSink {

    private final List<MountPublication> received = new ArrayList<>();
    private final Deque<Promise<Void>> promises = new ArrayDeque<>();

    @Override
    public boolean wantsDetail(String applicationName) {
        return false;
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        received.add(publication);
        Promise<Void> promise = Promise.promise();
        promises.addLast(promise);
        return promise.future();
    }

    /**
     * Returns every {@link MountPublication} received, in call order.
     *
     * @return an immutable copy of the recorded publications
     */
    public List<MountPublication> received() {
        return List.copyOf(received);
    }

    /**
     * Returns the promise created by the most recent {@link #mountBuilt(MountPublication)} call.
     *
     * @return the latest pending promise, or {@code null} if {@link #mountBuilt} was never called
     */
    public Promise<Void> latestPromise() {
        return promises.peekLast();
    }
}
