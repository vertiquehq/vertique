// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Authentication and authorization handlers for the REST framework.
 *
 * <p>The per-request security pipeline runs in four stages:
 * <ol>
 *   <li>{@link dev.vertique.rest.security.OriginCaptureMiddleware} (ROOT-scoped, pre-auth) —
 *       captures the {@link dev.vertique.security.origin.RequestOrigin} from the inbound
 *       request and stashes it on the routing context so it is available to both auth handlers
 *       and identity resolution.</li>
 *   <li>Auth handlers (per-route, OpenAPI-driven) — each successful handler appends an
 *       {@link dev.vertique.security.AuthenticationEvidence} entry via
 *       {@link dev.vertique.rest.security.RestAuthenticationEvidence}; a failing handler calls
 *       {@code ctx.fail()} and emits a
 *       {@link dev.vertique.security.events.CredentialRejectedEvent} via
 *       {@link dev.vertique.rest.security.CredentialRejectionReporter}, short-circuiting
 *       downstream stages.</li>
 *   <li>{@link dev.vertique.rest.security.IdentityResolutionMiddleware} (per-route, priority
 *       {@link dev.vertique.rest.security.IdentityResolutionContributor#PRIORITY}) — runs the
 *       priority-ordered {@link dev.vertique.security.resolver.SecurityIdentityResolver}
 *       chain against the accumulated evidence, builds the
 *       {@link dev.vertique.security.SecurityContext}, and binds it via
 *       {@link dev.vertique.rest.security.HolderBackedSecurityRuntime}.</li>
 *   <li>{@link dev.vertique.rest.security.AuthorizationContributor} /
 *       {@link dev.vertique.rest.security.SecurityPolicyEnforcer} (per-route, priority 100+) —
 *       evaluates JAX-RS security annotations ({@code @RolesAllowed}, {@code @PermitAll},
 *       {@code @DenyAll}) and the framework extension {@code @Authorized} for scope-based
 *       access control against the bound {@code SecurityContext}.</li>
 * </ol>
 *
 * <p>Startup consistency of annotation declarations, OpenAPI security requirements, and scheme
 * handler registrations is validated by
 * {@link dev.vertique.rest.core.security.SecurityPolicyValidator}. Include
 * {@link dev.vertique.rest.security.AuthModule} and
 * {@link dev.vertique.rest.security.SecurityModule} in the application Dagger
 * {@code @Component} to activate all security features.
 */
package dev.vertique.rest.security;
