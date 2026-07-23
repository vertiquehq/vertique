// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

/**
 * Application-facing factory for creating per-request {@link SseChannel} instances.
 *
 * <p>{@link SseChannelFactory} is injected into JAX-RS resources that need to emit
 * Server-Sent Events. Each call to {@link #create()} or {@link #create(SseChannelOptions)}
 * returns a fresh channel bound to the current request context. The returned channel's
 * {@link SseChannel#stream()} should be returned directly from the resource method.
 *
 * <p>Example:
 * <pre>{@code
 * @Inject
 * SseChannelFactory channelFactory;
 *
 * @GET
 * @Path("/notifications")
 * @Produces("text/event-stream")
 * @Operation(operationId = "streamNotifications")
 * public ReadStream<SseEvent> stream() {
 *     SseChannel channel = channelFactory.create();
 *     notificationService.subscribe(n -> channel.send(SseEvent.of(n)));
 *     channel.onClose(() -> notificationService.unsubscribe());
 *     return channel.stream();
 * }
 * }</pre>
 */
public interface SseChannelFactory {

    /**
     * Creates a new {@link SseChannel} using the default options from the application's
     * {@link dev.vertique.rest.core.config.SseConfig} configuration.
     *
     * @return a new channel ready to accept events
     */
    SseChannel create();

    /**
     * Creates a new {@link SseChannel} with the supplied per-channel options, overriding any
     * application-level defaults for buffer size and overflow policy.
     *
     * @param options per-channel configuration overrides; must not be {@code null}
     * @return a new channel configured with the given options
     */
    SseChannel create(SseChannelOptions options);
}
