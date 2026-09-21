// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.util.Map;

/**
 * The value-schema keyword fragment and required-ness a {@link ConstraintSource} resolves for one
 * member or parameter.
 *
 * <p>{@code keywords} maps a JSON Schema keyword name to the exact value type
 * {@link InputPropertyDescriber} applies it with — {@link Long} or {@link Integer} for a plain
 * integral bound, {@link java.math.BigDecimal} for a decimal bound, {@link String} for a pattern —
 * so a caller reproduces the pre-existing hand-translation's numeric formatting exactly regardless of
 * which source produced the value.
 *
 * @param keywords the keyword fragment to merge onto the member's schema; never {@code null}, may be
 *                 empty
 * @param required whether the member is required, from a {@code @NotNull} (or, for the annotation
 *                 walk only, a {@code @NotBlank}/{@code @NotEmpty}) constraint
 */
record ResolvedConstraints(Map<String, Object> keywords, boolean required) {

    /** No constraint applies; carries neither a keyword nor required-ness. */
    static final ResolvedConstraints NONE = new ResolvedConstraints(Map.of(), false);
}
