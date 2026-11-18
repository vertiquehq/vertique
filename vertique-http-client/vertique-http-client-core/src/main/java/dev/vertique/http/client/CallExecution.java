// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * One call of {@link DefaultVertiqueHttpClient#execute}: it owns the attempt's {@link AttemptScope},
 * the attempt timer and the binding to a {@link CancellationSignal}, and settles the caller's future
 * exactly once.
 *
 * <p>All state is confined to the call's Vert.x context. The only entry from another thread is
 * {@link #cancel()}, which hops onto that context.
 */
final class CallExecution<T> implements CancellationSignal.Registration {

    /** Message of the failure an attempt timeout ends the call with. */
    static final String TIMED_OUT = "The HTTP attempt timed out";

    private final Context context;
    private final AttemptScope scope;
    private final Promise<T> promise = Promise.promise();
    private final @Nullable CancellationSignal signal;
    private final Vertx vertx;
    private final long timeoutMs;
    private boolean settled;
    private long timerId = -1;

    CallExecution(
            Vertx vertx,
            Context context,
            @Nullable CancellationSignal signal,
            long timeoutMs,
            HttpClientInterceptors interceptors) {
        this.scope = new AttemptScope(interceptors, UUID.randomUUID().toString());
        this.vertx = vertx;
        this.context = context;
        this.signal = signal;
        this.timeoutMs = timeoutMs;
    }

    AttemptScope scope() {
        return scope;
    }

    /**
     * Starts the call.
     *
     * @param attempt builds the attempt's result from the call's scoped web client
     * @param scoped the scoped web client of {@link #scope()}
     * @return the future the caller observes
     */
    Future<T> start(Function<WebClient, Future<T>> attempt, WebClient scoped) {
        Future<T> future = promise.future();
        if (Vertx.currentContext() == context) {
            run(attempt, scoped);
        } else {
            context.runOnContext(ignored -> run(attempt, scoped));
        }
        return future;
    }

    private void run(Function<WebClient, Future<T>> attempt, WebClient scoped) {
        if (signal != null && !signal.register(this)) {
            settle(Future.failedFuture(new HttpExchangeCancelledException()));
            return;
        }
        if (timeoutMs > 0) {
            timerId = vertx.setTimer(timeoutMs, id -> settle(Future.failedFuture(new TimeoutException(TIMED_OUT))));
        }
        Future<T> result;
        try {
            result = attempt.apply(scoped);
            if (result == null) {
                result = Future.failedFuture(new IllegalStateException("The attempt returned no future"));
            }
        } catch (RuntimeException failure) {
            result = Future.failedFuture(failure);
        }
        result.onComplete(outcome -> {
            if (Vertx.currentContext() == context) {
                settle(outcome);
            } else {
                context.runOnContext(ignored -> settle(outcome));
            }
        });
    }

    /** Cancels the call from any thread: hops onto the call's context; the first ending wins. */
    @Override
    public void cancel() {
        context.runOnContext(ignored -> settle(Future.failedFuture(new HttpExchangeCancelledException())));
    }

    /**
     * Ends the call with {@code outcome}: stops the timer, leaves the signal, tells the interceptors how
     * the call's exchanges ended, resets the open exchanges and only then completes the caller's future. Later endings do nothing.
     */
    private void settle(AsyncResult<T> outcome) {
        if (settled) {
            return;
        }
        settled = true;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
        if (signal != null) {
            signal.deregister(this);
        }
        scope.end(outcome.failed() ? outcome.cause() : null);
        promise.handle(outcome);
    }
}
