// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches {@link InputPolicyMetadata} for target types by reflecting on
 * {@code @Canonicalize}, {@code @Sanitize}, {@code @SkipCanonicalization}, and
 * {@code @SkipSanitization} annotations.
 *
 * <p>Conflict detection: if both {@code @Canonicalize} and {@code @SkipCanonicalization} are
 * declared on the same element (or both {@code @Sanitize} and {@code @SkipSanitization}),
 * an {@link IllegalStateException} is thrown at resolution time.
 *
 * <p>Cycle detection: self-referential or mutually-referential types are handled by tracking
 * visited types and enforcing a maximum traversal depth of {@value #MAX_DEPTH}.
 *
 * <p>Field classification strips {@code java.util.Optional} layers and normalizes bounded type
 * arguments to their upper bound before deciding a field's shape, so {@code Optional<NestedDto>},
 * {@code Optional<? extends NestedDto>} and {@code List<? extends NestedDto>} all resolve nested
 * metadata from {@code NestedDto}. See {@link #buildFieldMeta} for the full rule; the APT-time
 * {@code AnnotationCollector} in {@code vertique-codegen-sanitization} applies the same rules so
 * the generated and reflective paths agree.
 *
 * <p>Thread-safe: metadata is computed once per type and cached via {@link ConcurrentHashMap}.
 */
class InputPolicyMetadataResolver {

    private static final int MAX_DEPTH = 10;

    private final Map<Class<?>, InputPolicyMetadata> cache = new ConcurrentHashMap<>();

    /**
     * Resolves {@link InputPolicyMetadata} for the given type, using a cached result if available.
     *
     * @param targetType the type to inspect
     * @return the resolved (and cached) metadata; never {@code null}
     * @throws IllegalStateException if conflicting annotations are detected on the same element
     */
    public InputPolicyMetadata resolve(Class<?> targetType) {
        return cache.computeIfAbsent(targetType, t -> resolveInternal(t, new HashSet<>(), 0));
    }

    /**
     * Internal recursive resolution with cycle-detection state.
     *
     * @param type    the type being resolved
     * @param visited set of types already in the current resolution stack
     * @param depth   current traversal depth
     * @return resolved metadata, or {@link InputPolicyMetadata#EMPTY} when a cycle/depth limit is hit
     */
    private InputPolicyMetadata resolveInternal(Class<?> type, Set<Class<?>> visited, int depth) {
        if (visited.contains(type) || depth >= MAX_DEPTH) {
            return InputPolicyMetadata.EMPTY;
        }
        visited.add(type);

        // --- Type-level annotations ---
        Canonicalize typeCanonicalize = AnnotationResolver.findMetaAnnotation(type, Canonicalize.class);
        Sanitize typeSanitize = AnnotationResolver.findMetaAnnotation(type, Sanitize.class);
        SkipCanonicalization typeSkipCanon = AnnotationResolver.findMetaAnnotation(type, SkipCanonicalization.class);
        SkipSanitization typeSkipSanit = AnnotationResolver.findMetaAnnotation(type, SkipSanitization.class);

        if (typeCanonicalize != null && typeSkipCanon != null) {
            throw new IllegalStateException(
                    "Type " + type.getName() + " has both @Canonicalize and @SkipCanonicalization — "
                            + "these annotations are mutually exclusive.");
        }
        if (typeSanitize != null && typeSkipSanit != null) {
            throw new IllegalStateException("Type " + type.getName() + " has both @Sanitize and @SkipSanitization — "
                    + "these annotations are mutually exclusive.");
        }

        List<Class<? extends Canonicalizer>> objectCanonChain =
                typeCanonicalize != null ? Arrays.asList(typeCanonicalize.value()) : List.of();
        List<Class<? extends Sanitizer>> objectSanitChain =
                typeSanitize != null ? Arrays.asList(typeSanitize.value()) : List.of();
        boolean skipCanon = typeSkipCanon != null;
        boolean skipSanit = typeSkipSanit != null;

        // --- Field/component-level annotations ---
        Map<String, FieldPolicyMetadata> fields = resolveFields(type, visited, depth);

        visited.remove(type);

        return new InputPolicyMetadata(objectCanonChain, objectSanitChain, skipCanon, skipSanit, fields);
    }

    /**
     * Resolves per-field metadata for the given type, supporting both regular classes (via
     * {@link Field}) and records (via {@link RecordComponent}).
     *
     * @param type    the type whose fields to inspect
     * @param visited the current visited set (passed through for recursion)
     * @param depth   current depth
     * @return a map of field name to {@link FieldPolicyMetadata}
     */
    private Map<String, FieldPolicyMetadata> resolveFields(Class<?> type, Set<Class<?>> visited, int depth) {
        Map<String, FieldPolicyMetadata> result = new LinkedHashMap<>();

        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                FieldPolicyMetadata meta = resolveRecordComponent(component, visited, depth);
                if (meta != null) {
                    result.put(component.getName(), meta);
                }
            }
        } else {
            // Walk all declared fields up the hierarchy
            for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                for (Field field : cls.getDeclaredFields()) {
                    if (result.containsKey(field.getName())) continue; // subclass overrides
                    FieldPolicyMetadata meta = resolveField(field, visited, depth);
                    if (meta != null) {
                        result.put(field.getName(), meta);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Resolves metadata for a single {@link Field}, returning {@code null} if the field type
     * is a primitive, boxed primitive, or otherwise not worth tracking.
     *
     * @param field   the field to inspect
     * @param visited visited type set for recursion
     * @param depth   current depth
     * @return metadata, or {@code null} if the field needs no processing
     */
    private FieldPolicyMetadata resolveField(Field field, Set<Class<?>> visited, int depth) {
        Canonicalize canon = AnnotationResolver.findMetaAnnotation(field, Canonicalize.class);
        Sanitize sanit = AnnotationResolver.findMetaAnnotation(field, Sanitize.class);
        SkipCanonicalization skipCanon = AnnotationResolver.findMetaAnnotation(field, SkipCanonicalization.class);
        SkipSanitization skipSanit = AnnotationResolver.findMetaAnnotation(field, SkipSanitization.class);

        checkConflicts(field.getDeclaringClass().getName() + "#" + field.getName(), canon, skipCanon, sanit, skipSanit);

        return buildFieldMeta(
                field.getGenericType(),
                field.getType(),
                canon,
                sanit,
                skipCanon != null,
                skipSanit != null,
                visited,
                depth);
    }

    /**
     * Resolves metadata for a single {@link RecordComponent}, returning {@code null} if the
     * component type does not need processing. Checks both the component and its accessor method
     * for annotations.
     *
     * @param component the record component to inspect
     * @param visited   visited type set for recursion
     * @param depth     current depth
     * @return metadata, or {@code null} if the component needs no processing
     */
    private FieldPolicyMetadata resolveRecordComponent(RecordComponent component, Set<Class<?>> visited, int depth) {
        Canonicalize canon = getAnnotation(component, Canonicalize.class);
        Sanitize sanit = getAnnotation(component, Sanitize.class);
        SkipCanonicalization skipCanon = getAnnotation(component, SkipCanonicalization.class);
        SkipSanitization skipSanit = getAnnotation(component, SkipSanitization.class);

        checkConflicts(
                component.getDeclaringRecord().getName() + "#" + component.getName(),
                canon,
                skipCanon,
                sanit,
                skipSanit);

        return buildFieldMeta(
                component.getGenericType(),
                component.getType(),
                canon,
                sanit,
                skipCanon != null,
                skipSanit != null,
                visited,
                depth);
    }

    /**
     * Builds {@link FieldPolicyMetadata} based on the field's type and annotation state.
     * Returns {@code null} for types that do not need processing (primitives, boxed types,
     * and non-container non-string types without annotations or skip flags).
     *
     * <p>{@code java.util.Optional<T>} is <em>transparent</em> here: the intermediate wire value of
     * an {@code Optional<T>} field is the unwrapped {@code T} (Jackson's {@code Jdk8Module}
     * serializes the payload, not the wrapper), so the field is classified by {@code T}. Without
     * this normalization, {@code Optional<NestedDto>} recurses into {@code Optional}'s own fields,
     * yields empty metadata, and returns {@code null} — at which point
     * {@link DefaultInputObjectProcessor} walks the nested map with
     * {@link InputPolicyMetadata#EMPTY} and the nested DTO's own chains never run. A raw
     * {@code Optional} has no type argument to classify against and falls back to the
     * no-schema branch.
     *
     * <p>Bounded type arguments are normalized to their upper bound by {@link #normalizeToBound}
     * first, so {@code Optional<? extends NestedDto>} and {@code Optional<T extends NestedDto>}
     * classify against {@code NestedDto} — the type javac writes into the erased signature and
     * therefore the type Jackson binds. {@code Optional<?>} and {@code Optional<? super X>}
     * normalize to {@code java.lang.Object} and stay unclassified. These rules are symmetric with
     * the APT-time {@code AnnotationCollector} in {@code vertique-codegen-sanitization}.
     *
     * @param genericType the full generic type of the field
     * @param rawType     the raw (erased) class of the field
     * @param canon       {@code @Canonicalize} annotation, or {@code null}
     * @param sanit       {@code @Sanitize} annotation, or {@code null}
     * @param skipCanon   whether {@code @SkipCanonicalization} is present
     * @param skipSanit   whether {@code @SkipSanitization} is present
     * @param visited     visited type set for recursion
     * @param depth       current depth
     * @return field metadata, or {@code null}
     */
    private FieldPolicyMetadata buildFieldMeta(
            Type genericType,
            Class<?> rawType,
            Canonicalize canon,
            Sanitize sanit,
            boolean skipCanon,
            boolean skipSanit,
            Set<Class<?>> visited,
            int depth) {

        List<Class<? extends Canonicalizer>> canonChain = canon != null ? Arrays.asList(canon.value()) : List.of();
        List<Class<? extends Sanitizer>> sanitChain = sanit != null ? Arrays.asList(sanit.value()) : List.of();

        boolean hasAnnotations = !canonChain.isEmpty() || !sanitChain.isEmpty() || skipCanon || skipSanit;

        // Optional<T> wrapper — classify by the wrapped type (see method javadoc).
        if (rawType == Optional.class) {
            Type wrapped = normalizeToBound(optionalTypeArgument(genericType));
            Class<?> wrappedRaw = rawClassOf(wrapped);
            if (wrappedRaw == null) {
                // Raw Optional (or a type argument that does not resolve to a class) — nothing to
                // classify against.
                if (hasAnnotations) {
                    return new FieldPolicyMetadata(
                            canonChain, sanitChain, skipCanon, skipSanit, rawType, null, false, false, null);
                }
                return null;
            }
            return buildFieldMeta(wrapped, wrappedRaw, canon, sanit, skipCanon, skipSanit, visited, depth);
        }

        // String field
        if (rawType == String.class) {
            return new FieldPolicyMetadata(
                    canonChain, sanitChain, skipCanon, skipSanit, rawType, null, true, false, null);
        }

        // Collection types
        if (Collection.class.isAssignableFrom(rawType)) {
            Class<?> elementType = extractCollectionElementType(genericType);
            if (elementType == null) {
                // Raw collection — can't determine element type
                if (hasAnnotations) {
                    return new FieldPolicyMetadata(
                            canonChain, sanitChain, skipCanon, skipSanit, rawType, null, false, false, null);
                }
                return null;
            }
            if (elementType == String.class) {
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, null, false, true, null);
            }
            // Collection of objects — recurse for element metadata
            if (!isPrimitiveOrBoxed(elementType)) {
                InputPolicyMetadata elementMeta = resolveInternal(elementType, new HashSet<>(visited), depth + 1);
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, elementMeta, false, false, elementType);
            }
            if (hasAnnotations) {
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, null, false, false, elementType);
            }
            return null;
        }

        // Nested object — recurse
        if (!isPrimitiveOrBoxed(rawType) && rawType != Object.class && !rawType.isEnum()) {
            InputPolicyMetadata nestedMeta = resolveInternal(rawType, new HashSet<>(visited), depth + 1);
            if (hasAnnotations || !nestedMeta.isEmpty()) {
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, nestedMeta, false, false, null);
            }
            return null;
        }

        // Primitives / enums / Object / etc.
        if (hasAnnotations) {
            return new FieldPolicyMetadata(
                    canonChain, sanitChain, skipCanon, skipSanit, rawType, null, false, false, null);
        }
        return null;
    }

    // --- Annotation helpers ---

    /**
     * Retrieves an annotation from a record component, falling back to the accessor method.
     * Supports meta-annotations (composed annotations) via {@link AnnotationResolver}.
     *
     * @param <A>            the annotation type
     * @param component      the record component
     * @param annotationType the annotation class
     * @return the annotation instance, or {@code null} if absent on both the component and accessor
     */
    private static <A extends Annotation> A getAnnotation(RecordComponent component, Class<A> annotationType) {
        A ann = AnnotationResolver.findMetaAnnotation(component, annotationType);
        if (ann != null) return ann;
        return AnnotationResolver.findMetaAnnotation(component.getAccessor(), annotationType);
    }

    /**
     * Throws {@link IllegalStateException} if conflicting annotations are detected.
     *
     * @param location  human-readable location string for the error message
     * @param canon     {@code @Canonicalize} or {@code null}
     * @param skipCanon {@code @SkipCanonicalization} or {@code null}
     * @param sanit     {@code @Sanitize} or {@code null}
     * @param skipSanit {@code @SkipSanitization} or {@code null}
     */
    private static void checkConflicts(
            String location,
            Canonicalize canon,
            SkipCanonicalization skipCanon,
            Sanitize sanit,
            SkipSanitization skipSanit) {
        if (canon != null && skipCanon != null) {
            throw new IllegalStateException(location + " has both @Canonicalize and @SkipCanonicalization — "
                    + "these annotations are mutually exclusive.");
        }
        if (sanit != null && skipSanit != null) {
            throw new IllegalStateException(location + " has both @Sanitize and @SkipSanitization — "
                    + "these annotations are mutually exclusive.");
        }
    }

    // --- Type classification helpers ---

    /**
     * Extracts the element type from a generic {@link Collection} type, normalizing the element
     * type argument to its upper bound and unwrapping any {@code java.util.Optional} layers.
     *
     * <p>So {@code List<Optional<String>>} yields {@code String}, and
     * {@code List<? extends NestedDto>} and {@code List<Optional<? extends NestedDto>>} both yield
     * {@code NestedDto}. This keeps the reflective walker symmetric with the APT-time
     * {@code AnnotationCollector} in {@code vertique-codegen-sanitization}.
     *
     * <p>{@code java.lang.Object} carries no schema, so an element type that resolves to it —
     * {@code List<Object>}, and {@code List<?>} / {@code List<? super X>} after bound
     * normalization — is reported as not determinable. That mirrors both the nested-object
     * branch's {@code rawType != Object.class} guard in {@link #buildFieldMeta} and the
     * collector's treatment of {@code Object} as a scalar leaf, and it routes the field to the
     * no-element-schema branch where inherited chains still reach string elements.
     *
     * @param type the generic type
     * @return the element class, or {@code null} if not determinable (raw collection, raw
     *         {@code Optional} element, an {@code Object} element, or an element type that
     *         resolves to no class)
     */
    private static Class<?> extractCollectionElementType(Type type) {
        if (!(type instanceof ParameterizedType pt)) {
            return null;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args.length != 1) {
            return null;
        }
        Type element = normalizeToBound(args[0]);
        Class<?> elementRaw = rawClassOf(element);
        while (elementRaw == Optional.class) {
            element = normalizeToBound(optionalTypeArgument(element));
            elementRaw = rawClassOf(element);
        }
        return elementRaw == Object.class ? null : elementRaw;
    }

    /**
     * Returns the single type argument of an {@code Optional<T>} generic type.
     *
     * @param type the {@code Optional} generic type
     * @return the {@code T} type, or {@code null} for a raw {@code Optional}
     */
    private static Type optionalTypeArgument(Type type) {
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                return args[0];
            }
        }
        return null;
    }

    /**
     * Normalizes a type argument to the type Jackson actually binds against.
     *
     * <ul>
     *   <li>{@link WildcardType} — resolves to the first upper bound. Reflection reports
     *       {@code java.lang.Object} as the upper bound of an unbounded {@code ?} and of a
     *       lower-bounded {@code ? super X}, so both normalize to {@code Object}.</li>
     *   <li>{@link TypeVariable} — resolves to the first declared bound. For an intersection
     *       bound ({@code T extends A & B}) that is {@code A}, which is also the type javac
     *       writes into the erased field signature.</li>
     *   <li>Anything else is returned unchanged.</li>
     * </ul>
     *
     * <p>Bounds may themselves be wildcards or type variables, so resolution recurses. Java
     * forbids circular type-variable bounds, so the recursion terminates.
     *
     * @param type the type to normalize; may be {@code null}
     * @return the normalized type, or {@code null} when {@code type} is {@code null}
     */
    private static Type normalizeToBound(Type type) {
        if (type == null) {
            return null;
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upperBounds = wildcard.getUpperBounds();
            return upperBounds.length == 0 ? Object.class : normalizeToBound(upperBounds[0]);
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            Type[] bounds = typeVariable.getBounds();
            return bounds.length == 0 ? Object.class : normalizeToBound(bounds[0]);
        }
        return type;
    }

    /**
     * Extracts the raw {@link Class} from a {@link Type}, handling plain classes and
     * parameterized types.
     *
     * @param type the type to erase; may be {@code null}
     * @return the raw class, or {@code null} when the type has no resolvable raw class
     */
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }

    /**
     * Returns {@code true} for primitive types and their boxed counterparts, as well as
     * other common immutable scalar types that cannot carry annotations and need no recursion.
     *
     * @param type the type to test
     * @return {@code true} if the type is a primitive or boxed primitive
     */
    private static boolean isPrimitiveOrBoxed(Class<?> type) {
        return type.isPrimitive()
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == Number.class
                || type == java.math.BigDecimal.class
                || type == java.math.BigInteger.class
                || type == java.time.LocalDate.class
                || type == java.time.LocalDateTime.class
                || type == java.time.OffsetDateTime.class
                || type == java.time.ZonedDateTime.class
                || type == java.time.Instant.class
                || type == java.util.UUID.class;
    }
}
