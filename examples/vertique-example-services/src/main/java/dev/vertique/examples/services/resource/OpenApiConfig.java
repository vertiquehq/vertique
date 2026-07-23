// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * OpenAPI metadata definition for the example-services application.
 *
 * <p>This class is scanned by {@code swagger-maven-plugin-jakarta} to populate the
 * {@code info} section of the generated OpenAPI spec.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Example Services API",
                        version = "0.1.0",
                        description = "Demonstrates event bus service dispatch with REST API"))
public class OpenApiConfig {}
