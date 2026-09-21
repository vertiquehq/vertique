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
     * Derives the kind from the generated schema's own {@code type} keyword, already resolved by the
     * schema library or by the property's declared type.
     *
     * @param jsonSchemaType the schema's {@code type} value ({@code "array"}, {@code "object"}, ...),
     *                       or {@code null} when the schema carries no {@code type} keyword
     * @return the corresponding kind, or {@link #OTHER} for anything unrecognized
     */
    static ConstraintValueKind fromSchemaType(String jsonSchemaType) {
        if (jsonSchemaType == null) {
            return OTHER;
        }
        return switch (jsonSchemaType) {
            case "array" -> ARRAY;
            case "object" -> MAP;
            case "integer", "number" -> NUMBER;
            case "string" -> STRING;
            default -> OTHER;
        };
    }

    /**
     * Derives the kind from a member's declared Java type, for a position the schema does not (yet)
     * carry a {@code type} keyword for.
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
