// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

/**
 * INTERNAL framework seam — callable by sibling Vertique modules only; not an application contract and outside the application maturity promise.
 *
 * One member an output-direction schema publishes under a property name other than the one Jackson
 * serializes it under, as {@link AnnotationJsonSchemaGenerator#outputRenames(java.lang.reflect.Type)}
 * reports it.
 *
 * <p>The typical cause is a {@code @Schema(name = ...)} on a member Jackson serializes under its own
 * name: the document describes the member under {@code schemaName}, while a serialized payload
 * carries it under {@code serializedName}. The record carries names only, never a schema fragment.
 *
 * <p>OpenAPI publication consumes this shape. Components evolve only by appending nullable trailing
 * fields (ADR-0254).
 *
 * @param declaringType  the binary name ({@link Class#getName()}) of the class declaring the member
 * @param member         the Java member's own name ({@link java.lang.reflect.Member#getName()})
 * @param serializedName the name the profile mapper's serialization introspection gives the member
 * @param schemaName     the property name the output schema publishes the member under
 */
public record OutputRename(String declaringType, String member, String serializedName, String schemaName) {}
