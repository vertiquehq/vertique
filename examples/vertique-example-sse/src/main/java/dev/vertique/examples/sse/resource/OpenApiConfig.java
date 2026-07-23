// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * OpenAPI metadata definition scanned by the swagger-maven-plugin to populate the info section of
 * the generated OpenAPI specification.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Job Progress SSE Example",
                        version = "1.0",
                        description = "Demonstrates the SSE streaming API using a simulated job progress feed."))
public class OpenApiConfig {}
