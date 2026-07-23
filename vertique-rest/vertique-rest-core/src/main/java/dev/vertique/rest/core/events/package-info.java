// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * REST-owned request-completion event infrastructure.
 *
 * <p>This package provides a transport source event ({@link dev.vertique.rest.core.events.RestRequestCompletedEvent})
 * that is emitted exactly once per handled request, covering all success and failure paths. It is
 * intended to be consumed by independent metrics, analytics, and operational observers — none of
 * which are responsible for wiring the emission.
 *
 * <p>The event is emitted by {@link dev.vertique.rest.core.events.RestRequestCompletionEmitter}, a
 * ROOT-scoped middleware. Consumers implement
 * {@link dev.vertique.rest.core.events.RestRequestCompletedListener} and are contributed via Dagger
 * multibinding.
 */
package dev.vertique.rest.core.events;
