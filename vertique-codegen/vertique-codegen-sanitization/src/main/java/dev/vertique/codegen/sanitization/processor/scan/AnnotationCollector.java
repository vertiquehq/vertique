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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * Mirrors {@code dev.vertique.input.processing.InputPolicyMetadataResolver} at APT time:
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
 *
 * <p>Field classification normalizes {@code java.util.Optional<T>} away before deciding the
 * {@link FieldKind}: the wire value of an {@code Optional<T>} field is the unwrapped {@code T},
 * so {@code Optional<String>} classifies as {@link FieldKind#STRING},
 * {@code Optional<NestedDto>} as {@link FieldKind#NESTED_DTO} for {@code NestedDto}, and
 * {@code List<Optional<String>>} as {@link FieldKind#COLLECTION_OF_STRINGS}. Bounded type
 * arguments are normalized to their upper bound by {@link #normalizeToBound} first, so
 * {@code Optional<? extends NestedDto>} and {@code List<? extends NestedDto>} classify against
 * {@code NestedDto}. See {@link #buildFieldModel} for the full rule, including raw
 * {@code Optional} handling.
 *
 * <p>A {@code Map}-typed field is <em>schema-free</em> ({@link FieldKind#OTHER}), never a nested
 * DTO: its keys are arbitrary, so it carries no statically known property set. The test is
 * assignability to {@code java.util.Map}, so a {@code HashMap}-typed field classifies identically.
 * This mirrors {@code InputPolicyMetadataResolver.isDescendableObject} in
 * {@code vertique-input-processing}, which excludes {@code Map} from descent — an unannotated
 * {@code Map} field therefore yields no {@link FieldModel} on either path.
 *
 * <p>Arrays classify exactly like collections — a {@code NestedDto[]} field is
 * {@link FieldKind#COLLECTION_OF_DTO} and a {@code String[]} field is
 * {@link FieldKind#COLLECTION_OF_STRINGS} — because both shapes arrive as a JSON array carrying
 * one element schema. An array whose component is itself an array ({@code String[][]}) has no
 * element schema and stays {@link FieldKind#OTHER}, matching
 * {@code TypeClassifier.elementType} in {@code vertique-input-processing}.
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
            // Primitive Optional specializations carry no string payload and have no type
            // argument to unwrap — treat them as scalar leaves so no dead nested-DTO arm is
            // emitted for them.
            "java.util.OptionalInt",
            "java.util.OptionalLong",
            "java.util.OptionalDouble",
            "java.lang.Object");

    /** FQN of the {@code java.util.Optional} wrapper that is transparent for classification. */
    private static final String OPTIONAL_FQN = "java.util.Optional";

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
     * <p><strong>Only instance properties are resolved.</strong> Static and synthetic fields are
     * skipped, mirroring {@code InputPolicyMetadataResolver.resolveFields} in
     * {@code vertique-input-processing}: neither is part of the type's wire shape, so emitting an
     * arm for one would let a crafted wire key drive a Lombok {@code @Slf4j} {@code log} field's or
     * a synthetic {@code this$0}'s type — and would make the generated path disagree with the
     * reflective one on the same DTO. Record components need no such filter; the record's component
     * list holds only its declared components.
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
                    if (isStaticOrSynthetic(enclosed)) continue;
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
     * Returns {@code true} for a field that is not an instance property of its declaring type.
     *
     * <p>The {@link Elements#getOrigin(Element) origin} test matters for a supertype read from a
     * class file rather than from source — a source {@code TypeElement} does not carry the
     * synthetic {@code this$0} in its element model, but a compiled one does.
     *
     * @param field the enclosed field element
     * @return {@code true} when the field is static, synthetic, or compiler-mandated
     */
    private boolean isStaticOrSynthetic(Element field) {
        if (field.getModifiers().contains(Modifier.STATIC)) {
            return true;
        }
        Elements.Origin origin = ctx.elements().getOrigin(field);
        return origin == Elements.Origin.SYNTHETIC || origin == Elements.Origin.MANDATED;
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
     * <p>{@code java.util.Optional<T>} is <em>transparent</em> here: the intermediate wire value
     * of an {@code Optional<T>} field is the unwrapped {@code T} value (Jackson's
     * {@code Jdk8Module} serializes the payload, not the wrapper), so the field is classified by
     * {@code T}. Without this normalization {@code Optional<String>} would classify as
     * {@link FieldKind#NESTED_DTO} and emit a {@code dispatchNested(v, Optional.class, …)} arm —
     * for which no generated processor exists, silently dropping the field's chain. Nested
     * wrappers ({@code Optional<Optional<T>>}) unwrap through the recursion; a raw
     * {@code Optional} has no type argument to classify against and falls back to
     * {@link FieldKind#OTHER} (or {@code null} when unannotated).
     *
     * <p>The unwrapped type argument is passed through {@link #normalizeToBound} before the
     * recursive classification, so a bounded generic ({@code Optional<? extends Child>},
     * {@code Optional<T extends Child>}) resolves to {@code Child} — the type Jackson
     * materializes. {@code Optional<?>} and {@code Optional<? super Child>} normalize to
     * {@code java.lang.Object} and therefore stay {@link FieldKind#OTHER}.
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

        // Optional<T> wrapper — classify by the wrapped type (see method javadoc). The type
        // argument is normalized to its upper bound first so bounded generics
        // (Optional<? extends Child>, Optional<T extends Child>) classify against Child, the
        // type Jackson actually materializes.
        if (isOptionalWrapper(type)) {
            TypeMirror wrapped = normalizeToBound(optionalTypeArgument(type));
            if (wrapped == null) {
                // Raw Optional (or a malformed type argument list) — nothing to classify against.
                return hasAnnotations
                        ? new FieldModel(
                                name, FieldKind.OTHER, canonChain, sanitChain, skipCanon, skipSanit, null, type)
                        : null;
            }
            return buildFieldModel(name, wrapped, canonChain, sanitChain, skipCanon, skipSanit, location);
        }

        // String field
        if (isString(type)) {
            return new FieldModel(name, FieldKind.STRING, canonChain, sanitChain, skipCanon, skipSanit, null, type);
        }

        // Collection and array types — both arrive as a JSON array on the wire and carry exactly
        // one element schema, so they classify through one branch. This mirrors
        // InputPolicyMetadataResolver.buildFieldMeta, which routes
        // `Collection.class.isAssignableFrom(rawType) || rawType.isArray()` the same way; without
        // it a `NestedDto[]` field would emit an applyDefault arm and only ever apply inherited
        // chains, while the reflective walker descended into each element.
        boolean isArray = type.getKind() == TypeKind.ARRAY;
        if (isArray || isCollection(type)) {
            TypeMirror elementType =
                    isArray ? normalizeElementType(arrayElementType(type)) : extractCollectionElementType(type);
            if (elementType == null) {
                // Raw collection, or an array whose component carries no element schema —
                // cannot determine element type
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
            // Collection or array of scalars, with annotations
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

        // Map fields are schema-free — never nested DTOs. A Map's keys are arbitrary, so it carries
        // no statically known property set to generate a switch over. This mirrors
        // InputPolicyMetadataResolver.isDescendableObject in vertique-input-processing, which
        // excludes Map from descent and therefore drops an unannotated Map field entirely; without
        // this branch the DECLARED fallthrough below would classify the field as NESTED_DTO and
        // emit a `dispatchNested(v, Map.class, …)` arm, so the generated path would report
        // Map (or a Map subtype) as the InputValueContext.ownerType for keys inside the field
        // while the reflective path reused the enclosing DTO. The test is assignability, not an
        // FQN match, so a HashMap-typed field classifies identically — exactly the reflective
        // resolver's `Map.class.isAssignableFrom(rawType)`. Placed after the collection/array
        // branch, which owns element-wise handling for anything that is also a Collection.
        if (isMap(type)) {
            return hasAnnotations
                    ? new FieldModel(
                            name,
                            FieldKind.OTHER,
                            canonChain,
                            sanitChain,
                            skipCanon,
                            skipSanit,
                            null,
                            ctx.types().erasure(type))
                    : null;
        }

        // Nested object (non-scalar, non-collection, non-array, non-map — arrays returned above)
        if (type.getKind() == TypeKind.DECLARED && !isScalarOrEnum(type)) {
            TypeMirror erasedType = ctx.types().erasure(type);
            return new FieldModel(
                    name, FieldKind.NESTED_DTO, canonChain, sanitChain, skipCanon, skipSanit, erasedType, erasedType);
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
        return isAssignableTo(type, "java.util.Collection");
    }

    /**
     * Returns {@code true} if the type mirror is assignable to {@link java.util.Map}.
     *
     * <p>Assignability rather than an FQN comparison, so a {@code HashMap}-typed field classifies
     * like a {@code Map}-typed one — the same test
     * ({@code Map.class.isAssignableFrom(rawType)}) the reflective
     * {@code InputPolicyMetadataResolver} applies.
     *
     * @param type the type mirror to test
     * @return {@code true} for map types
     */
    private boolean isMap(TypeMirror type) {
        return isAssignableTo(type, "java.util.Map");
    }

    /**
     * Returns {@code true} if the type mirror is assignable to the named supertype, comparing
     * erasures so a parameterized {@code List<Foo>} matches {@code java.util.Collection}.
     *
     * @param type       the type mirror to test
     * @param supertypeFqn the fully-qualified name of the supertype to test against
     * @return {@code true} when {@code type} is a declared type assignable to {@code supertypeFqn}
     */
    private boolean isAssignableTo(TypeMirror type, String supertypeFqn) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeElement supertype = ctx.elements().getTypeElement(supertypeFqn);
        if (supertype == null) return false;
        return ctx.types().isAssignable(ctx.types().erasure(type), ctx.types().erasure(supertype.asType()));
    }

    /**
     * Extracts the element type from a parameterized {@link Collection} type mirror, unwrapping
     * any {@code java.util.Optional} layers so {@code List<Optional<String>>} yields
     * {@code String} (and therefore classifies as {@link FieldKind#COLLECTION_OF_STRINGS}).
     *
     * <p>Wildcard and type-variable element types are normalized to their upper bound first (see
     * {@link #normalizeToBound}), so {@code List<? extends Child>} and
     * {@code List<Optional<? extends Child>>} both yield {@code Child} — the element type Jackson
     * materializes. An unbounded {@code ?} or a lower-bounded {@code ? super X} normalizes to
     * {@code java.lang.Object}, which is a scalar leaf and therefore routes the field to the
     * scalar-collection fallthrough.
     *
     * <p>Returns {@code null} — meaning "element type not determinable", which routes the field
     * to the raw-collection {@link FieldKind#OTHER}/{@code null} fallthrough — for raw
     * collections, element types that do not normalize to a declared type (e.g. nested arrays or
     * primitives), and raw {@code Optional} elements.
     *
     * @param type the collection type mirror
     * @return the element type mirror, or {@code null} if not determinable
     */
    private TypeMirror extractCollectionElementType(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) return null;
        if (dt.getTypeArguments().isEmpty()) return null;
        return normalizeElementType(dt.getTypeArguments().get(0));
    }

    /**
     * Normalizes a candidate element type — a collection's type argument or an array's component
     * type — to the declared type Jackson materializes for it, or {@code null} when no element
     * schema is determinable.
     *
     * <p>Wildcards and type variables resolve through {@link #normalizeToBound} and
     * {@code Optional} layers are unwrapped, so {@code ? extends Child},
     * {@code Optional<? extends Child>} and {@code Optional<Child>} all yield {@code Child}.
     *
     * <p>Returns {@code null} for anything that does not reduce to a declared type — a primitive
     * component ({@code int[]}), a nested array component ({@code String[][]}, {@code List<String[]>})
     * and a raw {@code Optional} — and for an element that is itself a container: a
     * {@link Collection} ({@code List<List<String>>}, {@code Set<List<Tag>>}) or a {@link java.util.Map}
     * ({@code List<Map<String, String>>}). A container element's wire value is another JSON array
     * or object rather than a dispatchable nested DTO, and a {@code Map}'s keys are arbitrary, so
     * neither carries a statically known property set. This is exactly
     * {@code TypeClassifier.elementType}'s rule in {@code vertique-input-processing}, whose null
     * result likewise keeps the field on the inherited-chain path instead of descending
     * element-wise — the two paths must classify these shapes identically or their output diverges.
     *
     * @param candidate the element type argument or array component type; may be {@code null}
     * @return the normalized declared element type, or {@code null} if not determinable
     */
    private TypeMirror normalizeElementType(TypeMirror candidate) {
        TypeMirror arg = normalizeToBound(candidate);
        if (arg == null || arg.getKind() != TypeKind.DECLARED) return null;
        while (isOptionalWrapper(arg)) {
            TypeMirror wrapped = normalizeToBound(optionalTypeArgument(arg));
            if (wrapped == null || wrapped.getKind() != TypeKind.DECLARED) return null;
            arg = wrapped;
        }
        if (isCollection(arg) || isMap(arg)) return null;
        return arg;
    }

    /**
     * Normalizes a type argument to the type Jackson actually materializes for it.
     *
     * <ul>
     *   <li>{@link TypeKind#WILDCARD} — resolves to the {@code extends} bound. An unbounded
     *       {@code ?} and a lower-bounded {@code ? super X} have no upper bound beyond
     *       {@code java.lang.Object}, so they normalize to {@code java.lang.Object} (a scalar
     *       leaf, i.e. {@link FieldKind#OTHER}).</li>
     *   <li>{@link TypeKind#TYPEVAR} — resolves to the type variable's upper bound. An
     *       intersection bound ({@code T extends A & B}) is erased via
     *       {@link javax.lang.model.util.Types#erasure}, which yields the leftmost bound —
     *       the same type javac writes into the erased field signature and therefore the same
     *       type Jackson binds against.</li>
     *   <li>Anything else is returned unchanged.</li>
     * </ul>
     *
     * <p>Bounds may themselves be wildcards or type variables ({@code ? extends T},
     * {@code T extends U}), so resolution recurses. Java forbids circular type-variable bounds,
     * so the recursion terminates.
     *
     * @param t the type mirror to normalize; may be {@code null}
     * @return the normalized type mirror, or {@code null} when {@code t} is {@code null}
     */
    private TypeMirror normalizeToBound(TypeMirror t) {
        if (t == null) {
            return null;
        }
        if (t instanceof WildcardType wildcard) {
            TypeMirror extendsBound = wildcard.getExtendsBound();
            return extendsBound == null ? objectType(t) : normalizeToBound(extendsBound);
        }
        if (t instanceof TypeVariable typeVar) {
            TypeMirror upperBound = typeVar.getUpperBound();
            if (upperBound == null) {
                return objectType(t);
            }
            if (upperBound.getKind() == TypeKind.INTERSECTION) {
                return ctx.types().erasure(upperBound);
            }
            return normalizeToBound(upperBound);
        }
        return t;
    }

    /**
     * Returns the {@code java.lang.Object} type mirror, used as the upper bound of an unbounded
     * or lower-bounded wildcard.
     *
     * @param fallback returned when {@code java.lang.Object} cannot be resolved from the
     *                 processing environment (never expected in practice)
     * @return the {@code java.lang.Object} mirror, or {@code fallback}
     */
    private TypeMirror objectType(TypeMirror fallback) {
        TypeElement objectElement = ctx.elements().getTypeElement("java.lang.Object");
        return objectElement != null ? objectElement.asType() : fallback;
    }

    /**
     * Returns {@code true} if the type mirror is a (possibly parameterized) {@code java.util.Optional}.
     *
     * @param type the type mirror to test
     * @return {@code true} for {@code java.util.Optional}
     */
    private static boolean isOptionalWrapper(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && OPTIONAL_FQN.equals(typeFqn(type));
    }

    /**
     * Returns the single type argument of an {@code Optional<T>} type mirror.
     *
     * @param type the {@code Optional} type mirror
     * @return the {@code T} mirror, or {@code null} for a raw {@code Optional}
     */
    private static TypeMirror optionalTypeArgument(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) return null;
        List<? extends TypeMirror> args = dt.getTypeArguments();
        return args.size() == 1 ? args.get(0) : null;
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
     * Extracts the raw component type from an array type mirror. Applies no normalization —
     * {@link #buildFieldModel} passes the result through {@link #normalizeElementType}, which is
     * what rejects a nested-array or primitive component.
     *
     * @param type the array type mirror; the caller must have established that its kind is
     *     {@link TypeKind#ARRAY}
     * @return the component type mirror
     */
    static TypeMirror arrayElementType(TypeMirror type) {
        return ((ArrayType) type).getComponentType();
    }
}
