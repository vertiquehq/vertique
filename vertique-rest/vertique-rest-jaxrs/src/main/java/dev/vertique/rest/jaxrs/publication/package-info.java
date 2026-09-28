// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam for the documentation module: installing framework-owned synthetic
 * operations through the resource security chain, and publishing the declared {@code
 * @RestApplication} composition for sibling framework modules to read.
 *
 * <p>Every type here is public only so sibling framework modules — starting with the OpenAPI
 * documentation module — can read this composition or install a route that runs exactly the chain
 * an equally annotated JAX-RS resource method gets (the scheme's authentication handler, every
 * registered {@code OperationHandlerContributor} in resource order, then a caller-supplied
 * terminal handler). It is outside the maturity promise and is not an application contract: an
 * application never depends on this package directly.
 *
 * <p>The synthetic-operation installer, a package-private type in {@code dev.vertique.rest.jaxrs},
 * is bound by {@code @Binds} in {@code RestModule}. {@link
 * dev.vertique.rest.jaxrs.publication.RestApplications} is the component-scoped {@code @Singleton}
 * view over every declared {@code @RestApplication}, built once per component and provided by
 * {@code RestModule}; {@link dev.vertique.rest.jaxrs.publication.ApiDocsInstalled} is the marker
 * this package detects, through {@code @BindsOptionalOf}, to know whether the OpenAPI documentation
 * module is present in the component.
 */
package dev.vertique.rest.jaxrs.publication;
