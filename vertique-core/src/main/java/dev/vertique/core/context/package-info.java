// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Context propagation substrate for vertique-core.
 *
 * <p>Provides a request-scoped {@link dev.vertique.core.context.ContextHolder} backed by Vert.x
 * context-local storage, separate SPI families for service-dispatch and durable metadata
 * propagation, validating registries, and a durable propagation orchestrator.
 *
 * <p>Framework boundary modules depend only on the generic SPIs in this package. Consumer
 * contracts such as {@code LocalizationContext}, {@code CorrelationContext}, or security metadata
 * live in their own modules.
 */
package dev.vertique.core.context;
