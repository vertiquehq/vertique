// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Framework-support JSON Schema validation. Public types in this package are
 * INTERNAL framework seams — not an application contract.
 *
 * <p>Shared, transport-neutral support for framework modules that validate payloads against
 * generated JSON Schema documents with the {@code vertx-json-schema} engine. It contains no REST,
 * MCP, Vert.x Web, or Dagger type, and depends only on {@code dev.vertique:vertique-core} and
 * {@code io.vertx:vertx-json-schema}.
 *
 * <p>{@link dev.vertique.json.schema.validation.PatternInputGuard} bounds the text a schema's
 * regular expressions may see, and {@link dev.vertique.json.schema.validation.SchemaPatterns}
 * reports every pattern in a schema that does not compile, without echoing the pattern text.
 * {@link dev.vertique.json.schema.validation.SchemaKeywords} names the keyword vocabulary those and
 * {@link dev.vertique.json.schema.validation.ViolationDetails} share, and {@code ViolationDetails}
 * turns engine output into value-free {@code dev.vertique.core.validation.ViolationDetail} entries
 * whose {@code path} takes the {@link dev.vertique.json.schema.validation.PathForm} the caller asks
 * for. No message produced by this package ever contains instance text.
 *
 * @see dev.vertique.json.schema.validation.PatternInputGuard
 * @see dev.vertique.json.schema.validation.SchemaPatterns
 * @see dev.vertique.json.schema.validation.ViolationDetails
 */
package dev.vertique.json.schema.validation;
