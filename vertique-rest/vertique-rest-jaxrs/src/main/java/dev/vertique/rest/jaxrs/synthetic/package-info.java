// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam for installing framework-owned synthetic operations through the resource
 * security chain.
 *
 * <p>An installed route runs exactly the chain an equally annotated JAX-RS resource method gets:
 * the scheme's authentication handler, every registered {@code OperationHandlerContributor} in
 * resource order, then a caller-supplied terminal handler. {@link
 * dev.vertique.rest.jaxrs.synthetic.SyntheticOperation} describes one such operation and {@link
 * dev.vertique.rest.jaxrs.synthetic.SyntheticOperationInstaller} installs it. The implementation is a
 * package-private type in {@code dev.vertique.rest.jaxrs}, bound by {@code @Binds} in {@code
 * RestModule}, because it builds on that package's package-private security-policy and route
 * validation internals.
 *
 * <p>Every type here is public only so sibling framework modules — starting with the OpenAPI
 * documentation module — can install such a route. It is outside the maturity promise and is not
 * an application contract: an application never depends on this package directly.
 */
package dev.vertique.rest.jaxrs.synthetic;
