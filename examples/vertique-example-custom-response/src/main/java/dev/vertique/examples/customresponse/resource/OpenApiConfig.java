// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * OpenAPI metadata definition. This class is scanned by swagger-maven-plugin-jakarta
 * to populate the info section of the generated OpenAPI spec.
 *
 * <p>Defines a JWT bearer token security scheme. Security requirements are applied
 * per-endpoint based on {@code @RolesAllowed}, {@code @DenyAll}, and {@code @PermitAll}
 * annotations.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Custom Response API",
                        version = "0.1.0",
                        description =
                                "Example API demonstrating custom response serialization, SHA-256 digest headers, and categorized error format"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
