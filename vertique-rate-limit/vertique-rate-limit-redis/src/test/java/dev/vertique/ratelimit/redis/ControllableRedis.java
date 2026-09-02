// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic barrier-fake in front of a real {@code io.vertx.redis.client.Redis} connection
 * (mirroring {@code vertique-cache-redis}'s Barrier-style controllable-fake idiom, T009's
 * contracted seam). Every {@link Request} whose {@link Command} matches {@link #intercepted} is
 * captured as a {@link Held} call instead of being sent — held indefinitely until a test explicitly
 * {@link Held#release() releases} it, at which point the original request is forwarded verbatim to
 * the real delegate and the held call completes with that genuine response (or failure).
 *
 * <p>Every other command (notably {@code GET}, and any command issued by this module's own
 * {@code PEXPIRE} write) passes straight through to the real delegate, unmodified — this fake
 * controls only the decisive CAS round-trip, never the surrounding reads/writes, so held/released
 * calls still resolve against genuine Redis state.
 *
 * <p>Since the same {@code Redis} instance is what both {@code Bucket4jVertx.casBasedBuilder(...)}
 * and this module's own {@code RedisAPI.pexpire(...)} write through, one fake instance can control
 * every command this backend issues by choosing which {@link Command} to intercept.
 */
final class ControllableRedis implements Redis {

    private final Redis delegate;
    private final Command intercepted;
    private final BlockingQueue<Held> pending = new LinkedBlockingQueue<>();
    private final AtomicInteger arrivals = new AtomicInteger();

    ControllableRedis(Redis delegate, Command intercepted) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.intercepted = Objects.requireNonNull(intercepted, "intercepted");
    }

    @Override
    public Future<RedisConnection> connect() {
        return delegate.connect();
    }

    @Override
    public Future<Void> close() {
        return delegate.close();
    }

    @Override
    public Future<List<Response>> batch(List<Request> commands) {
        return delegate.batch(commands);
    }

    @Override
    public Future<Response> send(Request request) {
        if (request.command() != intercepted) {
            return delegate.send(request);
        }
        Promise<Response> promise = Promise.promise();
        long arrivedAtNanos = System.nanoTime();
        Held held = new Held(request, promise, arrivedAtNanos);
        arrivals.incrementAndGet();
        pending.add(held);
        return promise.future();
    }

    /** Total number of intercepted-command calls this fake has ever received. */
    int totalArrivals() {
        return arrivals.get();
    }

    /** Number of intercepted calls currently held (arrived but not yet released). */
    int pendingCount() {
        return pending.size();
    }

    /**
     * Blocks the calling (test) thread until at least one intercepted call has arrived and removes
     * it from the pending queue, in arrival order.
     *
     * @param timeoutSeconds bounded wait
     * @return the held call
     */
    Held awaitNextArrival(long timeoutSeconds) throws InterruptedException {
        Held held = pending.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (held == null) {
            throw new AssertionError("timed out waiting for an intercepted " + intercepted + " call to arrive");
        }
        return held;
    }

    /** One intercepted command, captured before it ever reached the real Redis connection. */
    final class Held {
        private final Request request;
        private final Promise<Response> promise;
        private final long arrivedAtNanos;

        private Held(Request request, Promise<Response> promise, long arrivedAtNanos) {
            this.request = request;
            this.promise = promise;
            this.arrivedAtNanos = arrivedAtNanos;
        }

        long arrivedAtNanos() {
            return arrivedAtNanos;
        }

        /**
         * Forwards the original request to the real delegate and completes with its genuine
         * response.
         *
         * @return a future that completes once the forwarded round-trip itself has settled (not
         *     once every downstream consumer of it has reacted) — tests awaiting a fully-settled
         *     discard proof should still add a small additional grace window on top of this
         */
        Future<Response> release() {
            Future<Response> forwarded = delegate.send(request);
            forwarded.onComplete(result -> {
                if (result.succeeded()) {
                    promise.tryComplete(result.result());
                } else {
                    promise.tryFail(result.cause());
                }
            });
            return forwarded;
        }

        /** Completes this held call with a synthetic failure instead of forwarding it. */
        void releaseFailure(Throwable cause) {
            promise.tryFail(cause);
        }
    }
}
