// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Interceptor SPI for cross-cutting concerns in the REST client pipeline.
 *
 * <p>Interceptors are applied to every request and response made through a client proxy.
 * Implement {@link dev.vertique.rest.client.interceptor.RestClientInterceptor} and register
 * instances either globally via Dagger multibinding or per-client via
 * {@link dev.vertique.rest.client.RestClientBuilder#register(RestClientInterceptor)}.
 */
package dev.vertique.rest.client.interceptor;
