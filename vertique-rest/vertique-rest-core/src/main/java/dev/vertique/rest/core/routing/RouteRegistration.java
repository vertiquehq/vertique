// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.routing;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Per-operation registration surface handed to operation-handler contributors.
 *
 * <p>Contributors add handlers that run, in registration order, before the terminal operation
 * invoker. This is the neutral replacement for the per-operation portion of the previous
 * {@code RouterBuilder} surface, scoped to a single operation.
 */
public interface RouteRegistration {

    /**
     * Adds a handler to this operation's route. Handlers run in registration order, before the
     * terminal operation invoker.
     *
     * @param handler the handler to add
     * @return this registration, for fluent chaining
     */
    RouteRegistration addHandler(Handler<RoutingContext> handler);

    /**
     * Returns the descriptor for the operation being registered, giving contributors access to its
     * identity, security policy, and annotations.
     *
     * @return the non-null operation descriptor
     */
    RestOperationDescriptor operation();
}
