// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Canonical resilience annotations and engine-neutral declaration metadata.
 *
 * <p>{@link dev.vertique.resilience.annotation.Timeout},
 * {@link dev.vertique.resilience.annotation.Retry},
 * {@link dev.vertique.resilience.annotation.CircuitBreaker}, and
 * {@link dev.vertique.resilience.annotation.Bulkhead} are configuration annotations for types and
 * methods. {@link dev.vertique.resilience.annotation.Resilient} is the method-level resilience
 * anchor and may name a policy tier. {@link dev.vertique.resilience.annotation.ResilienceAnnotations}
 * resolves declarations and the named policy into immutable metadata for generated and reflective
 * consumers.
 */
package dev.vertique.resilience.annotation;
