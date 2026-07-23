// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Declarative REST client module that creates type-safe HTTP client proxies from JAX-RS-annotated
 * interfaces, backed by Vert.x WebClient.
 *
 * <p>The primary entry point is {@link dev.vertique.rest.client.RestClientFactory}, which
 * creates proxy instances from interfaces annotated with
 * {@link dev.vertique.rest.client.RestClient}. The factory integrates with Dagger 2 via
 * {@link dev.vertique.rest.client.RestClientModule}.
 *
 * <p>Extension points:
 * <ul>
 *   <li>{@link dev.vertique.rest.client.interceptor.RestClientInterceptor} — cross-cutting
 *       request/response logic (auth headers, logging, tracing)</li>
 *   <li>{@link dev.vertique.rest.client.RestClientExceptionMapper} — translates transport and
 *       HTTP error exceptions to domain exceptions; the default implementation
 *       {@link dev.vertique.rest.client.DefaultRestClientExceptionMapper} handles common
 *       network errors out of the box</li>
 * </ul>
 */
package dev.vertique.rest.client;
