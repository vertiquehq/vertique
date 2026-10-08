// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/** OpenAPI metadata for the REST cross-transport proof endpoints. */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "MCP cross-transport rate-limit example",
                        version = "0.1.0",
                        description = "REST endpoints sharing the MCP example's rate-limit runtime"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
