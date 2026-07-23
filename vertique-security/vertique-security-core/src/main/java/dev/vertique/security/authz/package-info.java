// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Core authorization engine — action-policy model, SPIs, and the Dagger wiring module.
 *
 * <h2>Public API</h2>
 * <ul>
 *   <li>{@link dev.vertique.security.authz.ActionRef} — canonical three-segment action
 *       identifier ({@code subsystem.resource.verb}) with grammar validation</li>
 *   <li>{@link dev.vertique.security.authz.ActionPattern} — exact-match or trailing-suffix
 *       wildcard pattern against {@link dev.vertique.security.authz.ActionRef}s</li>
 *   <li>{@link dev.vertique.security.authz.ActionDefinition} — wraps an
 *       {@link dev.vertique.security.authz.ActionRef}</li>
 *   <li>{@link dev.vertique.security.authz.Effect} — the only allowed policy effect is
 *       {@code ALLOW}</li>
 *   <li>{@link dev.vertique.security.authz.PolicyStatement} — an ALLOW statement over a set
 *       of {@link dev.vertique.security.authz.ActionPattern}s</li>
 *   <li>{@link dev.vertique.security.authz.PolicyDefinition} — a named, ordered list of
 *       {@link dev.vertique.security.authz.PolicyStatement}s</li>
 *   <li>{@link dev.vertique.security.authz.Authorizer} — transport-neutral async evaluator;
 *       returns, never emits</li>
 *   <li>{@link dev.vertique.security.authz.AuthorizationIntrospector} — returns the set of
 *       actions permitted to a subject</li>
 *   <li>{@link dev.vertique.security.authz.AuthzReasonCodes} — stable string constants for
 *       every possible decision reason code</li>
 *   <li>{@link dev.vertique.security.authz.RequiresAction} — transport-neutral annotation
 *       declaring the action required to invoke a method or type</li>
 *   <li>{@link dev.vertique.security.authz.AuthorizationRequest} — immutable request value
 *       type (reused from the existing model)</li>
 *   <li>{@link dev.vertique.security.authz.AuthorizationDecision} — immutable decision value
 *       type (reused from the existing model)</li>
 *   <li>{@link dev.vertique.security.authz.ResourceRef} — identifies the resource in a
 *       request (reused from the existing model)</li>
 * </ul>
 *
 * <h2>SPIs</h2>
 * <ul>
 *   <li>{@link dev.vertique.security.authz.ActionContributor} — contributes actions into the
 *       registry at startup; bound {@code @IntoSet} by framework and application modules</li>
 *   <li>{@link dev.vertique.security.authz.PolicyDefinitionSource} — contributes policy
 *       definitions; default in-memory impl in {@code internal}; config-backed impl in
 *       {@code vertique-config-core}</li>
 *   <li>{@link dev.vertique.security.authz.RolePolicyResolver} — maps role names to policy
 *       names; default in-memory impl in {@code internal}; config-backed impl in
 *       {@code vertique-config-core}</li>
 * </ul>
 *
 * <h2>Dagger wiring</h2>
 * <ul>
 *   <li>{@link dev.vertique.security.runtime.authz.SecurityAuthzModule} — the Dagger module that
 *       wires the entire engine: declares the three multibinding sets, binds the built-in
 *       {@link dev.vertique.security.authz.ActionContributor}, and provides the default
 *       {@link dev.vertique.security.authz.ActionRegistry},
 *       {@link dev.vertique.security.authz.Authorizer}, and
 *       {@link dev.vertique.security.authz.AuthorizationIntrospector}.</li>
 * </ul>
 */
package dev.vertique.security.authz;
