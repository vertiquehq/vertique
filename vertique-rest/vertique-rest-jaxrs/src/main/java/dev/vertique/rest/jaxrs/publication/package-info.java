// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam for the documentation module: installing framework-owned synthetic
 * operations through the resource security chain, publishing the declared {@code
 * @RestApplication} composition for sibling framework modules to read, and handing every JAX-RS
 * mount's detached operation snapshot to publication sinks.
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
 *
 * <p>{@link dev.vertique.rest.jaxrs.publication.OperationPublicationSink} is the sink a framework
 * module contributes to the {@code Set<OperationPublicationSink>} multibinding {@code RestModule}
 * declares, empty by default. When the set is empty, no JAX-RS mount builds a publication or copies
 * a schema. Otherwise every JAX-RS mount's router creation, empty mounts included, builds one
 * {@link dev.vertique.rest.jaxrs.publication.MountPublication} and hands that same instance to every
 * sink, completing only once every sink's returned future has. The snapshot records are detached
 * values: a {@code MountPublication} names the mount, its declared application's name and declaring
 * interface (both {@code null} for a mount that serves no declared application), and the
 * request-validation strategy id, and lists one {@link
 * dev.vertique.rest.jaxrs.publication.OperationPublication} per registered operation with the route
 * value as registered and the effective security facts. For a mount at least one sink wants detail
 * for, each operation also carries an {@link dev.vertique.rest.jaxrs.publication.OperationDetail}
 * whose {@link dev.vertique.rest.jaxrs.publication.CapturedSchemas} are deep copies of the schemas
 * the request-validation gate receives, taken before the gate is produced and keyed by {@link
 * dev.vertique.rest.jaxrs.publication.InputKey}. A sink must not retain a publication, or anything
 * reachable from it, past the call.
 */
package dev.vertique.rest.jaxrs.publication;
