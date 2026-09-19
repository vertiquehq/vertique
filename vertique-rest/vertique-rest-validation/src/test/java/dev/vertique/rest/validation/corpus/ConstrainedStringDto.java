// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corpus fixture for string constraints carried by property metadata: a Jakarta {@code @Size(min)}
 * and a Swagger {@code @Schema(pattern)}. The latter also puts a genuine, compilable {@code pattern}
 * member into the body document, so the gate's regex walk has a real value position to compile.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class ConstrainedStringDto {

    /** Carries {@code minLength} through {@code @Size(min)}. */
    @Size(min = 2)
    public String code;

    /** Carries a compilable {@code pattern} member through Swagger property metadata. */
    @Schema(pattern = "[A-Z]+")
    public String category;
}
