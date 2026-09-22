// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * Source of value-schema constraints for an input property, on top of the floor that is always
 * present: the schema library's own Jakarta Validation module for a field or getter that has a
 * schema-library member scope, and {@link WalkConstraintSource}'s hand translation of Jackson's
 * merged annotation map for a creator parameter, setter, or builder method, which has no such scope.
 *
 * <p>{@link MetadataConstraintSource}, driven by Bean Validation metadata
 * ({@link jakarta.validation.Validator#getConstraintsForClass}), is consulted <em>in addition to</em>
 * the floor whenever a {@code Validator} is supplied to the generator — never in place of it. Because
 * it can see constraints neither the schema library's own module nor the annotation walk can join by
 * wire name or reflective annotation presence at all (a constraint declared through an XML mapping, or
 * on a member Jackson does not merge annotations for), it adds what the floor cannot see, and corrects
 * the small, named set of shapes the floor is known to render
 * incorrectly ({@code @Range}, {@code @Length}, {@code @URL}, a {@code @Pattern} flag) — see
 * {@link ResolvedConstraints}. It never removes a keyword the floor already rendered, including one
 * in a non-{@code Default} group, which the schema library's own module does not filter by group.
 *
 * <p>When no {@code Validator} is supplied, no supplement runs at all, and the floor alone drives
 * generation — unchanged from before this abstraction existed.
 *
 * <p><strong>Resolved-definition contract.</strong> {@link InputPropertyDescriber#translateConstraints}
 * is the single choke point every unscoped-member call site goes through; it resolves the built type's
 * Jackson-introspected property for the member's wire name exactly once, from its own cached
 * introspection, and passes that same {@link BeanPropertyDefinition} (or {@code null}, when Jackson
 * reports no property of that wire name) to every {@link #forUnscopedMember} call for the member — the
 * floor and the supplement alike. Neither source performs its own, independent wire-name lookup, so
 * the two can never resolve or disagree about a different built property for the same member.
 */
interface ConstraintSource {

    /**
     * Constraints to merge onto a field or getter that already has a schema-library member scope,
     * on top of whatever the schema library's own Jakarta Validation module already wrote there.
     *
     * @param builtClass the type the property is described on — the leaf type, so inherited and
     *                   interface constraints join through it
     * @param javaName   the field's or getter's Java bean name; never the wire name
     * @param kind       the value position, selecting the size/range keyword family
     * @return the resolved constraints; {@link ResolvedConstraints#NONE} when nothing joins
     */
    ResolvedConstraints forScopedMember(Class<?> builtClass, String javaName, ConstraintValueKind kind);

    /**
     * Constraints to merge onto a creator parameter, setter, or builder method — a member with no
     * schema-library scope — on top of {@link WalkConstraintSource}'s hand translation, which always
     * runs first as the floor for this member kind.
     *
     * @param builtClass    the type the property is described on
     * @param javaName      the implied Java bean name to join by when no {@code builtProperty} field is
     *                      available (a creator parameter instead joins by its declaring constructor
     *                      and parameter index, read from {@code jacksonMember})
     * @param kind          the value position, selecting the size/range keyword family
     * @param jacksonMember the member's Jackson-resolved annotated member — an
     *                      {@link com.fasterxml.jackson.databind.introspect.AnnotatedParameter} for a
     *                      creator parameter, an
     *                      {@link com.fasterxml.jackson.databind.introspect.AnnotatedMethod} for a
     *                      setter or builder method; may be {@code null}
     * @param builtProperty the built type's Jackson-introspected property for the member's wire name,
     *                      resolved once by {@link InputPropertyDescriber#translateConstraints} and
     *                      shared by every call for this member; {@code null} when Jackson reports no
     *                      property of that wire name for the built type at all
     * @return the resolved constraints; {@link ResolvedConstraints#NONE} when nothing joins
     */
    ResolvedConstraints forUnscopedMember(
            Class<?> builtClass,
            String javaName,
            ConstraintValueKind kind,
            AnnotatedMember jacksonMember,
            BeanPropertyDefinition builtProperty);
}
