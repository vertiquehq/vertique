// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transparent pass-through decorator that counts how many times a chosen {@link Command} is sent,
 * used to prove Vertique never issues an extra logical attempt of its own around Bucket4j's
 * internal loop (TP-002).
 */
final class CountingRedis implements Redis {

    private final Redis delegate;
    private final Command counted;
    private final AtomicInteger count = new AtomicInteger();

    CountingRedis(Redis delegate, Command counted) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.counted = Objects.requireNonNull(counted, "counted");
    }

    int count() {
        return count.get();
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
        if (request.command() == counted) {
            count.incrementAndGet();
        }
        return delegate.send(request);
    }
}
