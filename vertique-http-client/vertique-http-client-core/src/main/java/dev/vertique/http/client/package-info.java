// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Governed outbound HTTP client built on the Vert.x {@code WebClient}.
 *
 * <p>{@link dev.vertique.http.client.VertiqueHttpClientBuilder} creates a
 * {@link dev.vertique.http.client.VertiqueHttpClient}. The client hands out Vert.x
 * {@code WebClient} instances through {@link dev.vertique.http.client.VertiqueHttpClient#webClient()}
 * and {@link dev.vertique.http.client.VertiqueHttpClient#execute(java.util.function.Function)}; callers
 * build and send requests with the Vert.x {@code WebClient}, {@code HttpRequest} and
 * {@code HttpResponse} API.
 */
package dev.vertique.http.client;
