// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Framework-level event bus client for service dispatch protocol interactions.
 *
 * <p>Provides two transport operations:
 * <ul>
 *   <li><strong>request/reply</strong> — sends a {@link DispatchEnvelope} and waits for a
 *       {@link Result} reply from the handler. Failed replies are translated to typed exceptions
 *       via {@link EventBusExceptionMapper}.
 *   <li><strong>fire-and-forget</strong> — sends a {@link DispatchEnvelope} without waiting for
 *       any reply. Used for one-way notifications and cron/delayed-job dispatch protocols.
 * </ul>
 *
 * <p>All messages use the {@code dispatch.envelope} codec, which must be registered at startup
 * via {@link LocalMessageCodec}.
 *
 * <p>This is a low-level transport abstraction. Service-aware callers should use
 * {@code ServiceRequestSender} (in {@code vertique-services}), which adds supervisor checks,
 * resilience timeout computation, and service-specific exception enrichment.
 *
 * @see EventBusExceptionMapper
 * @see DispatchEnvelope
 * @see Result
 */
@Singleton
public class EventBusClient {

    // --- Constants ---

    /** Shared delivery options for fire-and-forget sends (no send timeout). */
    private static final DeliveryOptions SEND_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Fields ---

    private final Vertx vertx;
    private final EventBusExceptionMapper exceptionMapper;

    /** Cache of delivery options keyed by send timeout to avoid per-request allocation. */
    private final ConcurrentMap<Long, DeliveryOptions> optionsByTimeout = new ConcurrentHashMap<>();

    // --- Constructor ---

    /**
     * Constructs a new {@code EventBusClient}.
     *
     * @param vertx           the Vert.x instance
     * @param exceptionMapper the mapper used to translate {@link io.vertx.core.eventbus.ReplyException}s
     *                        into typed event bus exceptions
     */
    @Inject
    public EventBusClient(Vertx vertx, EventBusExceptionMapper exceptionMapper) {
        this.vertx = vertx;
        this.exceptionMapper = exceptionMapper;
    }

    // --- Public API ---

    /**
     * Sends a request to the given event bus address and waits for a {@link Result} reply.
     *
     * <p>Uses the {@code dispatch.envelope} codec. If the request fails with a
     * {@link io.vertx.core.eventbus.ReplyException}, the cause is translated to a typed
     * exception via {@link EventBusExceptionMapper} before the future is failed.
     *
     * @param address       the event bus address to send the request to
     * @param envelope      the request envelope carrying the payload and context
     * @param sendTimeoutMs the maximum number of milliseconds to wait for a reply
     * @return a {@link Future} that completes with the handler's {@link Result}, or fails with a
     *         typed event bus exception if the request could not be delivered or timed out
     */
    public Future<Result<?>> request(String address, DispatchEnvelope<?> envelope, long sendTimeoutMs) {
        DeliveryOptions options = optionsByTimeout.computeIfAbsent(
                sendTimeoutMs,
                t -> new DeliveryOptions().setCodecName("dispatch.envelope").setSendTimeout(t));
        @SuppressWarnings("unchecked")
        Future<Result<?>> future = (Future<Result<?>>) (Future<?>) vertx.eventBus()
                .<Object>request(address, envelope, options)
                .map(reply -> (Result<?>) reply.body())
                .recover(cause -> Future.failedFuture(exceptionMapper.translate(cause, address)));
        return future;
    }

    /**
     * Sends a fire-and-forget message to the given event bus address.
     *
     * <p>No reply is expected or awaited. Uses the {@code dispatch.envelope} codec with no send
     * timeout. Suitable for one-way notifications and cron/delayed-job dispatch protocols where
     * result delivery is handled via a separate reply-address consumer.
     *
     * @param address  the event bus address to send the message to
     * @param envelope the message envelope carrying the payload and context
     */
    public void send(String address, DispatchEnvelope<?> envelope) {
        vertx.eventBus().send(address, envelope, SEND_OPTIONS);
    }
}
