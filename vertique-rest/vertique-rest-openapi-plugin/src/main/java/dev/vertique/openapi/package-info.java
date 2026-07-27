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
 *   <li>{@code ModelConverter}s unwrap or reshape return types during schema resolution: {@link
 *       dev.vertique.openapi.FutureModelConverter} unwraps {@code Future<T>} to {@code T}; {@link
 *       dev.vertique.openapi.SseModelConverter} resolves {@code ReadStream<SseEvent>} to a plain
 *       string schema; {@link dev.vertique.openapi.BigDecimalModelConverter} resolves {@code
 *       BigDecimal} to the {@code vertique-strict} string/decimal wire-form schema; and {@link
 *       dev.vertique.openapi.ScalarOptionalModelConverter} resolves the JDK's non-generic scalar
 *       optionals ({@code OptionalInt}/{@code OptionalLong}/{@code OptionalDouble}) to their
 *       unwrapped primitive schemas. They are opt-in: each application module's {@code pom.xml}
 *       must list the fully-qualified class name(s) under {@code swagger-maven-plugin}'s {@code
 *       modelConverterClasses}.
 *   <li>{@code OpenAPIExtension} ({@link dev.vertique.openapi.RequestParamsExtension}) expands
 *       {@code @RequestParams}-annotated method parameters into individual OpenAPI parameters. It
 *       is auto-discovered via {@code ServiceLoader} ({@code
 *       META-INF/services/io.swagger.v3.jaxrs2.ext.OpenAPIExtension}) — no {@code pom.xml} entry
 *       is required beyond including this artifact on the swagger-maven-plugin classpath.
 * </ul>
 */
package dev.vertique.openapi;
