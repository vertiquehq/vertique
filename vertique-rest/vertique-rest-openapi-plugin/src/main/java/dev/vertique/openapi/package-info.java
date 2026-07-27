// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Build-time Swagger extensions for {@code swagger-maven-plugin}: model converters and an OpenAPI
 * extension that make the generated spec reflect the framework's actual JAX-RS contract rather
 * than its internal wrapper/aggregation types.
 *
 * <p>This module contains two kinds of extension point, registered differently:
 *
 * <ul>
 *   <li>{@code ModelConverter}s ({@link dev.vertique.openapi.FutureModelConverter}, {@link
 *       dev.vertique.openapi.SseModelConverter}) unwrap or reshape return types during schema
 *       resolution. They are opt-in: each application module's {@code pom.xml} must list the
 *       fully-qualified class name(s) under {@code swagger-maven-plugin}'s {@code
 *       modelConverterClasses}.
 *   <li>{@code OpenAPIExtension} ({@link dev.vertique.openapi.RequestParamsExtension}) expands
 *       {@code @RequestParams}-annotated method parameters into individual OpenAPI parameters. It
 *       is auto-discovered via {@code ServiceLoader} ({@code
 *       META-INF/services/io.swagger.v3.jaxrs2.ext.OpenAPIExtension}) — no {@code pom.xml} entry
 *       is required beyond including this artifact on the swagger-maven-plugin classpath.
 * </ul>
 */
package dev.vertique.openapi;
