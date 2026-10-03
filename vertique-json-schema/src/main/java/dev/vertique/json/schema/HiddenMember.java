// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import jakarta.annotation.Nullable;

/**
 * One member, or one type, that a canonical generator's document describes while it carries a
 * hiding marker, as {@link AnnotationJsonSchemaGenerator#hiddenMembers(java.lang.reflect.Type)}
 * reports it.
 *
 * <p>The markers are {@code io.swagger.v3.oas.annotations.Hidden} and {@code @Schema(hidden =
 * true)}. The generators leave a member out of their documents only where they honor {@code
 * @Schema(hidden = true)}, so an entry names a member or type that the document still describes.
 * The record carries names and flags only, never a schema fragment.
 *
 * @param declaringType          the binary name ({@link Class#getName()}) of the class declaring the
 *                               member, or of the type itself when {@code member} is {@code null}
 * @param member                 the own name ({@link java.lang.reflect.Member#getName()}) of the Java
 *                               member that {@code declaringType} declares — a field, a method, or an
 *                               enum constant — or, for a creator parameter, the creator's name, then
 *                               {@code #}, then the zero-based parameter index ({@code <init>#0} for
 *                               a constructor, {@code of#1} for a static factory named {@code of});
 *                               {@code null} when the entry reports the type itself
 * @param marker                 the hiding marker or markers the declaration carries
 * @param hideableBySchemaHidden provisional: whether declaring {@code @Schema(hidden = true)}
 *                               directly on the property's own field, or on its getter when the mapper
 *                               sees no field, makes this generator leave the property out at the
 *                               position where it is described; {@code false} for a type, an enum
 *                               constant, a {@code @JsonUnwrapped} member, and an any-setter, and,
 *                               in the input direction, for a property described through a setter, a
 *                               builder method, a static factory creator's parameter, a constructor
 *                               creator's parameter without a backing field, a map member rendered
 *                               through its value constraints, a member bound through the inline
 *                               nested-bean branch, or a member bound through a converter; and for
 *                               a position with no schema-library member scope, where the generator
 *                               checks neither declaration; the value may change when
 *                               the generator honors the marker at more positions; an entry recorded
 *                               at several positions carries the conjunction of their values
 */
public record HiddenMember(
        String declaringType, @Nullable String member, HidingMarker marker, boolean hideableBySchemaHidden) {}
