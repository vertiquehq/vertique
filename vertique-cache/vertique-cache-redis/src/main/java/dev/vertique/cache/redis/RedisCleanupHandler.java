// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.Result;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import java.util.Objects;

/** Handles cron dispatches for the provider-owned physical cleanup sweep. */
final class RedisCleanupHandler {
    private final RedisCleanupJob job;
    private final EventBusClient eventBus;

    RedisCleanupHandler(RedisCleanupJob job, EventBusClient eventBus) {
        this.job = Objects.requireNonNull(job, "job");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    MessageConsumer<Object> register(Vertx vertx) {
        return Objects.requireNonNull(vertx, "vertx")
                .eventBus()
                .consumer(RedisCleanupJob.HANDLER_ADDRESS, this::handle);
    }

    private void handle(Message<Object> message) {
        if (!(message.body() instanceof DispatchEnvelope<?> envelope)) {
            return;
        }
        Future<RedisCleanupJob.CleanupResult> sweep;
        try {
            sweep = job.sweep();
        } catch (Throwable failure) {
            reply(envelope, Result.failure(failure));
            return;
        }
        if (sweep == null) {
            reply(envelope, Result.failure(new IllegalStateException("cleanup sweep returned null future")));
            return;
        }
        sweep.onComplete(result -> {
            if (result.succeeded()) {
                reply(envelope, Result.success(result.result()));
            } else {
                reply(envelope, Result.failure(result.cause()));
            }
        });
    }

    private void reply(DispatchEnvelope<?> envelope, Result<?> result) {
        envelope.replyAddress().ifPresent(address -> eventBus.send(address, DispatchEnvelope.of(result)));
    }
}
