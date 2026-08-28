// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared resilience policy primitives used by Vertique integrations.
 *
 * <p>This package contains pure-Java vocabulary. Runtime construction and execution remain in the
 * consuming modules; the policy types in this package do not depend on a selected execution engine.
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
