// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Canonical resilience annotations and engine-neutral declaration metadata.
 *
 * <p>{@link dev.vertique.resilience.annotation.Timeout},
 * {@link dev.vertique.resilience.annotation.Retry}, and
 * {@link dev.vertique.resilience.annotation.CircuitBreaker} are configuration annotations for
 * types and methods. {@link dev.vertique.resilience.annotation.ResilienceAnnotations} resolves
 * them into immutable declaration records for generated and reflective consumers.
 */
package dev.vertique.resilience.annotation;
