// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Closed OpenAPI Security Scheme descriptions for the REST layer.
 *
 * <p>{@link dev.vertique.rest.core.security.scheme.SecuritySchemeDescription} is the sealed
 * description a {@link dev.vertique.rest.core.security.SecuritySchemeHandler} returns from
 * {@link dev.vertique.rest.core.security.SecuritySchemeHandler#openApiDescription()}. Its kinds
 * are {@link dev.vertique.rest.core.security.scheme.Http},
 * {@link dev.vertique.rest.core.security.scheme.ApiKey},
 * {@link dev.vertique.rest.core.security.scheme.OAuth2},
 * {@link dev.vertique.rest.core.security.scheme.OpenIdConnect}, and
 * {@link dev.vertique.rest.core.security.scheme.MutualTls}. An {@code OAuth2} description
 * carries its configured flows as {@link dev.vertique.rest.core.security.scheme.OAuthFlows},
 * each flow an {@link dev.vertique.rest.core.security.scheme.OAuthFlow}.
 */
package dev.vertique.rest.core.security.scheme;
