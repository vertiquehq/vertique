// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Serializes local publication and compensation for each definition id without expiring work. */
@Singleton
final class DefinitionApplyQueue {
    private final Map<String, Future<Void>> tails = new HashMap<>();

    @Inject
    DefinitionApplyQueue() {}

    <T> Future<T> submit(String id, Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        Promise<Void> finished = Promise.promise();
        Future<Void> previous;
        synchronized (tails) {
            previous = tails.put(id, finished.future());
        }
        if (previous == null) previous = Future.succeededFuture();
        previous.onComplete(ignored -> {
            Future<T> operation;
            try {
                operation = action.get();
            } catch (RuntimeException e) {
                operation = Future.failedFuture(e);
            }
            operation.onComplete(outcome -> {
                result.handle(outcome);
                synchronized (tails) {
                    tails.remove(id, finished.future());
                }
                finished.complete();
            });
        });
        return result.future();
    }
}
