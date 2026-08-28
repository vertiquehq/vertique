// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared resilience policy primitives and executable runtime foundation used by Vertique
 * integrations.
 *
 * <p>The package contains engine-neutral policy vocabulary together with the application-scoped
 * {@link dev.vertique.resilience.Resilience} runtime, timeout components, and fixed-order
 * {@link dev.vertique.resilience.ResiliencePipeline} composition. The runtime uses Vert.x contexts
 * and timers while keeping later policy concerns independently composable.
 *
 * <h2>Policy primitives</h2>
 *
 * <ul>
 *   <li>{@link dev.vertique.resilience.BackoffStrategy} computes retry delays.</li>
 *   <li>{@link dev.vertique.resilience.RetryPolicy} determines retry eligibility.</li>
 * </ul>
 *
 * <p>Canonical annotations and their engine-neutral declaration metadata live in
 * {@link dev.vertique.resilience.annotation}.
 */
package dev.vertique.resilience;
