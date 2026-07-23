// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Resilience policy runtime implementations for the services dispatch pipeline.
 *
 * <p>Annotations ({@link dev.vertique.core.resilience.CircuitBreaker},
 * {@link dev.vertique.core.resilience.Retry},
 * {@link dev.vertique.core.resilience.Timeout}) from the core resilience package are placed on
 * contract interface methods. {@link dev.vertique.services.policy.PolicyChainBuilder} reads
 * annotations and config overrides to build a
 * {@link dev.vertique.services.dispatch.DispatchPipeline}
 * of {@link dev.vertique.services.policy.PolicyStage} instances.
 */
package dev.vertique.services.policy;
