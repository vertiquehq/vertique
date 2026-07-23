// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Build-time Swagger model converter that unwraps {@link io.vertx.core.Future} return types to
 * their inner type {@code T} in the generated OpenAPI specification. Without this converter,
 * swagger-maven-plugin would emit {@code Future} as an opaque schema; with it, the spec reflects
 * the actual response payload type (e.g., a resource record or list) so that generated client
 * code and API documentation are accurate. The converter is loaded as a
 * {@code modelConverterClasses} entry in the swagger-maven-plugin configuration of each
 * application module's {@code pom.xml}.
 */
package dev.vertique.openapi;
