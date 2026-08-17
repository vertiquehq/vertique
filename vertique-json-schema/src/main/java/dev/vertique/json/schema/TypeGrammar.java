// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * The accepted {@link Type} grammar for {@link AnnotationJsonSchemaGenerator#generateCanonical(Type)}.
 *
 * <p>Accepted, recursively:
 *
 * <ul>
 *   <li>a non-null {@link Class}, including a primitive class, an array class, and a raw generic
 *       class (raw generics stay accepted for current REST compatibility);
 *   <li>a {@link ParameterizedType} whose optional owner type, raw type, and type arguments are all
 *       themselves accepted; and
 *   <li>a {@link GenericArrayType} whose component type is accepted.
 * </ul>
 *
 * <p>Rejected: {@code null}, a {@link TypeVariable}, a {@link WildcardType}, any nested occurrence
 * of either unresolved form, and an unknown custom {@code Type} implementation. The rejection is
 * eager — it runs before Victools is invoked — so an unrepresentable type never reaches the
 * generator and never produces a partially built document.
 */
final class TypeGrammar {

    /**
     * Maximum nesting depth walked while validating a type, and while walking a declared type graph
     * for {@link SchemaImplementationGuard}. A resolved or declared type this deep is pathological;
     * refusing it bounds the walk against a maliciously or accidentally cyclic custom {@code Type}
     * implementation, which — unlike a recursive object graph — has no fixed point, and keeps a
     * hostile generic declaration from costing unbounded work.
     */
    static final int MAX_DEPTH = 64;

    private TypeGrammar() {}

    /**
     * Validates that a type is within the accepted grammar.
     *
     * @param type the type to validate
     * @throws JsonSchemaGenerationException if the type is {@code null} or contains an unresolved or
     *     unknown form at any depth
     */
    static void requireGeneratable(Type type) {
        if (type == null) {
            throw Diagnostics.failure("cannot generate a JSON Schema: the requested type is null", null);
        }
        validate(type, type, 0);
    }

    /**
     * Returns whether a type is one of the reflection forms this package understands, whether or not
     * it is <em>accepted</em>. Used by {@link Diagnostics} to decide when {@link Type#getTypeName()}
     * is safe to render.
     *
     * @param type the type to classify
     * @return {@code true} for a {@link Class}, {@link ParameterizedType}, {@link GenericArrayType},
     *     {@link TypeVariable}, or {@link WildcardType}
     */
    static boolean isKnownForm(Type type) {
        return type instanceof Class<?>
                || type instanceof ParameterizedType
                || type instanceof GenericArrayType
                || type instanceof TypeVariable<?>
                || type instanceof WildcardType;
    }

    /**
     * Recursively validates one node of the requested type's structure.
     *
     * @param requested the type the caller asked for, named in any failure message
     * @param current   the node currently being validated
     * @param depth     the current nesting depth
     * @throws JsonSchemaGenerationException if this node, or anything below it, is outside the grammar
     */
    private static void validate(Type requested, Type current, int depth) {
        if (depth > MAX_DEPTH) {
            throw reject(requested, "its type nesting exceeds the supported depth of " + MAX_DEPTH);
        }
        if (current == null) {
            throw reject(requested, "it contains a null nested type");
        }
        if (current instanceof Class<?>) {
            return;
        }
        if (current instanceof ParameterizedType parameterized) {
            Type owner = parameterized.getOwnerType();
            if (owner != null) {
                validate(requested, owner, depth + 1);
            }
            validate(requested, parameterized.getRawType(), depth + 1);
            for (Type argument : parameterized.getActualTypeArguments()) {
                validate(requested, argument, depth + 1);
            }
            return;
        }
        if (current instanceof GenericArrayType genericArray) {
            validate(requested, genericArray.getGenericComponentType(), depth + 1);
            return;
        }
        if (current instanceof TypeVariable<?>) {
            throw reject(requested, "it contains an unresolved type variable");
        }
        if (current instanceof WildcardType) {
            throw reject(requested, "it contains an unresolved wildcard type");
        }
        throw reject(
                requested,
                "it contains an unknown java.lang.reflect.Type implementation ("
                        + Diagnostics.truncate(current.getClass().getName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                        + ")");
    }

    /**
     * Builds the bounded rejection for a type outside the grammar.
     *
     * @param requested the type the caller asked for
     * @param reason    the value-free reason the type is unrepresentable
     * @return the exception to throw
     */
    private static JsonSchemaGenerationException reject(Type requested, String reason) {
        return Diagnostics.failure(
                "cannot generate a JSON Schema for " + Diagnostics.typeIdentity(requested) + ": " + reason, null);
    }
}
