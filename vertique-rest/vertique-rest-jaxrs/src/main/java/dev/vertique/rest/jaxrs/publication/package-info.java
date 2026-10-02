// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL framework seam that hands every JAX-RS mount's detached operation snapshot to
 * publication sinks.
 *
 * <p>Every type here is public only so sibling framework modules — the OpenAPI documentation
 * module and the {@code openapi-contract} validation module's contract-load check — can contribute
 * a sink and read the snapshots it receives. It is outside the maturity promise and is not an
 * application contract: an application never depends on this package directly.
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
 * dev.vertique.rest.jaxrs.publication.InputKey}. The detail also carries the operation's flattened
 * input inventory, one {@link dev.vertique.rest.jaxrs.publication.InputBinding} per bound method
 * parameter, body, and composite field with its location, bound name, type, default, requiredness,
 * hidden flag, and whether the gate enforces a schema for it, and the operation's {@link
 * dev.vertique.rest.jaxrs.publication.ResponseShape}. A sink must not retain a publication, or
 * anything reachable from it, past the call.
 */
package dev.vertique.rest.jaxrs.publication;
