// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JWT bearer token authentication for the REST framework.
 *
 * <p>Provides a ready-to-use {@link dev.vertique.rest.auth.jwt.JwtAuthModule} Dagger module
 * that wires together JWT authentication and multi-convention claim extraction. Applications only
 * need to provide a {@link io.vertx.ext.auth.jwt.JWTAuth} instance and include
 * {@link dev.vertique.rest.auth.jwt.JwtAuthModule} in their Dagger {@code @Component}.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.rest.auth.jwt.JwtAuthModule} — Dagger module; includes
 *       {@link dev.vertique.rest.security.AuthModule} and
 *       {@link dev.vertique.rest.security.SecurityModule} automatically</li>
 *   <li>{@link dev.vertique.rest.auth.jwt.JwtBearerSecuritySchemeHandler} — configures a
 *       {@link io.vertx.ext.web.handler.JWTAuthHandler} for a named OpenAPI security scheme</li>
 *   <li>{@link dev.vertique.rest.auth.jwt.JwtClaimAuthorizationProvider} — extracts
 *       roles and scopes from JWT claims ({@code roles}, {@code scope}, {@code scp},
 *       {@code permissions}) into Vert.x authorization objects</li>
 * </ul>
 */
package dev.vertique.rest.auth.jwt;
