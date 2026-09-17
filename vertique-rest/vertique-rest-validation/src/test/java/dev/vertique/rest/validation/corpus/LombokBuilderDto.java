// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * Corpus fixture for a builder type filled through its builder, whose getters the real Lombok
 * annotation processor generates. {@code @Getter} is load-bearing: the same class without it is
 * invisible to introspection and stays {@code {"type":"object"}} even after this task's change, the
 * gap {@code spec.md} § Known description gaps records as BG1.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
@Builder
@Jacksonized
@Getter
public class LombokBuilderDto {

    /** The string property the gate proofs send a well-typed value to. */
    private String name;

    /** The integer property the gate proofs post the numeric string {@code "2"} to. */
    private Integer quantity;
}
