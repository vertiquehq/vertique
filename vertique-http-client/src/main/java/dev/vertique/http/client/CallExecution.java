// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.resilience.ResilienceCancellation;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One call of {@link HttpClient#execute}: it runs the attempts, each with its own
 * {@link AttemptScope} and attempt timer, inside the call's resilience pipeline (or as a single attempt
 * without one), binds the call to a {@link CancellationSignal} and settles the caller's future exactly
 * once.
 *
 * <p>All state is confined to the call's Vert.x context. The entries from other threads are
 * {@link #cancel()} and {@link #cancel(Throwable)}, which fix the reason at once and hop onto that
 * context.
 */
final class CallExecution<T> implements CancellationSignal.Registration {

    /** Message of the failure an attempt timeout ends the call with. */
    static final String TIMED_OUT = "The HTTP attempt timed out";

    private final Context context;
    private final Vertx vertx;
    private final @Nullable CancellationSignal signal;
    private final long timeoutMs;
    private final HttpClientInterceptors interceptors;
    private final @Nullable ResiliencePipeline pipeline;
    private final Function<AttemptScope, WebClient> webClients;
    private final Consumer<CallExecution<?>> onSettled;
    private final String callId = UUID.randomUUID().toString();
    private final Promise<T> promise = Promise.promise();
    private final AtomicReference<Throwable> cancelReason = new AtomicReference<>();
    private final @Nullable ResilienceCancellation cancellation;
    private Function<WebClient, Future<T>> attemptFunction;
    private Attempt current;
    private int ordinal;
    private boolean settled;

    CallExecution(
            Vertx vertx,
            Context context,
            @Nullable CancellationSignal signal,
            long timeoutMs,
            HttpClientInterceptors interceptors,
            @Nullable ResiliencePipeline pipeline,
            Function<AttemptScope, WebClient> webClients,
            Consumer<CallExecution<?>> onSettled) {
        this.vertx = vertx;
        this.context = context;
        this.signal = signal;
        this.timeoutMs = timeoutMs;
        this.interceptors = interceptors;
        this.pipeline = pipeline;
        this.webClients = webClients;
        this.onSettled = onSettled;
        this.cancellation = pipeline == null ? null : ResilienceCancellation.create();
    }

    /**
     * Starts the call.
     *
     * @param attempt builds one attempt's result from that attempt's scoped web client
     * @return the future the caller observes
     */
    Future<T> start(Function<WebClient, Future<T>> attempt) {
        this.attemptFunction = attempt;
        Future<T> future = promise.future();
        onContext(this::run);
        return future;
    }

    private void run() {
        if (settled) {
            return;
        }
        if (signal != null && !signal.register(this)) {
            settle(Future.failedFuture(new HttpExchangeCancelledException()));
            return;
        }
        Future<T> result;
        try {
            result = pipeline == null ? beginAttempt() : pipeline.execute(this::supplier, cancellation);
        } catch (RuntimeException failure) {
            result = Future.failedFuture(failure);
        }
        result.onComplete(outcome -> onContext(() -> settle(outcome)));
    }

    /** The pipeline's supplier: one attempt, started on the call's context. */
    private Future<T> supplier() {
        if (Vertx.currentContext() == context) {
            return beginAttempt();
        }
        Promise<T> hop = Promise.promise();
        context.runOnContext(ignored -> beginAttempt().onComplete(hop));
        return hop.future();
    }

    private Future<T> beginAttempt() {
        if (settled) {
            Throwable reason = cancelReason.get();
            return Future.failedFuture(reason != null ? reason : new HttpExchangeCancelledException());
        }
        Attempt previous = current;
        if (previous != null) {
            // A pipeline's own timer gave up on the previous attempt without telling the client.
            previous.abandon(new TimeoutException(TIMED_OUT));
        }
        Attempt attempt = new Attempt(++ordinal);
        current = attempt;
        attempt.start();
        return attempt.promise.future();
    }

    /** Cancels the call from any thread: hops onto the call's context; the first ending wins. */
    @Override
    public void cancel() {
        cancel(new HttpExchangeCancelledException());
    }

    /**
     * Cancels the call with {@code reason} from any thread. The reason is fixed at once, so a failure the
     * cancellation itself causes never replaces it.
     */
    void cancel(Throwable reason) {
        if (!cancelReason.compareAndSet(null, reason)) {
            return;
        }
        if (cancellation != null) {
            cancellation.cancel(reason);
        }
        context.runOnContext(ignored -> endByCancel(reason));
    }

    private void endByCancel(Throwable reason) {
        if (settled) {
            return;
        }
        if (current != null) {
            current.abandon(reason);
        }
        if (pipeline == null) {
            settle(Future.failedFuture(reason));
        }
    }

    /**
     * Ends the call with {@code outcome}: leaves the signal, ends the attempt that is still running
     * and only then completes the caller's future with the
     * original failure. Later endings do nothing.
     */
    private void settle(AsyncResult<T> outcome) {
        if (settled) {
            return;
        }
        settled = true;
        if (signal != null) {
            signal.deregister(this);
        }
        Throwable failure = outcome.failed() ? outcome.cause() : null;
        Throwable reason = cancelReason.get();
        if (failure != null && reason != null) {
            failure = reason;
        }
        if (current != null) {
            current.abandon(failure instanceof ResilienceTimeoutException ? new TimeoutException(TIMED_OUT) : failure);
        }
        onSettled.accept(this);
        if (failure != null) {
            promise.fail(failure);
        } else {
            promise.complete(outcome.result());
        }
    }

    private void onContext(Runnable action) {
        if (Vertx.currentContext() == context) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
    }

    /** One attempt: its scope, its timer and the future the pipeline observes. */
    private final class Attempt {

        private final AttemptScope scope;
        private final Promise<T> promise = Promise.promise();
        private long timerId = -1;
        private boolean done;

        private Attempt(int ordinal) {
            this.scope = new AttemptScope(interceptors, callId, ordinal);
        }

        private void start() {
            WebClient web = webClients.apply(scope);
            if (timeoutMs > 0) {
                timerId = vertx.setTimer(timeoutMs, id -> onContext(this::timedOut));
            }
            Future<T> result;
            try {
                result = attemptFunction.apply(web);
                if (result == null) {
                    result = Future.failedFuture(new IllegalStateException("The attempt returned no future"));
                }
            } catch (RuntimeException failure) {
                scope.refusedWithoutSending(failure);
                result = Future.failedFuture(failure);
            }
            result.onComplete(outcome -> onContext(() -> finish(outcome)));
        }

        /** The attempt timer fired: fails the attempt with the timeout the call's pipeline expects. */
        private void timedOut() {
            if (done) {
                return;
            }
            finish(Future.failedFuture(new TimeoutException(TIMED_OUT)));
        }

        /** The attempt's future settled, or its timer fired: ends the attempt's scope, then its future. */
        private void finish(AsyncResult<T> outcome) {
            if (done) {
                return;
            }
            done = true;
            cancelTimer();
            Throwable original = outcome.failed() ? outcome.cause() : null;
            Throwable reason = cancelReason.get();
            if (original != null && reason != null) {
                original = reason;
            }
            Throwable reported = original == null || reason != null ? original : classified(original);
            scope.end(original);
            if (original == null) {
                promise.complete(outcome.result());
            } else {
                promise.fail(reported);
            }
        }

        /**
         * Applies the client's one classification to a failure of a pipeline execution: a refusal before the
         * first hop's request was written ends the execution through its cancellation, so no pipeline
         * retries it.
         */
        private Throwable classified(Throwable failure) {
            if (pipeline != null && scope.wasRefusedBeforeSend(failure)) {
                cancellation.cancel(failure);
            }
            return failure;
        }

        /** Ends an attempt that no longer matters: resets what is open and stops accepting requests. */
        private void abandon(@Nullable Throwable cause) {
            if (done) {
                return;
            }
            done = true;
            cancelTimer();
            scope.end(cause);
            promise.tryFail(cause != null ? cause : new HttpExchangeCancelledException());
        }

        private void cancelTimer() {
            if (timerId >= 0) {
                vertx.cancelTimer(timerId);
            }
        }
    }
}
