// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import jakarta.annotation.Nullable;

/**
 * One member, or one type, that a canonical generator describes while it carries {@code
 * io.swagger.v3.oas.annotations.Hidden} without {@code @Schema(hidden = true)}, as {@link
 * AnnotationJsonSchemaGenerator#hiddenOnlyMembers(java.lang.reflect.Type)} reports it.
 *
 * <p>The canonical generators honor only {@code @Schema(hidden = true)}, so a member or type marked
 * {@code @Hidden} alone stays in the document they generate. The record carries names only, never a
 * schema fragment.
 *
 * @param declaringType the binary name ({@link Class#getName()}) of the class declaring the member,
 *                      or of the type itself when {@code member} is {@code null}
 * @param member        the own name ({@link java.lang.reflect.Member#getName()}) of the Java member
 *                      that {@code declaringType} declares — a field, a method, or an enum constant
 *                      — or {@code null} when the entry reports the type itself
 */
public record HiddenOnlyMember(
        String declaringType, @Nullable String member) {}
