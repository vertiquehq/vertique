// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Event bus service dispatch with contract-first interfaces and resilience policies.
 *
 * <p>Annotate a service contract interface with {@link dev.vertique.services.ServiceContract} and implement it.
 * The framework scans implementations, registers event bus consumers with policy
 * pipelines (circuit breaker, retry, timeout), and generates type-safe client proxies.
 */
package dev.vertique.services;
