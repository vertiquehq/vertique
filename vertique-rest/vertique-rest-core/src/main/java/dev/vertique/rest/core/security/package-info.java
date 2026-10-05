// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Security contract interfaces and types for the REST layer.
 *
 * <p>{@link dev.vertique.rest.core.security.SecuritySchemeHandler} configures a security
 * scheme's authentication handler on the transport-neutral
 * {@link dev.vertique.rest.core.routing.SecuritySchemeRegistry}
 * (contributed via Dagger {@code Set<SecuritySchemeHandler>} multibinding).
 * {@link dev.vertique.rest.core.security.SecurityRuntime} is the DI-managed service for
 * accessing and storing the per-request {@link dev.vertique.security.SecurityContext}.
 *
 * <p>Authorization is declared via the {@link dev.vertique.security.authz.Authorized}
 * annotation on supported resource methods and enforced by
 * {@link dev.vertique.rest.core.security.SecurityPolicyValidator}, which evaluates
 * {@link dev.vertique.rest.core.security.SecurityPolicy} implementations and throws
 * {@link dev.vertique.rest.core.security.SecurityPolicyViolationException} (wrapping a
 * {@link dev.vertique.rest.core.security.SecurityPolicyViolation} record) when access
 * is denied.
 *
 * <p>The OpenAPI Security Scheme descriptions a {@code SecuritySchemeHandler} returns from
 * {@link dev.vertique.rest.core.security.SecuritySchemeHandler#openApiDescription()} live in
 * {@link dev.vertique.rest.core.security.scheme}.
 */
package dev.vertique.rest.core.security;
