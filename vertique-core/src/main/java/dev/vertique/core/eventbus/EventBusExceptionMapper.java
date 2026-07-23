// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import io.vertx.core.eventbus.ReplyException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Translates raw Vert.x {@link ReplyException} instances into typed event bus exceptions.
 *
 * <p>The set of {@link io.vertx.core.eventbus.ReplyFailure} types is closed (a fixed enum), so
 * this mapper is not extensible. Injectable for testability — callers receive the mapper via
 * constructor injection rather than using it as a static utility.
 *
 * <p>Translation rules:
 * <ul>
 *   <li>{@link io.vertx.core.eventbus.ReplyFailure#TIMEOUT} →
 *       {@link EventBusTimeoutException}
 *   <li>{@link io.vertx.core.eventbus.ReplyFailure#NO_HANDLERS} →
 *       {@link EventBusAddressUnavailableException}
 *   <li>{@link io.vertx.core.eventbus.ReplyFailure#RECIPIENT_FAILURE} →
 *       {@link EventBusDispatchException}
 *   <li>{@link io.vertx.core.eventbus.ReplyFailure#ERROR} →
 *       {@link EventBusDispatchException}
 * </ul>
 *
 * <p>Non-{@link ReplyException} throwables are returned as-is.
 *
 * @see EventBusClient
 */
@Singleton
public class EventBusExceptionMapper {

    /** Creates a new mapper instance. */
    @Inject
    public EventBusExceptionMapper() {}

    /**
     * Translates a throwable into a typed event bus exception if it is a {@link ReplyException};
     * otherwise returns the original throwable unchanged.
     *
     * @param cause   the throwable to translate — typically from a failed event bus request
     * @param address the event bus address that was the target of the request
     * @return a typed {@link EventBusTimeoutException}, {@link EventBusAddressUnavailableException},
     *         or {@link EventBusDispatchException} when {@code cause} is a {@link ReplyException};
     *         otherwise the original {@code cause}
     */
    public Throwable translate(Throwable cause, String address) {
        if (cause instanceof ReplyException re) {
            return switch (re.failureType()) {
                case TIMEOUT -> new EventBusTimeoutException(address, re);
                case NO_HANDLERS -> new EventBusAddressUnavailableException(address, re);
                case RECIPIENT_FAILURE, ERROR -> new EventBusDispatchException(address, re.getMessage(), re);
            };
        }
        return cause;
    }
}
