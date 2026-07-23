// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.sse.SseChannel;
import dev.vertique.rest.core.sse.SseChannelFactory;
import dev.vertique.rest.core.sse.SseChannelOptions;
import io.vertx.core.Vertx;

/**
 * Default implementation of {@link SseChannelFactory} that creates {@link DefaultSseChannel}
 * instances.
 *
 * <p>The factory is registered as a singleton in Dagger via {@link RestModule} and can be injected
 * into any JAX-RS resource that needs to emit Server-Sent Events. Each call to {@link #create()}
 * or {@link #create(SseChannelOptions)} returns a fresh, independent channel.
 *
 * <p>Default channel settings ({@link #create()}) are sourced from the application's
 * {@link SseConfig} (the {@code "jaxrs.sse"} config section). Per-channel overrides can be
 * provided via {@link #create(SseChannelOptions)}.
 */
class DefaultSseChannelFactory implements SseChannelFactory {

    private final Vertx vertx;
    private final SseConfig config;

    /**
     * Creates a factory with the given Vert.x instance and SSE configuration.
     *
     * @param vertx  the Vert.x instance used when constructing channels
     * @param config the application-level SSE configuration supplying default buffer size and
     *               overflow policy
     */
    DefaultSseChannelFactory(Vertx vertx, SseConfig config) {
        this.vertx = vertx;
        this.config = config;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@link DefaultSseChannel} using the default buffer size and overflow policy
     * from the application's {@link SseConfig}.
     *
     * @return a new channel configured with application defaults
     */
    @Override
    public SseChannel create() {
        int bufferSize = Math.max(1, config.defaultBufferSize());
        return new DefaultSseChannel(vertx, bufferSize, config.defaultOverflowPolicy());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Creates a {@link DefaultSseChannel} using the buffer size and overflow policy from the
     * supplied {@link SseChannelOptions}, ignoring application defaults.
     *
     * @param options per-channel configuration overrides; must not be {@code null}
     * @return a new channel configured with the given options
     */
    @Override
    public SseChannel create(SseChannelOptions options) {
        return new DefaultSseChannel(vertx, options.bufferSize(), options.overflowPolicy());
    }
}
