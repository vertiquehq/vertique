// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * REST-owned request-completion event infrastructure.
 *
 * <p>This package provides two transport source events, each emitted exactly once per request it
 * covers, on every success and failure path:
 * {@link dev.vertique.rest.core.events.RestRequestCompletedEvent} for a request that a JAX-RS
 * operation route claimed, and {@link dev.vertique.rest.core.events.HttpRequestCompletedEvent} for
 * a request that no transport claimed. A request that another transport claimed produces no
 * rest-core event; it is observed through that transport's own events. The events are intended to
 * be consumed by independent metrics, analytics, and operational observers — none of which are
 * responsible for wiring the emission.
 *
 * <p>The events are emitted by {@link dev.vertique.rest.core.events.RestRequestCompletionEmitter}, a
 * ROOT-scoped middleware. Consumers implement
 * {@link dev.vertique.rest.core.events.RestRequestCompletedListener} to observe JAX-RS operations
 * and {@link dev.vertique.rest.core.events.HttpRequestCompletedListener} to observe unclaimed
 * requests, contributed through their Dagger {@code Set} multibindings. A
 * {@link dev.vertique.rest.core.events.RequestCompletionScope} brackets the dispatch of either
 * event type.
 *
 * <p>Both listeners declare {@code onCompleted(event)} and a default
 * {@code onCompleted(event, RoutingContext)} that delegates to it; the emitter calls the
 * two-argument overload, with the request's live root routing context. A listener that needs only
 * the event implements the one-argument method, for example as a lambda. A listener that needs
 * per-request state or the live request overrides the two-argument overload and keys that state by
 * {@code routingContext.request()}, never by the {@code RoutingContext} object, because a
 * sub-router route handler receives a different {@code RoutingContext} wrapper around the same
 * request. The overload serves every completion consumer, audit included; no separate audit-only
 * completion SPI exists.
 */
package dev.vertique.rest.core.events;
