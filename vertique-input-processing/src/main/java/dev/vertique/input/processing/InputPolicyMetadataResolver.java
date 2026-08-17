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
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
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
 * <p><strong>Resolution is per type and non-recursive.</strong> The returned metadata describes
 * only {@code targetType}'s own fields; a field that holds a nested object records that field's
 * declared {@link Class}, and {@link DefaultInputObjectProcessor} calls {@link #resolve} again for
 * that class when it descends into the value. There is consequently no cycle state and no depth
 * budget: a self-referential type resolves once and applies at every level, direct and mutual
 * recursion behave identically, and a policy declared behind any number of policy-free links is
 * still found. Traversal terminates on the finite intermediate data instead.
 *
 * <p>Field classification runs through {@link TypeClassifier}, the single rule set this module also
 * applies to {@link DefaultInputObjectProcessor}'s entry-point target type: {@code java.util.Optional}
 * layers are stripped and bounded type arguments are normalized to their upper bound before a
 * field's shape is decided, so {@code Optional<NestedDto>}, {@code Optional<? extends NestedDto>}
 * and {@code List<? extends NestedDto>} all classify against {@code NestedDto}. See
 * {@link #buildFieldMeta} for the shape rules built on top; the APT-time
 * {@code AnnotationCollector} in {@code vertique-codegen-sanitization} applies the same rules so
 * the generated and reflective paths agree.
 *
 * <p>Thread-safe: metadata is computed once per type and cached via {@link ConcurrentHashMap}.
 */
class InputPolicyMetadataResolver {

    private final Map<Class<?>, InputPolicyMetadata> cache = new ConcurrentHashMap<>();

    /**
     * Resolves {@link InputPolicyMetadata} for the given type, using a cached result if available.
     *
     * @param targetType the type to inspect
     * @return the resolved (and cached) metadata; never {@code null}
     * @throws IllegalStateException if conflicting annotations are detected on the same element
     */
    public InputPolicyMetadata resolve(Class<?> targetType) {
        return cache.computeIfAbsent(targetType, this::resolveInternal);
    }

    /**
     * Answers {@link InputObjectProcessor#declaresPolicies(Type)}: whether any type reachable from
     * {@code targetType} declares a canonicalizer or sanitizer chain.
     *
     * <p>Walks the declared type graph breadth-first with a visited set, using a throw-away resolver
     * so no {@link Class} is retained past the call — this is a startup-time query, not a request-path
     * one. Descent follows exactly the links {@link #buildFieldMeta} records: a field's declared type
     * when it carries a property set, and a collection or array field's element type. Skip flags
     * declare nothing to run and are therefore not policies.
     *
     * @param targetType the entry-point type; must not be {@code null}
     * @return {@code true} if a declared chain exists anywhere in the reachable graph
     */
    static boolean declaresPolicies(Type targetType) {
        InputPolicyMetadataResolver resolver = new InputPolicyMetadataResolver();
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        enqueueDescendable(pending, TypeClassifier.classify(targetType));
        // A collection or array entry point carries its policies on the element type, not the container.
        enqueueDescendable(pending, TypeClassifier.elementType(targetType));

        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (!visited.add(type)) {
                continue;
            }
            InputPolicyMetadata metadata = resolver.resolve(type);
            if (!metadata.objectCanonicalizerChain().isEmpty()
                    || !metadata.objectSanitizerChain().isEmpty()) {
                return true;
            }
            for (FieldPolicyMetadata field : metadata.fields().values()) {
                if (!field.canonicalizerChain().isEmpty()
                        || !field.sanitizerChain().isEmpty()) {
                    return true;
                }
                if (field.collectionElementType() != null) {
                    enqueueDescendable(pending, field.collectionElementType());
                } else {
                    enqueueDescendable(pending, field.fieldType());
                }
            }
        }
        return false;
    }

    /**
     * Adds {@code type} to the traversal queue when it carries a property set worth resolving.
     *
     * @param pending the traversal queue
     * @param type    the candidate type; may be {@code null}
     */
    private static void enqueueDescendable(Deque<Class<?>> pending, @Nullable Class<?> type) {
        if (type != null && type != String.class && isDescendableObject(type)) {
            pending.add(type);
        }
    }

    /**
     * Resolves one type's own metadata. Never recurses into nested types, so this is safe to run
     * inside {@link ConcurrentHashMap#computeIfAbsent} and terminates on any type graph.
     *
     * @param type the type being resolved
     * @return the resolved metadata for {@code type} alone
     */
    private InputPolicyMetadata resolveInternal(Class<?> type) {
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
        Map<String, FieldPolicyMetadata> fields = resolveFields(type);

        return new InputPolicyMetadata(objectCanonChain, objectSanitChain, skipCanon, skipSanit, fields);
    }

    /**
     * Resolves per-field metadata for the given type, supporting both regular classes (via
     * {@link Field}) and records (via {@link RecordComponent}).
     *
     * @param type the type whose fields to inspect
     * @return a map of field name to {@link FieldPolicyMetadata}
     */
    private Map<String, FieldPolicyMetadata> resolveFields(Class<?> type) {
        Map<String, FieldPolicyMetadata> result = new LinkedHashMap<>();

        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                FieldPolicyMetadata meta = resolveRecordComponent(component);
                if (meta != null) {
                    result.put(component.getName(), meta);
                }
            }
        } else {
            // Walk all declared fields up the hierarchy
            for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                for (Field field : cls.getDeclaredFields()) {
                    if (result.containsKey(field.getName())) continue; // subclass overrides
                    FieldPolicyMetadata meta = resolveField(field);
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
     * @param field the field to inspect
     * @return metadata, or {@code null} if the field needs no processing
     */
    private FieldPolicyMetadata resolveField(Field field) {
        Canonicalize canon = AnnotationResolver.findMetaAnnotation(field, Canonicalize.class);
        Sanitize sanit = AnnotationResolver.findMetaAnnotation(field, Sanitize.class);
        SkipCanonicalization skipCanon = AnnotationResolver.findMetaAnnotation(field, SkipCanonicalization.class);
        SkipSanitization skipSanit = AnnotationResolver.findMetaAnnotation(field, SkipSanitization.class);

        checkConflicts(field.getDeclaringClass().getName() + "#" + field.getName(), canon, skipCanon, sanit, skipSanit);

        return buildFieldMeta(
                field.getGenericType(), field.getType(), canon, sanit, skipCanon != null, skipSanit != null);
    }

    /**
     * Resolves metadata for a single {@link RecordComponent}, returning {@code null} if the
     * component type does not need processing. Checks both the component and its accessor method
     * for annotations.
     *
     * @param component the record component to inspect
     * @return metadata, or {@code null} if the component needs no processing
     */
    private FieldPolicyMetadata resolveRecordComponent(RecordComponent component) {
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
                component.getGenericType(), component.getType(), canon, sanit, skipCanon != null, skipSanit != null);
    }

    /**
     * Builds {@link FieldPolicyMetadata} based on the field's type and annotation state.
     * Returns {@code null} for types that do not need processing (primitives, boxed types,
     * and non-container non-string types without annotations or skip flags).
     *
     * <p>A field whose declared type is a <em>descendable object</em> (see
     * {@link #isDescendableObject}) always yields metadata carrying that declared type, even when
     * neither the field nor the type declares any policy. That is what lets the walker descend
     * through policy-free links and still find a policy declared further down; dropping such a
     * field would strand the whole subtree on {@link InputPolicyMetadata#EMPTY}.
     *
     * <p>{@code java.util.Optional<T>} is <em>transparent</em> here: the intermediate wire value of
     * an {@code Optional<T>} field is the unwrapped {@code T} (Jackson's {@code Jdk8Module}
     * serializes the payload, not the wrapper), so the field is classified by {@code T}. Without
     * this normalization, {@code Optional<NestedDto>} would classify against {@code Optional}
     * itself and the nested DTO's own chains would never run. A raw {@code Optional} has no type
     * argument to classify against and falls back to the no-schema branch.
     *
     * <p>Bounded type arguments are normalized to their upper bound by
     * {@link TypeClassifier#normalize} first, so {@code Optional<? extends NestedDto>} and
     * {@code Optional<T extends NestedDto>} classify against {@code NestedDto} — the type javac
     * writes into the erased signature and therefore the type Jackson binds. {@code Optional<?>} and
     * {@code Optional<? super X>} normalize to {@code java.lang.Object} and stay unclassified. These
     * rules are symmetric with the APT-time {@code AnnotationCollector} in
     * {@code vertique-codegen-sanitization}.
     *
     * <p><strong>Arrays classify as collections.</strong> {@code NestedDto[]} and
     * {@code List<NestedDto>} arrive as the same JSON array and carry the same element schema, so an
     * array field records its component type as the collection element type and its elements are
     * descended into element-wise. An array whose component type is itself an array or carries no
     * schema takes the no-element-schema branch, where inherited chains still reach string leaves.
     *
     * <p><strong>Only a descendable element type is recorded.</strong> An element type that is a
     * scalar leaf — see {@link TypeClassifier#isScalarLeaf} — takes the no-element-schema branch
     * instead, exactly as {@link #isDescendableObject} excludes those types from a field-type
     * descent. Recording one would send the list into element-wise dispatch, where a JSON-string
     * value (an enum name, or a number Jackson coerces from a string) matches no nested-object shape
     * and is returned untouched, silently dropping every declared chain. The
     * APT-time {@code AnnotationCollector} applies the same rule through
     * {@code isScalarOrEnum}, so the generated and reflective paths agree.
     *
     * @param genericType the full generic type of the field
     * @param rawType     the raw (erased) class of the field
     * @param canon       {@code @Canonicalize} annotation, or {@code null}
     * @param sanit       {@code @Sanitize} annotation, or {@code null}
     * @param skipCanon   whether {@code @SkipCanonicalization} is present
     * @param skipSanit   whether {@code @SkipSanitization} is present
     * @return field metadata, or {@code null}
     */
    private FieldPolicyMetadata buildFieldMeta(
            Type genericType,
            Class<?> rawType,
            Canonicalize canon,
            Sanitize sanit,
            boolean skipCanon,
            boolean skipSanit) {

        List<Class<? extends Canonicalizer>> canonChain = canon != null ? Arrays.asList(canon.value()) : List.of();
        List<Class<? extends Sanitizer>> sanitChain = sanit != null ? Arrays.asList(sanit.value()) : List.of();

        boolean hasAnnotations = !canonChain.isEmpty() || !sanitChain.isEmpty() || skipCanon || skipSanit;

        // Optional<T> wrapper — classify by the wrapped type (see method javadoc).
        if (rawType == Optional.class) {
            Type wrapped = TypeClassifier.normalize(genericType);
            Class<?> wrappedRaw = TypeClassifier.rawClassOf(wrapped);
            if (wrappedRaw == null || wrappedRaw == Optional.class) {
                // Raw Optional (or a type argument that does not resolve to a class) — nothing to
                // classify against.
                return schemaFree(canonChain, sanitChain, skipCanon, skipSanit, rawType, hasAnnotations);
            }
            return buildFieldMeta(wrapped, wrappedRaw, canon, sanit, skipCanon, skipSanit);
        }

        // String field
        if (rawType == String.class) {
            return new FieldPolicyMetadata(canonChain, sanitChain, skipCanon, skipSanit, rawType, true, false, null);
        }

        // Collection and array types — both arrive as a JSON array on the wire and carry exactly
        // one element schema, so they classify through one branch.
        if (Collection.class.isAssignableFrom(rawType) || rawType.isArray()) {
            Class<?> elementType = TypeClassifier.elementType(genericType);
            if (elementType == null) {
                // Raw collection, or an element type with no schema — can't determine element type
                return schemaFree(canonChain, sanitChain, skipCanon, skipSanit, rawType, hasAnnotations);
            }
            if (elementType == String.class) {
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, false, true, null);
            }
            // Collection or array of objects — record the element type; its metadata is resolved at
            // descent. An enum element is excluded for the same reason isDescendableObject excludes
            // an enum field type: it carries no property set to descend into, so recording it would
            // route the list into element-wise dispatch, where a JSON-string enum value is neither a
            // map nor a list and is returned verbatim with no chain applied.
            if (!TypeClassifier.isScalarLeaf(elementType)) {
                return new FieldPolicyMetadata(
                        canonChain, sanitChain, skipCanon, skipSanit, rawType, false, false, elementType);
            }
            return schemaFree(canonChain, sanitChain, skipCanon, skipSanit, rawType, hasAnnotations);
        }

        // Nested object — record the declared type unconditionally so the walker can descend into
        // it and resolve its own metadata, even across links that declare no policy themselves.
        if (isDescendableObject(rawType)) {
            return new FieldPolicyMetadata(canonChain, sanitChain, skipCanon, skipSanit, rawType, false, false, null);
        }

        // Primitives / enums / Object / Map / etc. — no statically known property set.
        return schemaFree(canonChain, sanitChain, skipCanon, skipSanit, rawType, hasAnnotations);
    }

    /**
     * Builds the metadata for a field that carries no element or nested schema — the shape every
     * non-descendable branch of {@link #buildFieldMeta} produces. Such a field is only worth
     * recording when it declares policy of its own; otherwise the walker has nothing to do with it
     * and it is left out of the metadata entirely.
     *
     * @param canonChain     the declared canonicalizer chain, possibly empty
     * @param sanitChain     the declared sanitizer chain, possibly empty
     * @param skipCanon      whether {@code @SkipCanonicalization} is present
     * @param skipSanit      whether {@code @SkipSanitization} is present
     * @param rawType        the raw (erased) class of the field
     * @param hasAnnotations whether the field declares any policy of its own
     * @return the schema-free field metadata, or {@code null} when the field declares no policy
     */
    private static FieldPolicyMetadata schemaFree(
            List<Class<? extends Canonicalizer>> canonChain,
            List<Class<? extends Sanitizer>> sanitChain,
            boolean skipCanon,
            boolean skipSanit,
            Class<?> rawType,
            boolean hasAnnotations) {
        return hasAnnotations
                ? new FieldPolicyMetadata(canonChain, sanitChain, skipCanon, skipSanit, rawType, false, false, null)
                : null;
    }

    /**
     * Returns {@code true} when a field of this declared type carries a statically known property
     * set the walker can descend into and resolve metadata for.
     *
     * <p>Excluded, each because it has no such property set and must keep the walker's
     * inherited-chain-only handling: scalar leaves — primitives, boxed/scalar JDK types and enums,
     * see {@link TypeClassifier#isScalarLeaf} — plus {@link Object} and {@link Map} (arbitrary
     * keys). Arrays are excluded here too, but only defensively: {@link #buildFieldMeta} routes them
     * through the {@link Collection} branch before this test is reached, because element traversal
     * is a collection concern.
     *
     * @param type the declared field type
     * @return {@code true} if the walker should resolve {@code type}'s own metadata at descent
     */
    private static boolean isDescendableObject(Class<?> type) {
        return !TypeClassifier.isScalarLeaf(type)
                && type != Object.class
                && !type.isArray()
                && !Map.class.isAssignableFrom(type);
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
}
