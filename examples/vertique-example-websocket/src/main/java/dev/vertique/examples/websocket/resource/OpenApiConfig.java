// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * OpenAPI metadata definition scanned by the swagger-maven-plugin at build time.
 *
 * <p>Declares a JWT bearer token security scheme ({@code bearerAuth}) that matches the scheme
 * name used by {@link dev.vertique.rest.auth.jwt.JwtAuthModule}.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "WebSocket Chat Example",
                        version = "1.0",
                        description = "Chat room demo using @WebSocketEndpoint with JWT authentication"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
