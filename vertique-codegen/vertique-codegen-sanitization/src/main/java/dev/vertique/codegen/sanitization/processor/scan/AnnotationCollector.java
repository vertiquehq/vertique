// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.sanitization.processor.scan.FieldModel.FieldKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Mirrors {@link dev.vertique.rest.core.request.InputPolicyMetadataResolver} at APT time:
 * collects per-type and per-field annotation chains from {@link TypeElement} mirrors instead of
 * reflection.
 *
 * <p>Produces {@link DtoModel} records capturing object-level chains and per-field
 * {@link FieldModel} entries. Annotation conflicts ({@code @Canonicalize} +
 * {@code @SkipCanonicalization} on the same element, or {@code @Sanitize} + {@code @SkipSanitization})
 * are surfaced as {@link Diagnostic.Kind#ERROR} so they fail compilation early — mirroring the
 * {@link IllegalStateException} the runtime resolver throws.
 *
 * <p>Meta-annotation support: all annotation mirrors on each element are walked; if any annotation
 * is itself meta-annotated with {@code @Canonicalize}, {@code @Sanitize}, {@code @SkipCanonicalization},
 * or {@code @SkipSanitization}, it is treated as the effective source annotation.
 */
public final class AnnotationCollector {

    // --- Annotation FQNs ---

    static final String CANONICALIZE_FQN = "dev.vertique.core.sanitization.Canonicalize";
    static final String SANITIZE_FQN = "dev.vertique.core.sanitization.Sanitize";
    static final String SKIP_CANON_FQN = "dev.vertique.core.sanitization.SkipCanonicalization";
    static final String SKIP_SANIT_FQN = "dev.vertique.core.sanitization.SkipSanitization";

    // --- Well-known scalar FQNs for field classification ---

    private static final List<String> SCALAR_FQNS = List.of(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character",
            "java.lang.Number",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.Instant",
            "java.util.UUID",
            "java.lang.Object");

    private final CodegenContext ctx;

    /**
     * Constructs an {@code AnnotationCollector} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public AnnotationCollector(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Collects annotation metadata for the given DTO type and returns a fully-populated
     * {@link DtoModel}.
     *
     * @param origin the type element representing the DTO; must not be {@code null}
     * @return the collected model; never {@code null}
     */
    public DtoModel collect(TypeElement origin) {
        String qualifiedName = origin.getQualifiedName().toString();

        // --- Type-level annotations ---
        AnnotationMirror typeCanon = findMetaAnnotation(origin, CANONICALIZE_FQN);
        AnnotationMirror typeSanit = findMetaAnnotation(origin, SANITIZE_FQN);
        boolean typeSkipCanon = findMetaAnnotation(origin, SKIP_CANON_FQN) != null;
        boolean typeSkipSanit = findMetaAnnotation(origin, SKIP_SANIT_FQN) != null;

        checkConflicts(origin, qualifiedName, typeCanon, typeSkipCanon, typeSanit, typeSkipSanit);

        List<TypeMirror> objCanonChain = typeCanon != null ? readClassArrayValue(typeCanon) : List.of();
        List<TypeMirror> objSanitChain = typeSanit != null ? readClassArrayValue(typeSanit) : List.of();

        // --- Field/component-level metadata ---
        LinkedHashMap<String, FieldModel> fields = resolveFields(origin);

        return new DtoModel(origin, qualifiedName, objCanonChain, objSanitChain, typeSkipCanon, typeSkipSanit, fields);
    }

    // --- Field / record-component traversal ---

    /**
     * Resolves field models for both records (record components) and regular classes (declared
     * fields walked up the supertype hierarchy).
     *
     * @param type the type to inspect
     * @return ordered map of field name to {@link FieldModel}
     */
    private LinkedHashMap<String, FieldModel> resolveFields(TypeElement type) {
        LinkedHashMap<String, FieldModel> result = new LinkedHashMap<>();
        if (ctx.isRecord(type)) {
            for (RecordComponentElement component : type.getRecordComponents()) {
                FieldModel model = resolveRecordComponent(component);
                if (model != null) {
                    result.put(component.getSimpleName().toString(), model);
                }
            }
        } else {
            // Walk declared fields up the supertype hierarchy (subclass fields first).
            for (TypeMirror superMirror : collectSuperHierarchy(type)) {
                if (!(superMirror instanceof DeclaredType dt)) continue;
                if (!(dt.asElement() instanceof TypeElement te)) continue;
                String fqn = te.getQualifiedName().toString();
                if ("java.lang.Object".equals(fqn)) continue;
                for (Element enclosed : te.getEnclosedElements()) {
                    if (enclosed.getKind() != ElementKind.FIELD) continue;
                    VariableElement field = (VariableElement) enclosed;
                    String fieldName = field.getSimpleName().toString();
                    if (result.containsKey(fieldName)) continue; // subclass overrides
                    FieldModel model = resolveField(field);
                    if (model != null) {
                        result.put(fieldName, model);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Collects the full supertype hierarchy of the given type starting from the type itself,
     * walking through superclasses (excluding {@code Object}).
     *
     * @param type the type to walk
     * @return list of type mirrors from the type down to (but not including) {@code Object}
     */
    private List<TypeMirror> collectSuperHierarchy(TypeElement type) {
        List<TypeMirror> hierarchy = new ArrayList<>();
        TypeMirror current = type.asType();
        while (current != null && current.getKind() == TypeKind.DECLARED) {
            DeclaredType dt = (DeclaredType) current;
            TypeElement te = (TypeElement) dt.asElement();
            String fqn = te.getQualifiedName().toString();
            if ("java.lang.Object".equals(fqn)) break;
            hierarchy.add(current);
            TypeMirror superClass = te.getSuperclass();
            current = (superClass != null && superClass.getKind() == TypeKind.DECLARED) ? superClass : null;
        }
        return hierarchy;
    }

    /**
     * Resolves a {@link FieldModel} for a record component. Returns {@code null} for components
     * that need no processing (e.g., non-string primitives with no annotations).
     *
     * @param component the record component
     * @return the field model, or {@code null}
     */
    private FieldModel resolveRecordComponent(RecordComponentElement component) {
        String name = component.getSimpleName().toString();
        String location = component.getEnclosingElement().toString() + "#" + name;

        AnnotationMirror canon = findMetaAnnotationOnRecordComponent(component, CANONICALIZE_FQN);
        AnnotationMirror sanit = findMetaAnnotationOnRecordComponent(component, SANITIZE_FQN);
        boolean skipCanon = findMetaAnnotationOnRecordComponent(component, SKIP_CANON_FQN) != null;
        boolean skipSanit = findMetaAnnotationOnRecordComponent(component, SKIP_SANIT_FQN) != null;

        checkConflicts(component, location, canon, skipCanon, sanit, skipSanit);

        List<TypeMirror> canonChain = canon != null ? readClassArrayValue(canon) : List.of();
        List<TypeMirror> sanitChain = sanit != null ? readClassArrayValue(sanit) : List.of();

        return buildFieldModel(name, component.asType(), canonChain, sanitChain, skipCanon, skipSanit, location);
    }

    /**
     * Resolves a {@link FieldModel} for a regular class field. Returns {@code null} for fields
     * that need no processing.
     *
     * @param field the field variable element
     * @return the field model, or {@code null}
     */
    private FieldModel resolveField(VariableElement field) {
        String name = field.getSimpleName().toString();
        String location = field.getEnclosingElement().toString() + "#" + name;

        AnnotationMirror canon = findMetaAnnotation(field, CANONICALIZE_FQN);
        AnnotationMirror sanit = findMetaAnnotation(field, SANITIZE_FQN);
        boolean skipCanon = findMetaAnnotation(field, SKIP_CANON_FQN) != null;
        boolean skipSanit = findMetaAnnotation(field, SKIP_SANIT_FQN) != null;

        checkConflicts(field, location, canon, skipCanon, sanit, skipSanit);

        List<TypeMirror> canonChain = canon != null ? readClassArrayValue(canon) : List.of();
        List<TypeMirror> sanitChain = sanit != null ? readClassArrayValue(sanit) : List.of();

        return buildFieldModel(name, field.asType(), canonChain, sanitChain, skipCanon, skipSanit, location);
    }

    /**
     * Builds a {@link FieldModel} for the given field type and annotation state. Returns
     * {@code null} for types that need no processing.
     *
     * @param name       the field name
     * @param type       the field type mirror
     * @param canonChain field-level canonicalizer chain
     * @param sanitChain field-level sanitizer chain
     * @param skipCanon  whether {@code @SkipCanonicalization} is present
     * @param skipSanit  whether {@code @SkipSanitization} is present
     * @param location   human-readable location string for diagnostics
     * @return the field model, or {@code null} if the field needs no processing
     */
    private FieldModel buildFieldModel(
            String name,
            TypeMirror type,
            List<TypeMirror> canonChain,
            List<TypeMirror> sanitChain,
            boolean skipCanon,
            boolean skipSanit,
            String location) {

        boolean hasAnnotations = !canonChain.isEmpty() || !sanitChain.isEmpty() || skipCanon || skipSanit;

        // String field
        if (isString(type)) {
            return new FieldModel(name, FieldKind.STRING, canonChain, sanitChain, skipCanon, skipSanit, null, type);
        }

        // Collection types
        if (isCollection(type)) {
            TypeMirror elementType = extractCollectionElementType(type);
            if (elementType == null) {
                // Raw collection — cannot determine element type
                return hasAnnotations
                        ? new FieldModel(
                                name, FieldKind.OTHER, canonChain, sanitChain, skipCanon, skipSanit, null, type)
                        : null;
            }
            if (isString(elementType)) {
                return new FieldModel(
                        name,
                        FieldKind.COLLECTION_OF_STRINGS,
                        canonChain,
                        sanitChain,
                        skipCanon,
                        skipSanit,
                        null,
                        type);
            }
            if (!isScalarOrEnum(elementType)) {
                return new FieldModel(
                        name,
                        FieldKind.COLLECTION_OF_DTO,
                        canonChain,
                        sanitChain,
                        skipCanon,
                        skipSanit,
                        ctx.types().erasure(elementType),
                        type);
            }
            // Collection of scalars with annotations
            return hasAnnotations
                    ? new FieldModel(
                            name,
                            FieldKind.OTHER,
                            canonChain,
                            sanitChain,
                            skipCanon,
                            skipSanit,
                            elementType,
                            ctx.types().erasure(type))
                    : null;
        }

        // Nested object (non-scalar, non-collection, non-array)
        if (type.getKind() == TypeKind.DECLARED && !isScalarOrEnum(type)) {
            TypeMirror erasedType = ctx.types().erasure(type);
            return new FieldModel(
                    name, FieldKind.NESTED_DTO, canonChain, sanitChain, skipCanon, skipSanit, erasedType, erasedType);
        }

        // Arrays — treated as OTHER (not in scope per the plan)
        if (type.getKind() == TypeKind.ARRAY) {
            return hasAnnotations
                    ? new FieldModel(name, FieldKind.OTHER, canonChain, sanitChain, skipCanon, skipSanit, null, type)
                    : null;
        }

        // Primitives / enums / other scalars
        return hasAnnotations
                ? new FieldModel(name, FieldKind.OTHER, canonChain, sanitChain, skipCanon, skipSanit, null, type)
                : null;
    }

    // --- Annotation lookup ---

    /**
     * Finds the first annotation on the element (or meta-annotated on any of the element's
     * annotations) that has the given FQN.
     *
     * @param element       the element to search
     * @param annotationFqn the FQN of the annotation to find
     * @return the annotation mirror, or {@code null} if not found
     */
    static AnnotationMirror findMetaAnnotation(Element element, String annotationFqn) {
        Optional<AnnotationMirror> direct = AnnotationMirrors.findByFqn(element, annotationFqn);
        if (direct.isPresent()) {
            return direct.get();
        }
        // Recursively walk meta-annotations to support multi-hop composition: @A → @B → @Canonicalize.
        // Mirrors AnnotationResolver.findMetaAnnotation in vertique-core, which the reflective
        // walker uses; without recursion, a DTO declared as @MyAlias String name (where @MyAlias
        // is meta-annotated with @MyBase, which is meta-annotated with @Canonicalize) would
        // pass the participation check yet have no chain emitted, diverging silently from the
        // reflective path.
        return findMetaRecursive(element, annotationFqn, new java.util.HashSet<>());
    }

    private static AnnotationMirror findMetaRecursive(
            Element element, String annotationFqn, java.util.Set<String> visited) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotationType = mirror.getAnnotationType().asElement();
            String thisFqn = annotationType instanceof javax.lang.model.element.TypeElement te
                    ? te.getQualifiedName().toString()
                    : annotationType.getSimpleName().toString();
            // Direct match on this annotation type at any depth.
            if (annotationFqn.equals(thisFqn)) {
                return mirror;
            }
            // Direct match on a meta-annotation declared on this annotation type.
            Optional<AnnotationMirror> meta = AnnotationMirrors.findByFqn(annotationType, annotationFqn);
            if (meta.isPresent()) {
                return meta.get();
            }
            // Recurse into the annotation type's own annotations, guarding against cycles
            // (annotations may be self-meta-annotated, e.g. @Documented).
            if (visited.add(thisFqn)) {
                AnnotationMirror deeper = findMetaRecursive(annotationType, annotationFqn, visited);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /**
     * Finds the annotation on a record component, checking the component element itself and its
     * accessor method, plus meta-annotations on both.
     *
     * @param component     the record component
     * @param annotationFqn the FQN of the annotation to find
     * @return the annotation mirror, or {@code null} if not found
     */
    private AnnotationMirror findMetaAnnotationOnRecordComponent(
            RecordComponentElement component, String annotationFqn) {
        AnnotationMirror onComponent = findMetaAnnotation(component, annotationFqn);
        if (onComponent != null) return onComponent;
        // Check the accessor method
        if (component.getAccessor() != null) {
            return findMetaAnnotation(component.getAccessor(), annotationFqn);
        }
        return null;
    }

    /**
     * Reads the {@code value()} array attribute from a {@code @Canonicalize} or {@code @Sanitize}
     * annotation mirror. The attribute holds {@code Class<?>} values, which at APT time are
     * represented as {@link TypeMirror} inside each {@link AnnotationValue}.
     *
     * @param mirror the annotation mirror
     * @return ordered list of type mirrors; never {@code null}
     */
    @SuppressWarnings("unchecked")
    private List<TypeMirror> readClassArrayValue(AnnotationMirror mirror) {
        List<TypeMirror> result = new ArrayList<>();
        for (AnnotationValue av : ctx.annotations().attributeArray(mirror, "value")) {
            Object rawVal = av.getValue();
            if (rawVal instanceof TypeMirror tm) {
                result.add(tm);
            }
        }
        return result;
    }

    /**
     * Emits a compile-time ERROR if both the additive and the skip annotation are present on
     * the same element.
     *
     * @param element   the element for diagnostic attachment
     * @param location  human-readable location string for the message
     * @param canon     {@code @Canonicalize} mirror, or {@code null}
     * @param skipCanon whether {@code @SkipCanonicalization} is present
     * @param sanit     {@code @Sanitize} mirror, or {@code null}
     * @param skipSanit whether {@code @SkipSanitization} is present
     */
    private void checkConflicts(
            Element element,
            String location,
            AnnotationMirror canon,
            boolean skipCanon,
            AnnotationMirror sanit,
            boolean skipSanit) {

        if (canon != null && skipCanon) {
            ctx.diagnostics()
                    .error(
                            element,
                            "%s has both @Canonicalize and @SkipCanonicalization — "
                                    + "these annotations are mutually exclusive",
                            location);
        }
        if (sanit != null && skipSanit) {
            ctx.diagnostics()
                    .error(
                            element,
                            "%s has both @Sanitize and @SkipSanitization — "
                                    + "these annotations are mutually exclusive",
                            location);
        }
    }

    // --- Type classification helpers ---

    /**
     * Returns {@code true} if the type mirror represents {@code java.lang.String}.
     *
     * @param type the type mirror to test
     * @return {@code true} for {@code String}
     */
    static boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && typeFqn(type).equals("java.lang.String");
    }

    /**
     * Returns {@code true} if the type mirror is assignable to {@link Collection}.
     *
     * @param type the type mirror to test
     * @return {@code true} for collection types
     */
    private boolean isCollection(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeElement collectionElement = ctx.elements().getTypeElement("java.util.Collection");
        if (collectionElement == null) return false;
        return ctx.types().isAssignable(ctx.types().erasure(type), ctx.types().erasure(collectionElement.asType()));
    }

    /**
     * Extracts the element type from a parameterized {@link Collection} type mirror.
     *
     * @param type the collection type mirror
     * @return the element type mirror, or {@code null} if not determinable
     */
    private TypeMirror extractCollectionElementType(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) return null;
        if (dt.getTypeArguments().isEmpty()) return null;
        TypeMirror arg = dt.getTypeArguments().get(0);
        return (arg.getKind() == TypeKind.DECLARED) ? arg : null;
    }

    /**
     * Returns {@code true} for scalars (primitives, boxed types, common immutable value types) and
     * enum types that are not worth traversing.
     *
     * @param type the type mirror to test
     * @return {@code true} if the type should be treated as a scalar leaf
     */
    static boolean isScalarOrEnum(TypeMirror type) {
        if (type.getKind().isPrimitive()) return true;
        if (type.getKind() == TypeKind.DECLARED) {
            String fqn = typeFqn(type);
            if (SCALAR_FQNS.contains(fqn)) return true;
            // Check if it's an enum
            DeclaredType dt = (DeclaredType) type;
            if (dt.asElement() instanceof TypeElement te) {
                return te.getKind() == ElementKind.ENUM;
            }
        }
        return false;
    }

    /**
     * Returns the fully-qualified name of a declared type mirror.
     *
     * @param type the declared type mirror
     * @return the FQN string, or an empty string if not determinable
     */
    static String typeFqn(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) return "";
        if (!(dt.asElement() instanceof TypeElement te)) return "";
        return te.getQualifiedName().toString();
    }

    /**
     * Extracts the element type from an array type mirror.
     *
     * @param type the array type mirror
     * @return the component type mirror, or {@code null}
     */
    static TypeMirror arrayElementType(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) type).getComponentType();
        }
        return null;
    }
}
