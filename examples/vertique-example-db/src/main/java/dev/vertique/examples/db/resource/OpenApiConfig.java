// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * OpenAPI specification metadata for the example-db application.
 *
 * <p>This class is scanned by the {@code swagger-maven-plugin-jakarta} at build time to produce
 * the top-level info block of the generated {@code openapi.json}.
 */
@OpenAPIDefinition(
        info = @Info(title = "Example DB API", version = "1.0", description = "Item CRUD REST API with PostgreSQL"))
public class OpenApiConfig {}
