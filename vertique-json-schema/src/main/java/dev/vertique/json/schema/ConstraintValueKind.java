// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.util.Collection;
import java.util.Map;

/**
 * The value shape a rendered size/range constraint must target: selects between {@code
 * minLength}/{@code maxLength}, {@code minItems}/{@code maxItems}, and {@code
 * minProperties}/{@code maxProperties} for the same {@code @Size}-shaped constraint.
 */
enum ConstraintValueKind {
    STRING,
    ARRAY,
    MAP,
    NUMBER,
    OTHER;

    /**
     * Derives the kind from a member's declared Java type.
     *
     * <p>Deriving from the generated schema's own {@code type} keyword was tried and abandoned (C2):
     * that keyword is absent at the point constraints are applied for a map, a bean, or an {@code
     * Optional} value position (the schema is still a bare {@code $ref} wrapper, or the member has no
     * schema-library member scope at all), so a {@code @Size} on a {@code Map} rendered {@code
     * maxLength} instead of {@code maxProperties}. The declared Java type is always available,
     * regardless of what the schema looks like at the moment a constraint is being applied.
     *
     * @param javaType the declared Java type
     * @return the corresponding kind
     */
    static ConstraintValueKind fromJavaType(Class<?> javaType) {
        if (javaType == null) {
            return OTHER;
        }
        if (CharSequence.class.isAssignableFrom(javaType)) {
            return STRING;
        }
        if (javaType.isArray() || Collection.class.isAssignableFrom(javaType)) {
            return ARRAY;
        }
        if (Map.class.isAssignableFrom(javaType)) {
            return MAP;
        }
        if (Number.class.isAssignableFrom(javaType)
                || (javaType.isPrimitive() && javaType != boolean.class && javaType != char.class)) {
            return NUMBER;
        }
        return OTHER;
    }
}
