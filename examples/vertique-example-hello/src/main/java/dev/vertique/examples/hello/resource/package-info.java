// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JAX-RS resource classes and supporting types for the hello example application. Includes
 * {@code HelloResource}, a JAX-RS resource with greeting endpoints that demonstrate path
 * parameters, query parameters, JWT-secured routes, and a real {@code @RateLimited} endpoint
 * (admitted through the real rate-limit runtime and mapped to {@code 429} by {@code
 * vertique-rest-rate-limit}'s exception mapper, not a hand-rolled error response — the prototype
 * {@code GreetingLimitExceededException}/{@code GreetingLimitExceptionMapper}/{@code
 * GreetingLimitProblemDetail} scaffolding this endpoint used before is retired, T013); and
 * {@code ResourceModule}, the Dagger module that contributes non-resource JAX-RS extension
 * bindings — resource instances themselves are auto-generated into the
 * {@code @JaxRsResources} multibinding so they are discovered and registered at startup.
 */
package dev.vertique.examples.hello.resource;
