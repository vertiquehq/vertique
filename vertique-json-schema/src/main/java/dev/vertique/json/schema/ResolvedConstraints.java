// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.util.Map;

/**
 * The value-schema keyword fragments and required-ness a {@link ConstraintSource} resolves for one
 * member or parameter, split by how a caller may merge them onto a schema that may already carry a
 * keyword for the same position.
 *
 * <p>The Bean Validation floor — the schema library's own Jakarta Validation module for a scoped
 * field or getter, or {@link WalkConstraintSource}'s hand translation for a creator parameter,
 * setter, or builder method — is never overridden by an {@code addition}: a caller merges those only
 * where the schema does not already carry the keyword, so a source that runs after the floor can see
 * more than the floor ever could (a constraint declared through an XML mapping, or on a member Jackson
 * does not merge annotations for) without ever taking back what the floor
 * already stated correctly. A {@code correction} is merged unconditionally, because it targets one of
 * the small, named set of shapes the floor is known to render incorrectly for that constraint
 * (vertiquehq/vertique-dev#606: {@code @Range}, {@code @Length}, {@code @URL}, and a {@code @Pattern}
 * flag) — never a keyword the floor gets right.
 *
 * <p>Both maps use the same value-type convention: {@link Long} or {@link Integer} for a plain
 * integral bound, {@link java.math.BigDecimal} for a decimal bound, {@link String} for a pattern, so a
 * caller reproduces the pre-existing hand-translation's numeric formatting exactly regardless of which
 * source produced the value.
 *
 * @param additions   the keyword fragment to merge onto the member's schema only where the schema
 *                    does not already carry that keyword; never {@code null}, may be empty
 * @param corrections the keyword fragment to merge onto the member's schema unconditionally,
 *                    overwriting whatever the floor already stated for that keyword; never {@code
 *                    null}, may be empty
 * @param required    whether the member is required; merged with whatever required-ness the floor
 *                    already established by {@code OR}, never by replacement — nothing narrows a
 *                    property from required to optional
 */
record ResolvedConstraints(Map<String, Object> additions, Map<String, Object> corrections, boolean required) {

    /** No constraint applies; carries neither an addition, a correction, nor required-ness. */
    static final ResolvedConstraints NONE = new ResolvedConstraints(Map.of(), Map.of(), false);
}
