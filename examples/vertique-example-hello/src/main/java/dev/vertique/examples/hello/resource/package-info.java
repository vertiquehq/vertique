// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JAX-RS resource classes and supporting types for the hello example application. Includes
 * {@code HelloResource}, a JAX-RS resource with greeting endpoints that demonstrate path
 * parameters, query parameters, JWT-secured routes, and custom error responses;
 * {@code GreetingLimitExceededException}, a domain exception with no HTTP dependency that is
 * mapped to a structured Problem Detail response by a paired {@code ExceptionMapper}
 * implementation; a {@code @SuperBuilder} subclass of
 * {@link dev.vertique.rest.ProblemDetail} used as the custom error body for limit-exceeded
 * responses; and {@code ResourceModule}, the Dagger module that contributes all resource
 * instances to the {@code @JaxRsResources} multibinding so they are discovered and registered
 * at startup.
 */
package dev.vertique.examples.hello.resource;
