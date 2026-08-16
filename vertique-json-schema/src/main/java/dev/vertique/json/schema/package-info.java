// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Transport-neutral, annotation-driven JSON Schema generation for resolved Java {@link
 * java.lang.reflect.Type} values.
 *
 * <p>This package owns Draft 2020-12 JSON Schema synthesis through Victools, configured with the
 * Jackson, Jakarta Validation, and Swagger 2 annotation modules. It contains no REST, MCP, Vert.x
 * Web, or transport-specific type, and depends only on {@code dev.vertique:vertique-core} for the
 * stable JSON profile contracts declared in {@code dev.vertique.core.json}.
 *
 * <p>{@link dev.vertique.json.schema.AnnotationJsonSchemaGenerator} is the single entry point,
 * offering three construction modes — the current REST-compatible default, and profile-aware
 * input and output modes that consume a resolved {@code dev.vertique.core.json.JsonMapperProfile}.
 * {@link dev.vertique.json.schema.JsonSchemaGenerationException} is the one bounded failure type
 * this package throws; no Victools type is ever exposed through a public signature.
 *
 * @see dev.vertique.json.schema.AnnotationJsonSchemaGenerator
 * @see dev.vertique.json.schema.JsonSchemaGenerationException
 */
package dev.vertique.json.schema;
