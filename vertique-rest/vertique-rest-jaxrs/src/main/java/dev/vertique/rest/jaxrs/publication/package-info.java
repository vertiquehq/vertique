// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam for the documentation module: installing framework-owned synthetic
 * operations through the resource security chain.
 *
 * <p>Every type here is public only so sibling framework modules — starting with the OpenAPI
 * documentation module — can install a route that runs exactly the chain an equally annotated
 * JAX-RS resource method gets (the scheme's authentication handler, every registered {@code
 * OperationHandlerContributor} in resource order, then a caller-supplied terminal handler). It is
 * outside the maturity promise and is not an application contract: an application never depends
 * on this package directly.
 *
 * <p>The implementation, a package-private installer in {@code dev.vertique.rest.jaxrs}, is bound
 * by {@code @Binds} in {@code RestModule}.
 */
package dev.vertique.rest.jaxrs.publication;
