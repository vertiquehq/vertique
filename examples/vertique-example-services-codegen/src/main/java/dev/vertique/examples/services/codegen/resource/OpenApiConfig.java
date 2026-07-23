// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * OpenAPI metadata definition for the example-services-codegen application.
 *
 * <p>This class is scanned by {@code swagger-maven-plugin-jakarta} to populate the
 * {@code info} section of the generated OpenAPI spec.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Example Services Codegen API",
                        version = "0.1.0",
                        description =
                                "Demonstrates compile-time service contract codegen with direct-impl and handler-pattern contracts"))
public class OpenApiConfig {}
