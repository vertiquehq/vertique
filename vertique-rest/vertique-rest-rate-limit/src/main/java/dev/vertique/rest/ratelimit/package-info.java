// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * REST HTTP mapping for the provider-neutral rate-limit runtime, plus an optional pre-
 * authorization edge admission {@code Middleware} (contracts/rest-adapter.md).
 *
 * <p>This adapter owns exactly two things:
 * <ol>
 *   <li>{@link dev.vertique.rest.ratelimit.RateLimitExceptionMapper} — maps {@code
 *       RateLimitExceededException}/{@code RateLimitUnavailableException} (raised by the
 *       {@code @RateLimited} aspect or programmatic {@code execute()} calls) to {@code 429}/
 *       {@code 503}, contributed unconditionally.</li>
 *   <li>{@link dev.vertique.rest.ratelimit.RateLimitEdgeMiddleware} — an optional, config-driven
 *       {@code MiddlewareScope.ROOT} handler that applies ordered composable-dimension admission
 *       rules ({@code GLOBAL}/{@code IP}/{@code HEADER}) before any application code, including
 *       authentication, runs. Contributed only when {@code rateLimit.rest.edge.enabled} is
 *       {@code true}.</li>
 * </ol>
 *
 * <p>It introduces no {@code vertique-rest-core}/{@code vertique-rest-jaxrs} contract change.
 * Include {@link dev.vertique.rest.ratelimit.RestRateLimitModule} in the application Dagger
 * {@code @Component} to activate both.
 */
package dev.vertique.rest.ratelimit;
