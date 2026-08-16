// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.github.victools.jsonschema.generator.CustomPropertyDefinition;
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Fails generation when a property carries a non-default {@code @Schema(implementation = ...)} while
 * its <em>declared</em> type graph contains a class the active profile overrides.
 *
 * <p>Why this must fail rather than compose: at the pinned Victools version,
 * {@code Swagger2Module.resolveTargetTypeOverrides(MemberScope)} redirects the member's resolved
 * target type <em>before</em> general-type definition lookup runs. The profile's custom definition
 * for the declared class is therefore never consulted for that member, and the fragment — the wire
 * contract the profile's mapper actually produces — is dropped with no trace in the document. A
 * silently wrong schema is the one outcome this package will not emit, so the combination is
 * rejected. Turning the failure into a real conjunction later is a compatible evolution.
 *
 * <h2>Why a member-scope hook</h2>
 *
 * <p>The check is implemented as a Victools {@link CustomPropertyDefinitionProvider} rather than a
 * reflective pre-pass over the requested root type, for two reasons:
 *
 * <ul>
 *   <li><strong>Reach.</strong> Victools visits the members of every type it generates, so a
 *       member-scope hook covers nested DTOs, collection element types, and inherited members for
 *       free. A root-only reflective walk would silently miss all of them.
 *   <li><strong>Timing.</strong> This provider is consulted at exactly the moment the member's schema
 *       is about to be produced — the moment the fragment would otherwise be dropped — and it is the
 *       one member-scope hook that could legitimately <em>supply</em> the conjoined definition should
 *       the deferred true-conjunction behavior ever be implemented.
 * </ul>
 *
 * <p>The provider never returns a definition: it either throws or returns {@code null} to leave the
 * member entirely to Victools.
 *
 * <h2>What "declared type graph" means</h2>
 *
 * <p>The graph is read from {@link MemberScope#getDeclaredType()} — a Classmate {@link ResolvedType}
 * — for two reasons. First, it is the member's <em>pre-redirect</em> type: at the pinned Victools
 * version the target-type redirect lives in the separate {@link MemberScope#getOverriddenType()}
 * accessor, so the declared type is exactly as immune to the redirect as raw reflection would be.
 * Second, unlike {@link Field#getGenericType()} / {@link Method#getGenericReturnType()}, it resolves
 * type variables against the declaring context, so a member inherited from a generic supertype —
 * {@code class Base<T> { T amount; }} bound by {@code class Derived extends Base<BigDecimal>} —
 * yields {@code BigDecimal} rather than an unreadable type variable. Raw reflection would report no
 * override for exactly the member Victools then redirects, which is the silent drop this guard
 * exists to prevent.
 *
 * <p>The graph comprises the declared type itself plus, recursively, its resolved type parameters
 * and array element types. <strong>Map key positions are excluded</strong>: for a {@link Map} with
 * two type parameters the key parameter is skipped, matching the override contract, under which a
 * map key stays mapper-owned. The walk is bounded by {@link TypeGrammar#MAX_DEPTH}, and exceeding
 * that bound <em>fails</em> rather than resolving to "no override found" — a depth bound inside a
 * fail-closed guard must never fail open.
 *
 * <h2>How the {@code @Schema} is resolved</h2>
 *
 * <p>The resolution mirrors {@code Swagger2Module.getSchemaAnnotationValue(MemberScope)} exactly,
 * because that is the method deciding whether the redirect happens. It reads, in order:
 *
 * <ol>
 *   <li>in a fake container item scope, {@code @ArraySchema.schema()} — and nothing else;
 *   <li>otherwise a direct {@code @Schema}; and
 *   <li>otherwise {@code @ArraySchema.arraySchema()}.
 * </ol>
 *
 * <p>Both annotations are resolved through
 * {@link MemberScope#getAnnotationConsideringFieldAndGetter(Class)}, so an annotation on either a
 * field or its accessor counts, exactly as the Swagger module resolves it. In all three branches the
 * sentinel default {@code Void.class} means "no redirect declared". Covering only the direct
 * {@code @Schema} would leave both {@code @ArraySchema} forms silently dropping the fragment.
 *
 * @param <M> the member scope kind this instance is registered for
 */
final class SchemaImplementationGuard<M extends MemberScope<?, ?>> implements CustomPropertyDefinitionProvider<M> {

    /** Maximum length, in UTF-16 code units, of the member identity rendered in a failure message. */
    private static final int MAX_MEMBER_NAME_LENGTH = 128;

    /** The validated, direction-filtered override declarations the guard tests the graph against. */
    private final ValidatedProfile profile;

    /**
     * Creates a guard over one direction's validated declarations.
     *
     * @param profile the validated, direction-filtered profile view
     */
    SchemaImplementationGuard(ValidatedProfile profile) {
        this.profile = profile;
    }

    /**
     * Inspects a member and fails generation when it combines an implementation redirect with an
     * overridden declared type; never supplies a definition of its own.
     *
     * @param member  the member Victools is about to generate a schema for
     * @param context the active generation context, unused by this guard
     * @return always {@code null}, leaving the member to Victools
     * @throws JsonSchemaGenerationException if the member combines a non-default
     *     {@code implementation = ...} redirect with an overridden declared type, or if its declared
     *     type graph nests deeper than the guard can walk
     */
    @Override
    public CustomPropertyDefinition provideCustomSchemaDefinition(M member, SchemaGenerationContext context) {
        if (member == null) {
            return null;
        }
        Schema declaredSchema = redirectingSchemaAnnotation(member);
        if (declaredSchema == null) {
            return null;
        }
        // Class.class-typed annotation members are never null (the compiler rejects a null default
        // or literal), so only the sentinel default Void.class needs to be checked here.
        Class<?> implementation = declaredSchema.implementation();
        if (implementation == Void.class) {
            return null;
        }

        Member rawMember = member.getRawMember();
        String boundedName = Diagnostics.truncate(memberName(member, rawMember), MAX_MEMBER_NAME_LENGTH);
        Class<?> overridden = firstOverriddenClass(member.getDeclaredType(), 0, boundedName);
        if (overridden == null) {
            return null;
        }
        throw Diagnostics.failure(
                "cannot generate a JSON Schema: property '" + boundedName
                        + "' declares a @Schema implementation redirect while its declared type graph carries an"
                        + " effective schema override for " + Diagnostics.typeIdentity(overridden),
                null);
    }

    /**
     * Resolves the {@code @Schema} whose {@code implementation} member the Swagger module would read
     * for this member, mirroring {@code Swagger2Module.getSchemaAnnotationValue(MemberScope)}: an
     * {@code @ArraySchema.schema()} in a fake container item scope, otherwise a direct
     * {@code @Schema}, otherwise an {@code @ArraySchema.arraySchema()}.
     *
     * @param member the member scope being inspected
     * @return the annotation the redirect would be read from, or {@code null} when none applies
     */
    private static Schema redirectingSchemaAnnotation(MemberScope<?, ?> member) {
        if (member.isFakeContainerItemScope()) {
            // In a fake container item scope the Swagger module reads the element metadata only, so a
            // direct @Schema on the container member is deliberately not consulted here.
            ArraySchema arraySchema = member.getAnnotationConsideringFieldAndGetter(ArraySchema.class);
            return arraySchema == null ? null : arraySchema.schema();
        }
        Schema declaredSchema = member.getAnnotationConsideringFieldAndGetter(Schema.class);
        if (declaredSchema != null) {
            return declaredSchema;
        }
        ArraySchema arraySchema = member.getAnnotationConsideringFieldAndGetter(ArraySchema.class);
        return arraySchema == null ? null : arraySchema.arraySchema();
    }

    /**
     * Renders the bounded identity of the offending member.
     *
     * @param member    the member scope
     * @param rawMember the underlying reflective member, possibly {@code null}
     * @return the qualified member name
     */
    private static String memberName(MemberScope<?, ?> member, Member rawMember) {
        if (rawMember == null) {
            return String.valueOf(member.getName());
        }
        return rawMember.getDeclaringClass().getSimpleName() + "." + rawMember.getName();
    }

    /**
     * Finds the first class in a resolved declared type graph that carries an effective override.
     *
     * <p>Exceeding {@link TypeGrammar#MAX_DEPTH} throws instead of returning {@code null}: past the
     * bound the guard has not proven the absence of an override, and reporting "none found" would
     * turn the bound into a silent bypass of the very drop this guard refuses to emit.
     *
     * @param type       the graph node to inspect, possibly {@code null}
     * @param depth      the current nesting depth
     * @param memberName the bounded member identity to name in a depth failure
     * @return the overridden class, or {@code null} when the graph carries none
     * @throws JsonSchemaGenerationException if the graph nests past {@link TypeGrammar#MAX_DEPTH}
     */
    private Class<?> firstOverriddenClass(ResolvedType type, int depth, String memberName) {
        if (type == null) {
            return null;
        }
        if (depth > TypeGrammar.MAX_DEPTH) {
            throw Diagnostics.failure(
                    "cannot generate a JSON Schema: property '" + memberName
                            + "' declares a @Schema implementation redirect and its declared type nesting exceeds the"
                            + " supported depth of " + TypeGrammar.MAX_DEPTH
                            + ", so an effective schema override cannot be ruled out",
                    null);
        }
        Class<?> erasedType = type.getErasedType();
        if (profile.fragmentFor(erasedType) != null) {
            return erasedType;
        }
        if (type.isArray()) {
            return firstOverriddenClass(type.getArrayElementType(), depth + 1, memberName);
        }
        List<ResolvedType> parameters = type.getTypeParameters();
        boolean mapKeyExcluded = Map.class.isAssignableFrom(erasedType) && parameters.size() == 2;
        for (int index = 0; index < parameters.size(); index++) {
            if (mapKeyExcluded && index == 0) {
                continue;
            }
            Class<?> hit = firstOverriddenClass(parameters.get(index), depth + 1, memberName);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
