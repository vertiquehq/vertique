// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Decides whether a builder method's constraint borrow onto the built type's same-named field — see
 * {@link InputPropertyDescriber#borrowBuilderFieldAttributes} and {@link
 * MetadataConstraintSource#forUnscopedMember} — is sound enough to publish, for the
 * <em>getter-less</em> built property (the private, no-getter case). A getter-backed built property
 * borrows unconditionally, for any builder, and never consults this class — see
 * {@code module.md}'s "Builder borrow assumption" (round 2). The owner ruling (vertiquehq/vertique-dev,
 * {@code spike/deserializer-driven-schema}): the borrow is guaranteed by construction for a Lombok
 * {@code @Builder @Jacksonized} setter, which the framework's own configuration types use throughout,
 * and is kept for that shape; a hand-written builder that does not reproduce it may go unresolved,
 * meaning its getter-less property is published by type only.
 *
 * <p>Detection is entirely {@code java.lang.reflect} over facts Jackson's own annotations already
 * declare at {@code RUNTIME} retention — never {@code @lombok.Generated}, whose {@code
 * RetentionPolicy.CLASS} would need reading the compiled class file directly (as JaCoCo's own
 * exclusion does) rather than through ordinary reflection, disproportionate surface for a detection
 * hint in a module whose compile dependencies are deliberately frozen. The shape {@code @Jacksonized}
 * emits is itself made entirely of runtime-visible Jackson annotations, so it can be read the same
 * way any other Jackson-driven join in this class already is: the built type carries {@code
 * @JsonDeserialize(builder = ...)} naming a {@code static} nested class of the built type named
 * {@code <Type>Builder}; that class carries {@code @JsonPOJOBuilder(withPrefix = "", buildMethodName
 * = "build")} — the exact values {@code @Jacksonized} generates — and a zero-argument {@code build()}
 * returning the built type; and, for the property in question, a builder method with exactly one
 * parameter whose type and name both match the built field exactly. A hand-written builder that
 * reproduces this whole shape resolves too — the ruling tolerates that: it says a hand-written builder
 * <em>may</em> go unresolved, not that it must.
 */
final class BuilderBorrowDetector {

    private BuilderBorrowDetector() {}

    /**
     * Whether the borrow of {@code field}'s constraints onto {@code builderMethod} — a method
     * declared on a type other than {@code builtClass} — is sound: the whole shape matches what
     * {@code @Builder @Jacksonized} generates by default.
     *
     * @param builderMethod the builder method that would set {@code field}
     * @param builtClass    the type the property is described on
     * @param field         the built type's field the property joins to
     * @return {@code true} when the borrow may be published
     */
    static boolean isSoundBorrow(Method builderMethod, Class<?> builtClass, Field field) {
        return matchesLombokBuilderShape(builderMethod, builderMethod.getDeclaringClass(), builtClass, field);
    }

    private static boolean matchesLombokBuilderShape(
            Method builderMethod, Class<?> builderClass, Class<?> builtClass, Field field) {
        if (!Modifier.isStatic(builderClass.getModifiers()) || builderClass.getEnclosingClass() != builtClass) {
            return false;
        }
        if (!builderClass.getSimpleName().equals(builtClass.getSimpleName() + "Builder")) {
            return false;
        }
        JsonDeserialize deserialize = builtClass.getAnnotation(JsonDeserialize.class);
        if (deserialize == null || deserialize.builder() != builderClass) {
            return false;
        }
        JsonPOJOBuilder pojoBuilder = builderClass.getAnnotation(JsonPOJOBuilder.class);
        if (pojoBuilder == null
                || !pojoBuilder.withPrefix().isEmpty()
                || !"build".equals(pojoBuilder.buildMethodName())) {
            return false;
        }
        Method build;
        try {
            build = builderClass.getDeclaredMethod("build");
        } catch (NoSuchMethodException absent) {
            return false;
        }
        if (build.getReturnType() != builtClass) {
            return false;
        }
        return builderMethod.getParameterCount() == 1
                && builderMethod.getParameterTypes()[0].equals(field.getType())
                && builderMethod.getName().equals(field.getName());
    }
}
