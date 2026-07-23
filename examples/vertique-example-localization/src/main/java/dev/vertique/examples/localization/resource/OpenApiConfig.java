// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * OpenAPI metadata definition for the example-localization application.
 *
 * <p>This class is scanned by {@code swagger-maven-plugin-jakarta} to populate the
 * {@code info} section of the generated OpenAPI spec.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Example Localization API",
                        version = "0.1.0",
                        description = "Demonstrates inbound REST locale negotiation with RequestLocaleInterceptor"))
public class OpenApiConfig {}
