// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.github.victools.jsonschema.generator.CustomPropertyDefinition;
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
 * <p>The graph is read from the underlying {@link Field} or {@link Method} through plain JDK
 * reflection — {@link Field#getGenericType()} / {@link Method#getGenericReturnType()} — precisely
 * because that is immune to the target-type redirect the guard exists to catch. It comprises the
 * declared type itself plus, recursively, its type arguments and array component types.
 * <strong>Map key positions are excluded</strong>: for a {@link Map} with two type arguments the key
 * argument is skipped, matching the override contract, under which a map key stays mapper-owned. An
 * unresolved position — a type variable or wildcard — contributes nothing, since no declared class
 * can be read from it.
 *
 * <p>Annotations are resolved through
 * {@link MemberScope#getAnnotationConsideringFieldAndGetter(Class)}, so a {@code @Schema} on either a
 * field or its accessor counts, exactly as the Swagger module resolves it.
 *
 * @param <M> the member scope kind this instance is registered for
 */
final class SchemaImplementationGuard<M extends MemberScope<?, ?>> implements CustomPropertyDefinitionProvider<M> {

    /**
     * Maximum nesting depth walked through a declared type graph. A declared type this deep is
     * pathological, and bounding the walk keeps a hostile generic declaration from costing unbounded
     * work.
     */
    private static final int MAX_DEPTH = 64;

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
     *     {@code @Schema(implementation = ...)} with an overridden declared type
     */
    @Override
    public CustomPropertyDefinition provideCustomSchemaDefinition(M member, SchemaGenerationContext context) {
        if (member == null || member.isFakeContainerItemScope()) {
            // A fake container item scope re-presents the same raw member; the real member scope
            // already carries the check, so re-running it here would only duplicate the work.
            return null;
        }
        Schema declaredSchema = member.getAnnotationConsideringFieldAndGetter(Schema.class);
        if (declaredSchema == null) {
            return null;
        }
        Class<?> implementation = declaredSchema.implementation();
        if (implementation == null || implementation == Void.class) {
            return null;
        }

        Member rawMember = member.getRawMember();
        Class<?> overridden = firstOverriddenClass(declaredTypeOf(rawMember), 0);
        if (overridden == null) {
            return null;
        }
        throw Diagnostics.failure(
                "cannot generate a JSON Schema: property '"
                        + Diagnostics.truncate(memberName(member, rawMember), MAX_MEMBER_NAME_LENGTH)
                        + "' declares a @Schema implementation redirect while its declared type graph carries an"
                        + " effective schema override for " + Diagnostics.typeIdentity(overridden),
                null);
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
     * Reads a reflective member's declared generic type.
     *
     * @param rawMember the underlying field or method, possibly {@code null}
     * @return the declared generic type, or {@code null} for an unrecognised member kind
     */
    private static Type declaredTypeOf(Member rawMember) {
        if (rawMember instanceof Field field) {
            return field.getGenericType();
        }
        if (rawMember instanceof Method method) {
            return method.getGenericReturnType();
        }
        return null;
    }

    /**
     * Finds the first class in a declared type graph that carries an effective override.
     *
     * @param type  the graph node to inspect, possibly {@code null}
     * @param depth the current nesting depth
     * @return the overridden class, or {@code null} when the graph carries none
     */
    private Class<?> firstOverriddenClass(Type type, int depth) {
        if (type == null || depth > MAX_DEPTH) {
            return null;
        }
        if (type instanceof Class<?> rawClass) {
            if (profile.fragmentFor(rawClass) != null) {
                return rawClass;
            }
            return rawClass.isArray() ? firstOverriddenClass(rawClass.getComponentType(), depth + 1) : null;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type rawType = parameterized.getRawType();
            Class<?> hit = firstOverriddenClass(rawType, depth + 1);
            if (hit != null) {
                return hit;
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            boolean mapKeyExcluded = rawType instanceof Class<?> rawClass
                    && Map.class.isAssignableFrom(rawClass)
                    && arguments.length == 2;
            for (int index = 0; index < arguments.length; index++) {
                if (mapKeyExcluded && index == 0) {
                    continue;
                }
                hit = firstOverriddenClass(arguments[index], depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }
        if (type instanceof GenericArrayType genericArray) {
            return firstOverriddenClass(genericArray.getGenericComponentType(), depth + 1);
        }
        // A type variable, a wildcard, or an unknown Type implementation names no declared class.
        return null;
    }
}
