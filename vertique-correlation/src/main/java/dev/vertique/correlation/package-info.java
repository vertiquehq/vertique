// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique correlation runtime: the mutable holder-bound {@code CorrelationContext} implementation,
 * factory, mutator, MDC integration keys, default {@code CorrelationIdGenerator}, and Dagger module
 * that wires correlation into the framework's context-propagation substrate.
 *
 * <p>The public read-only API ({@link dev.vertique.core.correlation.CorrelationContext},
 * {@link dev.vertique.core.correlation.CorrelationContextSnapshot}, the value records, the
 * {@link dev.vertique.core.correlation.CorrelationIdGenerator} SPI, and
 * {@link dev.vertique.core.correlation.CorrelationHeaderValidator}) lives in {@code vertique-core}.
 * This module owns the runtime: it never appears on the API surface that application handlers see.
 *
 * <p>Module-boundary rule: this module depends on {@code vertique-core} and {@code vertique-logging}
 * (for {@code MDCContexts}); REST-specific ingress lives in {@code vertique-rest-core}. The substrate
 * (context holder, registries, propagator) must never depend on this module.
 */
package dev.vertique.correlation;
