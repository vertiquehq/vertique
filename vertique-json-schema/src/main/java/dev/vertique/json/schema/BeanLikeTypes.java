// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * The one shared "is this class ever described as a bean by {@link InputPropertyDescriber}" check
 * (S5, spike/deserializer-driven-schema round 4 ruling), consulted by both {@link
 * InputPropertyDescriber}'s own F1 refusal and {@link ValidatedProfile}'s F6 override-closure check —
 * previously two separately maintained copies of the same exclusion list and "settable property" test,
 * now one.
 */
final class BeanLikeTypes {

    private BeanLikeTypes() {}

    /**
     * Whether {@code javaType} is a type {@link InputPropertyDescriber} would ever actually describe as
     * a bean: excluded outright — a primitive, array, enum, annotation, or a JDK/Jakarta/Jackson type,
     * none of which ever reach the describer's own bean-ness decision (Victools' own built-in handling
     * applies instead), so neither caller may call one of them bean-like merely because reflective
     * introspection happens to enumerate some property-shaped accessor on it — and the {@code
     * io.vertx.*} family, which is excluded the same way {@link InputPropertyDescriber}'s own F1
     * refusal excludes it: a no-argument getter such as {@code Buffer#getBytes()} or a
     * mutable-collection getter such as {@code JsonArray#getList()} makes plain reflective
     * introspection report a property for these well-known wrapper types even though neither is ever
     * bound as a bean. Otherwise, "settable" ({@link BeanPropertyDefinition#couldDeserialize()}), not
     * merely known: a getter-only property is not a field walk either caller's check protects.
     *
     * @param mapper   the mapper whose reflective introspection decides bean-likeness
     * @param javaType the class being classified
     * @return {@code true} when {@code javaType} is not excluded outright and carries at least one
     *     settable introspected property
     */
    static boolean beanLike(ObjectMapper mapper, Class<?> javaType) {
        if (javaType.isPrimitive()
                || javaType.isArray()
                || javaType.isEnum()
                || javaType.isAnnotation()
                || javaType.getName().startsWith("java.")
                || javaType.getName().startsWith("javax.")
                || javaType.getName().startsWith("jakarta.")
                || javaType.getName().startsWith("com.fasterxml.jackson.")
                || javaType.getName().startsWith("io.vertx.")) {
            return false;
        }
        return mapper
                .getDeserializationConfig()
                .introspect(mapper.getTypeFactory().constructType(javaType))
                .findProperties()
                .stream()
                .anyMatch(BeanPropertyDefinition::couldDeserialize);
    }
}
