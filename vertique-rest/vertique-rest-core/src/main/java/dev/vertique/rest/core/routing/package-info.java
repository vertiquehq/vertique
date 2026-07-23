// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Transport-neutral routing contracts for the REST layer.
 *
 * <p>{@link dev.vertique.rest.core.routing.RestOperationDescriptor} is the base, core-visible
 * description of a single REST operation (identity, content types, security policy, security
 * requirements, and resolved annotations) consumed by rest-core SPIs without coupling rest-core to
 * rest-jaxrs. {@link dev.vertique.rest.core.routing.SecurityRequirement} is the neutral
 * security-requirement record.
 *
 * <p>The registration surfaces replace the previous {@code RouterBuilder} coupling with three
 * distinct scopes: {@link dev.vertique.rest.core.routing.RouteRegistration} (per-operation handler
 * registration), {@link dev.vertique.rest.core.routing.SecuritySchemeRegistry} (scheme-scoped
 * authentication-handler registration), and {@link dev.vertique.rest.core.routing.RouterSetup}
 * (global router/security access for lifecycle hooks).
 */
package dev.vertique.rest.core.routing;
