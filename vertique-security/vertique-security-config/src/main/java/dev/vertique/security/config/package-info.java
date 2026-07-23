// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique Security config-backed authorization adapters.
 *
 * <p>This package is the root of the {@code vertique-security-config} module, which bridges
 * {@code vertique-security-core} authorization SPIs with {@code vertique-config-core}:
 * <ul>
 *   <li>{@code ConfigBackedPolicyDefinitionSource} — reads action-to-roles policy from configuration</li>
 *   <li>{@code ConfigBackedRolePolicyResolver} — resolves role-based policy entries from configuration</li>
 *   <li>{@code AuthorizationConfig}, {@code PolicyDefinitionConfig}, {@code PolicyStatementConfig} —
 *       configuration record types</li>
 *   <li>{@code AuthzConfigModule} — Dagger module binding the config-backed implementations
 *       {@code @IntoSet} so they compose with other policy sources</li>
 * </ul>
 *
 * <p>Generic {@code vertique-config-core} must not own security-specific adapters; this module
 * is the designated bridge. Applications wiring config-backed authorization include
 * {@code vertique-security-config} and its {@code AuthzConfigModule} alongside
 * {@code vertique-security-runtime}'s {@code SecurityAuthzModule}.
 */
package dev.vertique.security.config;
