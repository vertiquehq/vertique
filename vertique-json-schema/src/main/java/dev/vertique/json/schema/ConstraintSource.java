// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

/**
 * Source of value-schema constraints for an input property, abstracting over where they come from.
 *
 * <p>Two implementations. {@link WalkConstraintSource} is the pre-existing behavior: a field or
 * getter with a schema-library member scope is left entirely to the library's own Jackson and Jakarta
 * Validation modules, and a creator parameter, setter, or builder method — which has no such scope —
 * is hand-translated from Jackson's merged annotation map. It is used whenever no
 * {@link jakarta.validation.Validator} is supplied to the generator, so generation is unchanged from
 * before this abstraction existed.
 *
 * <p>{@link MetadataConstraintSource} is driven by Bean Validation metadata
 * ({@link jakarta.validation.Validator#getConstraintsForClass}) when a {@code Validator} is supplied.
 * Because it can see constraints the schema library's own walk cannot join by wire name (a
 * constructor-parameter constraint without {@code -parameters}, an inherited or interface constraint,
 * a composed constraint's leaves, an XML-mapped constraint), it drives constraints for every member
 * kind — scoped or not — and the generator disables its own Jakarta Validation module so the two
 * never double-emit or conflict.
 *
 * <p>Selected once per generator, at construction, by
 * {@link AnnotationJsonSchemaGenerator#forInputProfile(dev.vertique.core.json.JsonMapperProfile,
 * jakarta.validation.Validator)}.
 */
interface ConstraintSource {

    /**
     * Whether this source drives value-schema constraints for a field or getter that already has a
     * schema-library member scope, in which case the generator must not also install its own Jakarta
     * Validation module (or the two would double-emit or conflict).
     *
     * @return {@code false} for {@link WalkConstraintSource}; {@code true} for
     *     {@link MetadataConstraintSource}
     */
    boolean disablesGeneratorJakartaModule();

    /**
     * Constraints for a field or getter that has a schema-library member scope.
     *
     * <p>Only ever consulted when {@link #disablesGeneratorJakartaModule()} is {@code true} — when it
     * is {@code false}, the schema library's own modules already applied everything this method could
     * add, and the caller never calls it.
     *
     * @param builtClass the type the property is described on — the leaf type, so inherited and
     *                   interface constraints join through it
     * @param javaName   the field's or getter's Java bean name; never the wire name
     * @param kind       the value position, selecting the size/range keyword family
     * @return the resolved constraints; {@link ResolvedConstraints#NONE} when nothing joins
     */
    ResolvedConstraints forScopedMember(Class<?> builtClass, String javaName, ConstraintValueKind kind);

    /**
     * Constraints for a creator parameter, setter, or builder method — a member with no
     * schema-library scope.
     *
     * @param builtClass    the type the property is described on
     * @param javaName      the Java bean name to join by for a setter or builder method (the built
     *                      type's same-named property); a creator parameter instead joins by its
     *                      declaring constructor and parameter index, read from {@code jacksonMember}
     * @param kind          the value position, selecting the size/range keyword family
     * @param jacksonMember the member's Jackson-resolved annotated member — an
     *                      {@link com.fasterxml.jackson.databind.introspect.AnnotatedParameter} for a
     *                      creator parameter, an
     *                      {@link com.fasterxml.jackson.databind.introspect.AnnotatedMethod} for a
     *                      setter or builder method; may be {@code null}
     * @return the resolved constraints; {@link ResolvedConstraints#NONE} when nothing joins
     */
    ResolvedConstraints forUnscopedMember(
            Class<?> builtClass, String javaName, ConstraintValueKind kind, AnnotatedMember jacksonMember);
}
