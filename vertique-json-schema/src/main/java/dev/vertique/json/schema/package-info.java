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
 * <p>Five result types carry what the generator reports beyond the schema text:
 *
 * <ul>
 *   <li>{@link dev.vertique.json.schema.CanonicalSchema} pairs a canonical document with the {@link
 *       dev.vertique.json.schema.RedactionManifest} the generator bound to it. It is a plain record
 *       anyone can construct, so a consumer trusts the pairing only when {@code
 *       redactionManifest().matches(json())} holds against the document actually encoded.
 *   <li>{@link dev.vertique.json.schema.RedactionManifest} lists the JSON Pointer of every
 *       reserved-name assertion in that document and carries the digest of its canonical bytes; only
 *       this package constructs it.
 *   <li>{@link dev.vertique.json.schema.OutputRename} names a member an output-direction schema
 *       publishes under a property name other than the one Jackson serializes it under. The report
 *       is provisional.
 *   <li>{@link dev.vertique.json.schema.HiddenMember} names a member or type that the document still
 *       describes while it carries a hiding marker; {@code hiddenMembers} reports these entries.
 *   <li>{@link dev.vertique.json.schema.HidingMarker} names which marker a {@code HiddenMember}
 *       carries: {@code io.swagger.v3.oas.annotations.Hidden}, {@code @Schema(hidden = true)}, or
 *       both.
 * </ul>
 *
 * @see dev.vertique.json.schema.AnnotationJsonSchemaGenerator
 * @see dev.vertique.json.schema.JsonSchemaGenerationException
 */
package dev.vertique.json.schema;
