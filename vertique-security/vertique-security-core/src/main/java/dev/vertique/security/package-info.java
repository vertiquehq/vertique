// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique Security core API and SPI.
 *
 * <p>This package is the root of the {@code vertique-security-core} module, which defines:
 * <ul>
 *   <li>Identity and authentication types: {@code SecurityContext}, {@code SecurityIdentity},
 *       {@code PrincipalRef}, {@code AuthMethod}</li>
 *   <li>Authorization contracts: {@code Authorizer}, {@code ActionRegistry}, {@code ActionContributor},
 *       {@code AuthorizationIntrospector}</li>
 *   <li>Security event SPI: {@code SecurityEventObserver}, {@code SecurityEventEmitter}</li>
 *   <li>The sealed {@code VerificationSource} family and authentication-evidence records</li>
 * </ul>
 *
 * <p>Sub-packages follow the same structure as the originating {@code dev.vertique.security}
 * package: {@code .authz}, {@code .events}, {@code .origin}, {@code .resolver},
 * {@code .channel}, and {@code .verification}.
 *
 * <p>Runtime implementations live in {@code vertique-security-runtime}
 * ({@code dev.vertique.security.runtime}). Config-backed adapters live in
 * {@code vertique-security-config} ({@code dev.vertique.security.config}).
 */
package dev.vertique.security;
