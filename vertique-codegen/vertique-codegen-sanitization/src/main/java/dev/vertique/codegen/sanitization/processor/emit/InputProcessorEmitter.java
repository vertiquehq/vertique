// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.sanitization.processor.scan.DtoModel;
import dev.vertique.codegen.sanitization.processor.scan.FieldModel;
import dev.vertique.codegen.sanitization.processor.scan.FieldModel.FieldKind;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * JavaPoet emitter for {@code {DTO}_InputProcessor} classes generated from {@link DtoModel}
 * instances.
 *
 * <p>Each emitted class:
 * <ul>
 *   <li>Is {@code public final} and implements
 *       {@code GeneratedInputProcessor<T>}.</li>
 *   <li>Carries {@code @Generated("dev.vertique.codegen.sanitization.processor.SanitizationProcessor")}.</li>
 *   <li>Has a public no-arg constructor for classloader-based instantiation by
 *       {@code GeneratedInputProcessorDispatcher}.</li>
 *   <li>Declares {@code static final} chain constants for each object-level and field-level
 *       chain, using {@code SCREAMING_SNAKE_CASE} names.</li>
 *   <li>Overrides {@code fieldNameOwnerTypes()} with a deduplicated {@code static final
 *       Set<Class<?>>} holding every owner its {@code process(...)} arms may consult — always its
 *       own target type, plus each dispatched nested/element type and each annotated schema-free
 *       field's erased declared type. See {@link #collectOwnerTypeNames} for the derivation and
 *       {@link #buildOwnerTypesInitializer} for why the emitted body accumulates rather than
 *       using a {@code Set.of(...)} varargs literal.</li>
 *   <li>Implements {@code process(...)} with a {@code switch} whose arms are the DTO's
 *       <em>Java</em> property names and whose selector is the traversal's projection of the wire
 *       key ({@code rootCtx.logicalFieldName(Dto.class, k)}), calling
 *       {@code GeneratedSupport.applyString}, {@code applyStringCollection}, or
 *       {@code dispatchObjectCollection} depending on field kind. The emitted map keeps the wire
 *       key, so the projection selects policies without renaming what the codec binds.</li>
 * </ul>
 *
 * <p>The emitted code shape matches the hand-written companions in
 * {@code vertique-input-processing}'s test tree ({@code dev.vertique.input.processing}). The
 * reference for the projected-switch shape above is
 * {@code GeneratedVsReflectiveEquivalenceTest_ProjectionGenerated_InputProcessor}, and every other
 * companion now mirrors it. Keep them in step when this emitter changes: a companion that switches
 * on the raw key stays green under {@code InputFieldNameResolver.IDENTITY} while diverging from
 * what actually ships, which is how an emitter gap once survived a whole slice unnoticed.
 */
public final class InputProcessorEmitter {

    // --- Well-known type names ---

    private static final ClassName GENERATED_INPUT_PROCESSOR =
            ClassName.get("dev.vertique.input.processing", "GeneratedInputProcessor");
    private static final ClassName EFFECTIVE_INPUT_POLICIES =
            ClassName.get("dev.vertique.input.processing", "EffectiveInputPolicies");
    private static final ClassName INPUT_LOCATION = ClassName.get("dev.vertique.core.sanitization", "InputLocation");
    private static final ClassName CHAIN_RESOLVER = ClassName.get("dev.vertique.input.processing", "ChainResolver");
    private static final ClassName DISPATCHER =
            ClassName.get("dev.vertique.input.processing", "GeneratedInputProcessorDispatcher");
    private static final ClassName INPUT_TRAVERSAL_CONTEXT =
            ClassName.get("dev.vertique.input.processing", "InputTraversalContext");
    private static final ClassName INPUT_FIELD_NAME_RESOLVER =
            ClassName.get("dev.vertique.core.sanitization", "InputFieldNameResolver");
    private static final ClassName CANONICALIZER = ClassName.get("dev.vertique.core.sanitization", "Canonicalizer");
    private static final ClassName SANITIZER = ClassName.get("dev.vertique.core.sanitization", "Sanitizer");
    private static final ClassName NULLABLE = ClassName.get("jakarta.annotation", "Nullable");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LINKED_HASH_SET = ClassName.get("java.util", "LinkedHashSet");

    /** Name of the emitted constant holding the processor's field-name owner types. */
    private static final String OWNER_TYPES_FIELD = "FIELD_NAME_OWNER_TYPES";

    // GeneratedSupport static import target
    private static final ClassName GENERATED_SUPPORT =
            ClassName.get("dev.vertique.input.processing", "GeneratedSupport");
    private static final String APPLY_STRING = "applyString";
    private static final String APPLY_STRING_COLLECTION = "applyStringCollection";
    private static final String DISPATCH_OBJECT_COLLECTION = "dispatchObjectCollection";

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.sanitization.processor.SanitizationProcessor";

    private final CodegenContext ctx;

    /**
     * Constructs an {@code InputProcessorEmitter} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public InputProcessorEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the {@code {DTO}_InputProcessor} source file for the given DTO model.
     *
     * <p>The generated class is placed in the same package as the source DTO.
     *
     * @param model the DTO model to emit; must not be {@code null}
     */
    public void emit(DtoModel model) {
        TypeElement origin = model.origin();
        String pkg = ctx.packageNameOf(origin);
        String generatedSimpleName = Identifiers.generatedClassName(origin, "_InputProcessor");

        ClassName originClass = buildOriginClassName(pkg, origin);
        ParameterizedTypeName processorInterface = ParameterizedTypeName.get(GENERATED_INPUT_PROCESSOR, originClass);

        // --- Static chain constant fields ---
        List<FieldSpec> staticFields = buildStaticFields(model);

        // --- Public no-arg constructor ---
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addJavadoc(
                        "Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher.")
                .addModifiers(Modifier.PUBLIC)
                .build();

        // --- targetType() method ---
        MethodSpec targetTypeMethod = MethodSpec.methodBuilder("targetType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), originClass))
                .addStatement("return $T.class", originClass)
                .build();

        // --- fieldNameOwnerTypes() constant, initializer and method ---
        ParameterizedTypeName setOfClass = ParameterizedTypeName.get(
                SET, ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)));
        FieldSpec ownerTypesField = FieldSpec.builder(
                        setOfClass, OWNER_TYPES_FIELD, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .build();
        CodeBlock ownerTypesInitializer = buildOwnerTypesInitializer(model, originClass);
        MethodSpec fieldNameOwnerTypesMethod = MethodSpec.methodBuilder("fieldNameOwnerTypes")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(setOfClass)
                .addStatement("return $L", OWNER_TYPES_FIELD)
                .build();

        // --- process() method ---
        MethodSpec processMethod = buildProcessMethod(model, originClass);

        // --- Assemble the class ---
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addSuperinterface(processorInterface);

        for (FieldSpec sf : staticFields) {
            classBuilder.addField(sf);
        }
        classBuilder.addField(ownerTypesField);
        classBuilder.addStaticBlock(ownerTypesInitializer);
        classBuilder.addMethod(constructor);
        classBuilder.addMethod(targetTypeMethod);
        classBuilder.addMethod(fieldNameOwnerTypesMethod);
        classBuilder.addMethod(processMethod);

        TypeSpec typeSpec = classBuilder.build();

        JavaFile javaFile = JavaFile.builder(pkg, typeSpec)
                .addStaticImport(GENERATED_SUPPORT, APPLY_STRING)
                .addStaticImport(GENERATED_SUPPORT, APPLY_STRING_COLLECTION)
                .addStaticImport(GENERATED_SUPPORT, DISPATCH_OBJECT_COLLECTION)
                .build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(origin, "Failed to write %s: %s", generatedSimpleName, e.getMessage());
        }
    }

    // --- Origin class name building ---

    /**
     * Builds a {@link ClassName} that correctly represents a potentially-nested type element.
     *
     * <p>For top-level types, this is equivalent to {@code ClassName.get(pkg, simpleName)}.
     * For nested types (e.g., {@code Outer.Inner}), JavaPoet requires the enclosing class names
     * to be passed as separate simple-name arguments so that it renders {@code Outer.Inner.class}
     * rather than the invalid bare {@code Inner.class}.
     *
     * @param pkg    the package name of the type
     * @param origin the type element (may be nested)
     * @return the {@link ClassName} that correctly references the type in generated code
     */
    private static ClassName buildOriginClassName(String pkg, TypeElement origin) {
        // Collect simple names from innermost to outermost
        Deque<String> names = new ArrayDeque<>();
        names.addFirst(origin.getSimpleName().toString());
        Element enclosing = origin.getEnclosingElement();
        while (enclosing instanceof TypeElement enclosingType) {
            names.addFirst(enclosingType.getSimpleName().toString());
            enclosing = enclosingType.getEnclosingElement();
        }
        // ClassName.get(pkg, outermost, ...remaining) resolves nested class references correctly
        String first = names.removeFirst();
        String[] rest = names.toArray(new String[0]);
        return ClassName.get(pkg, first, rest);
    }

    // --- Static field building ---

    /**
     * Builds the static chain constant fields for object-level chains and per-field chains.
     *
     * @param model the DTO model
     * @return ordered list of field specs
     */
    private List<FieldSpec> buildStaticFields(DtoModel model) {
        List<FieldSpec> fields = new ArrayList<>();

        ParameterizedTypeName listOfCanon = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(CANONICALIZER)));
        ParameterizedTypeName listOfSanit = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(SANITIZER)));

        // OBJ_CANON
        fields.add(buildChainField("OBJ_CANON", listOfCanon, model.objectCanonChain()));
        // OBJ_SANIT
        fields.add(buildChainField("OBJ_SANIT", listOfSanit, model.objectSanitChain()));
        // OBJ_SKIP_CANON
        fields.add(FieldSpec.builder(boolean.class, "OBJ_SKIP_CANON", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", model.skipCanonicalization())
                .build());
        // OBJ_SKIP_SANIT
        fields.add(FieldSpec.builder(boolean.class, "OBJ_SKIP_SANIT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", model.skipSanitization())
                .build());

        // Per-field constants. OTHER-kind fields with annotations also emit constants because
        // their dispatch in process(...) calls applyDefault with the field-level chain/skip.
        for (FieldModel field : model.fields().values()) {
            if (field.kind() == FieldKind.OTHER && !hasFieldLevelAnnotations(field)) continue;
            String prefix = Identifiers.constantName(field.name());
            fields.add(buildChainField(prefix + "_CANON", listOfCanon, field.canonChain()));
            fields.add(buildChainField(prefix + "_SANIT", listOfSanit, field.sanitChain()));
        }

        return fields;
    }

    /**
     * Builds a {@code static final List<Class<? extends X>>} field initialized with
     * {@code List.of(...)} for the given chain entries.
     *
     * @param name      the constant name
     * @param fieldType the {@link ParameterizedTypeName} for {@code List<Class<? extends X>>}
     * @param chain     the list of type mirrors for the chain classes
     * @return the field spec
     */
    private FieldSpec buildChainField(String name, ParameterizedTypeName fieldType, List<TypeMirror> chain) {
        CodeBlock initializer;
        if (chain.isEmpty()) {
            initializer = CodeBlock.of("$T.of()", LIST);
        } else {
            CodeBlock.Builder cb = CodeBlock.builder();
            cb.add("$T.of(", LIST);
            for (int i = 0; i < chain.size(); i++) {
                if (i > 0) cb.add(", ");
                cb.add("$L.class", chain.get(i).toString());
            }
            cb.add(")");
            initializer = cb.build();
        }
        return FieldSpec.builder(fieldType, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .build();
    }

    // --- fieldNameOwnerTypes() building ---

    /**
     * Collects, in deterministic emission order and free of duplicates, every class the generated
     * processor may hand to {@code InputTraversalContext.logicalFieldName} or dispatch into. The
     * set mirrors the owner arguments the emitted {@code process(...)} arms actually pass:
     *
     * <ul>
     *   <li>the origin class — the selector owner of the switch, the owner of every
     *       {@code applyString} / {@code applyStringCollection} arm, and the owner of the
     *       {@code default} arm. It is <em>always</em> present: the runtime reads an empty return
     *       from {@code GeneratedInputProcessor.fieldNameOwnerTypes()} as "this processor declares
     *       no owner set" and falls back to its reflective walk, so the origin class is what makes
     *       emptiness a usable sentinel;</li>
     *   <li>the erased nested/element type of every {@link FieldKind#NESTED_DTO} and
     *       {@link FieldKind#COLLECTION_OF_DTO} field, which its arm passes to
     *       {@code dispatchNested} / {@code dispatchObjectCollection} as both target and owner;</li>
     *   <li>the erased declared type of every <em>annotated</em> {@link FieldKind#OTHER} field —
     *       the same {@code nestedMapOwnerName} its arm hands to {@code applyDefault} for the
     *       reflective continuation (see {@link #buildOtherFieldArm}).</li>
     * </ul>
     *
     * <p>An unannotated {@link FieldKind#OTHER} field gets no arm of its own and therefore
     * contributes nothing. Deduplication happens here as well as in the emitted body: a
     * {@link java.util.LinkedHashSet} of {@link TypeName} keeps the generated source stable and
     * repeat-free across builds, while the emitted accumulation guards the case two distinct
     * {@link TypeMirror}s name one type without comparing equal.
     *
     * @param model       the DTO model
     * @param originClass the {@link ClassName} of the source DTO
     * @return the owner type names in emission order; never empty (the origin is always present)
     */
    private Set<TypeName> collectOwnerTypeNames(DtoModel model, ClassName originClass) {
        Set<TypeName> owners = new LinkedHashSet<>();
        owners.add(originClass);
        for (FieldModel field : model.fields().values()) {
            switch (field.kind()) {
                case NESTED_DTO, COLLECTION_OF_DTO -> owners.add(rawTypeName(field.nestedTypeMirror(), originClass));
                case OTHER -> {
                    if (hasFieldLevelAnnotations(field)) {
                        owners.add(otherFieldOwnerName(field, originClass));
                    }
                }
                default -> {
                    // STRING and COLLECTION_OF_STRINGS arms pass the origin class as owner, which
                    // is already seeded above.
                }
            }
        }
        return owners;
    }

    /**
     * Builds the static initializer that populates the emitted owner-type constant.
     *
     * <p>The body accumulates into an insertion-ordered {@link java.util.LinkedHashSet} and returns
     * {@link java.util.Set#copyOf}. It deliberately does <em>not</em> emit a
     * {@code Set.of(a, b, c)} varargs literal: {@code Set.of} rejects a duplicate element with
     * {@link IllegalArgumentException}, and because the constant is a {@code static final} field
     * that failure would surface as an {@code ExceptionInInitializerError} during generated-class
     * initialization — at first dispatch, not at build time.
     *
     * @param model       the DTO model
     * @param originClass the {@link ClassName} of the source DTO
     * @return the static initializer block
     */
    private CodeBlock buildOwnerTypesInitializer(DtoModel model, ClassName originClass) {
        CodeBlock.Builder init = CodeBlock.builder();
        init.addStatement("$T<$T<?>> owners = new $T<>()", SET, ClassName.get(Class.class), LINKED_HASH_SET);
        for (TypeName owner : collectOwnerTypeNames(model, originClass)) {
            init.addStatement("owners.add($T.class)", owner);
        }
        init.addStatement("$L = $T.copyOf(owners)", OWNER_TYPES_FIELD, SET);
        return init.build();
    }

    /**
     * Resolves the owner type an annotated {@link FieldKind#OTHER} field's arm passes to
     * {@code GeneratedSupport.applyDefault} — the field's erased declared type, falling back to the
     * enclosing DTO when the declared type is not class-resolvable (e.g. a raw collection with no
     * element type).
     *
     * <p>Single derivation shared by {@link #buildOtherFieldArm} and
     * {@link #collectOwnerTypeNames}, so the declared owner set cannot drift from the owners the
     * emitted body actually uses.
     *
     * @param field       the OTHER-kind field model with at least one annotation
     * @param originClass the ClassName of the source DTO
     * @return the owner {@link TypeName} usable as a {@code .class} literal target
     */
    private TypeName otherFieldOwnerName(FieldModel field, ClassName originClass) {
        return field.declaredType() != null ? rawTypeName(field.declaredType(), originClass) : originClass;
    }

    // --- process() method building ---

    /**
     * Builds the {@code process(...)} method body with a field-name {@code switch} statement.
     *
     * @param model       the DTO model
     * @param originClass the {@link ClassName} of the source DTO
     * @return the method spec
     */
    private MethodSpec buildProcessMethod(DtoModel model, ClassName originClass) {
        ParameterizedTypeName listOfClass = ParameterizedTypeName.get(
                LIST, ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(CANONICALIZER)));

        CodeBlock.Builder body = CodeBlock.builder();

        // Guard: if intermediate is not a Map, return unchanged
        body.beginControlFlow("if (!(intermediate instanceof $T<?, ?> raw))", MAP);
        body.addStatement("return intermediate");
        body.endControlFlow();

        // Seed root context. A caller with no context of its own can only seed identity naming;
        // the engine's own entry points always hand over the real parent, so this arm is a
        // last-resort fallback rather than the normal path.
        body.addStatement(
                "$T rootCtx = parent != null ? parent : $T.fromPolicies(policies, $T.IDENTITY)",
                INPUT_TRAVERSAL_CONTEXT,
                INPUT_TRAVERSAL_CONTEXT,
                INPUT_FIELD_NAME_RESOLVER);

        // Build output map
        body.addStatement("$T<String, Object> out = new $T<>(raw.size())", MAP, LINKED_HASH_MAP);

        // Iterate entries
        body.beginControlFlow("for ($T<?, ?> e : raw.entrySet())", Map.Entry.class);
        body.addStatement("String k = $T.valueOf(e.getKey())", String.class);
        body.addStatement("Object v = e.getValue()");
        body.beginControlFlow("if (v == null)");
        body.addStatement("out.put(k, null)");
        body.addStatement("continue");
        body.endControlFlow();

        // Compose the child path once per iteration (used by all arms and by the default
        // helper). This must happen before the switch / fallback path so it's visible to both.
        body.addStatement("String childPath = parentPath.isEmpty() ? k : parentPath + \".\" + k");

        // Switch on the PROJECTED key. The intermediate is keyed by wire names while the arms below
        // are keyed by Java property names, so a non-identity projection (e.g. a @JsonProperty
        // rename) would match no arm and silently drop the field's declared chain if the raw key
        // were switched on. The projection selects the arm only — `out` keeps the wire key `k`,
        // which is what the codec binds. This mirrors the reflective walker, which projects before
        // its per-field metadata lookup and likewise re-emits the wire key.
        //
        // Unknown keys flow through applyDefault with empty field-level chain/skip so route +
        // object-level + ancestor chains still apply to them — matching the reflective walker.
        // Annotated OTHER-kind fields get their own arm with their declared field-level constants
        // so a class-level @Canonicalize PLUS a field-level @Canonicalize on an Object-typed field
        // both compose correctly.
        boolean anyEmittable = hasEmittableFields(model) || hasAnyAnnotatedOther(model);
        if (!model.fields().isEmpty() && anyEmittable) {
            body.beginControlFlow("switch (rootCtx.logicalFieldName($T.class, k))", originClass);
            for (FieldModel field : model.fields().values()) {
                if (field.kind() == FieldKind.OTHER) {
                    if (hasFieldLevelAnnotations(field)) {
                        body.add(buildOtherFieldArm(field, originClass));
                    }
                    continue;
                }
                body.add(buildSwitchArm(field, model, originClass));
            }
            body.addStatement(
                    "default -> out.put(k, $T.applyDefault(v, rootCtx,"
                            + " OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,"
                            + " $T.of(), $T.of(), false, false,"
                            + " resolver, location, childPath, k, $T.class, $T.class, dispatcher))",
                    GENERATED_SUPPORT,
                    LIST,
                    LIST,
                    originClass,
                    originClass);
            body.endControlFlow();
        } else {
            // No emittable fields — still apply inherited + object-level chains via applyDefault
            // so route-level policies keep working for un-annotated body roots, and so a DTO
            // declared with class-level @Canonicalize/@Sanitize still trims its strings.
            body.addStatement(
                    "out.put(k, $T.applyDefault(v, rootCtx,"
                            + " OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,"
                            + " $T.of(), $T.of(), false, false,"
                            + " resolver, location, childPath, k, $T.class, $T.class, dispatcher))",
                    GENERATED_SUPPORT,
                    LIST,
                    LIST,
                    originClass,
                    originClass);
        }

        body.endControlFlow(); // for loop

        body.addStatement("return out");

        return MethodSpec.methodBuilder("process")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addParameter(Object.class, "intermediate")
                .addParameter(EFFECTIVE_INPUT_POLICIES, "policies")
                .addParameter(INPUT_LOCATION, "location")
                .addParameter(CHAIN_RESOLVER, "resolver")
                .addParameter(DISPATCHER, "dispatcher")
                .addParameter(com.palantir.javapoet.ParameterSpec.builder(INPUT_TRAVERSAL_CONTEXT, "parent")
                        .addAnnotation(NULLABLE)
                        .build())
                .addParameter(String.class, "parentPath")
                .addCode(body.build())
                .build();
    }

    /**
     * Returns {@code true} if the model has at least one field that is not {@link FieldKind#OTHER}.
     *
     * @param model the DTO model
     * @return {@code true} when there are emittable (non-other) fields
     */
    private boolean hasEmittableFields(DtoModel model) {
        for (FieldModel field : model.fields().values()) {
            if (field.kind() != FieldKind.OTHER) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code field} declares any field-level chain or skip flag —
     * used to decide whether an {@link FieldKind#OTHER}-kind field needs its own switch arm
     * (so its declared chain composes with object/inherited layers) or can fall through to the
     * default arm.
     *
     * @param field the field model
     * @return {@code true} if the field has any field-level annotation worth threading
     */
    private boolean hasFieldLevelAnnotations(FieldModel field) {
        return !field.canonChain().isEmpty() || !field.sanitChain().isEmpty() || field.skipCanon() || field.skipSanit();
    }

    /**
     * Returns {@code true} when at least one {@link FieldKind#OTHER} field carries field-level
     * annotations — i.e. requires its own switch arm.
     *
     * @param model the DTO model
     * @return {@code true} when an annotated OTHER-kind field is present
     */
    private boolean hasAnyAnnotatedOther(DtoModel model) {
        for (FieldModel field : model.fields().values()) {
            if (field.kind() == FieldKind.OTHER && hasFieldLevelAnnotations(field)) return true;
        }
        return false;
    }

    /**
     * Builds an explicit switch arm for an annotated {@link FieldKind#OTHER}-kind field. The
     * arm calls {@code applyDefault} with the field's declared chain/skip constants, so the
     * field-level layer composes with object and inherited layers exactly as the reflective
     * walker does.
     *
     * @param field       the OTHER-kind field model with at least one annotation
     * @param originClass the ClassName of the source DTO
     * @return a {@code case "fieldName" -> ...} code block
     */
    private CodeBlock buildOtherFieldArm(FieldModel field, ClassName originClass) {
        String fieldName = field.name();
        String prefix = Identifiers.constantName(fieldName);
        // Owner-type semantics are shape-dependent (see GeneratedSupport.applyDefault):
        // - Strings and list elements use the enclosing DTO as ownerType — matches reflective
        //   processStringValue / processNestedList, which propagate the parent owner.
        // - Nested maps use the declared field type as ownerType — matches reflective
        //   processNestedMap → dispatchNested(map, fieldMeta.fieldType(), ...) which then
        //   continues with targetType=fieldType and that same fieldType as ownerType.
        // Fall back to the enclosing DTO when the declared type isn't class-resolvable
        // (e.g. raw collections without an element type). Derived once, in otherFieldOwnerName, so
        // fieldNameOwnerTypes() declares exactly the owner this arm passes.
        TypeName nestedMapOwnerName = otherFieldOwnerName(field, originClass);
        return CodeBlock.builder()
                .addStatement(
                        "case $S -> out.put(k, $T.applyDefault(v, rootCtx,"
                                + " OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,"
                                + " $L, $L, $L, $L,"
                                + " resolver, location, childPath, $S, $T.class, $T.class, dispatcher))",
                        fieldName,
                        GENERATED_SUPPORT,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit(),
                        fieldName,
                        originClass,
                        nestedMapOwnerName)
                .build();
    }

    /**
     * Resolves a {@link javax.lang.model.type.TypeMirror} to a non-parameterized
     * {@link TypeName} suitable for use as a class literal target. Strips
     * type parameters via {@link javax.lang.model.util.Types#erasure} so generic types render
     * as the raw class.
     *
     * @param mirror   the type mirror to convert
     * @param fallback the fallback ClassName to use if conversion fails
     * @return a TypeName usable as the operand of {@code .class} in JavaPoet output
     */
    private TypeName rawTypeName(javax.lang.model.type.TypeMirror mirror, ClassName fallback) {
        try {
            javax.lang.model.type.TypeMirror erased = ctx.types().erasure(mirror);
            return TypeName.get(erased);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Builds a single {@code case "fieldName" -> ...} arm for the given field.
     *
     * @param field       the field model
     * @param model       the enclosing DTO model (for OBJ_* constants)
     * @param originClass the ClassName of the source DTO
     * @return the code block for this switch arm
     */
    private CodeBlock buildSwitchArm(FieldModel field, DtoModel model, ClassName originClass) {
        String fieldName = field.name();
        String prefix = Identifiers.constantName(fieldName);

        CodeBlock.Builder arm = CodeBlock.builder();

        switch (field.kind()) {
            case STRING -> {
                // applyString(v, rootCtx, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,
                //             FIELD_CANON, FIELD_SANIT, skipCanon, skipSanit,
                //             resolver, location, childPath, "fieldName", OriginClass.class)
                // The InputValueContext's logicalName is the JAVA property name because this arm
                // matched a declared property; childPath stays the WIRE path (§3.7), so a
                // diagnostic still points at the key the caller actually sent.
                arm.addStatement(
                        "case $S -> out.put(k, $L(v, rootCtx, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, "
                                + "$L, $L, $L, $L, resolver, location, childPath, $S, $T.class))",
                        fieldName,
                        APPLY_STRING,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit(),
                        fieldName,
                        originClass);
            }
            case COLLECTION_OF_STRINGS -> {
                // applyStringCollection(v, rootCtx, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,
                //                       FIELD_CANON, FIELD_SANIT, skipCanon, skipSanit,
                //                       resolver, location, childPath, OriginClass.class)
                arm.addStatement(
                        "case $S -> out.put(k, $L(v, rootCtx, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, "
                                + "$L, $L, $L, $L, resolver, location, childPath, $T.class))",
                        fieldName,
                        APPLY_STRING_COLLECTION,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit(),
                        originClass);
            }
            case NESTED_DTO -> {
                // Descend context + dispatch nested. The nested type mirror is erased via
                // rawTypeName before being used as a class literal: Java forbids parameterized
                // class literals (e.g. `Optional<String>.class` does not compile), and
                // GeneratedInputProcessorDispatcher.dispatchNested takes a raw Class<?> anyway.
                TypeName nestedType = rawTypeName(field.nestedTypeMirror(), originClass);
                arm.beginControlFlow("case $S ->", fieldName);
                // The descend is keyed on the two declaration sites it folds in: the ENCLOSING DTO
                // (which declared OBJ_CANON / OBJ_SANIT) and that DTO paired with this field name
                // (which declared the per-field chains). A self-referential type therefore
                // contributes each of its chains once per descent path instead of once per level of
                // the intermediate — the field-level key is what bounds `@Sanitize(X) Node child`.
                arm.addStatement(
                        "$T nestedCtx = rootCtx.descend($T.class, $S, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, "
                                + "OBJ_SKIP_SANIT, $L, $L, $L, $L)",
                        INPUT_TRAVERSAL_CONTEXT,
                        originClass,
                        fieldName,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit());
                arm.addStatement(
                        "out.put(k, dispatcher.dispatchNested(v, $T.class, policies, location, resolver, nestedCtx, childPath, $T.class))",
                        nestedType,
                        nestedType);
                arm.endControlFlow();
            }
            case COLLECTION_OF_DTO -> {
                // Descend context + dispatch collection of objects. Erase the element type
                // mirror for the same reason as NESTED_DTO above (e.g. a
                // List<Optional<String>> element type must render as Optional.class, not
                // Optional<String>.class).
                TypeName elementType = rawTypeName(field.nestedTypeMirror(), originClass);
                arm.beginControlFlow("case $S ->", fieldName);
                // Keyed on the enclosing DTO and this field name, for the same reason as the
                // NESTED_DTO arm above.
                arm.addStatement(
                        "$T innerCtx = rootCtx.descend($T.class, $S, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, "
                                + "OBJ_SKIP_SANIT, $L, $L, $L, $L)",
                        INPUT_TRAVERSAL_CONTEXT,
                        originClass,
                        fieldName,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit());
                arm.addStatement(
                        "out.put(k, $L(v, $T.class, policies, location, resolver, dispatcher, innerCtx, childPath, $T.class))",
                        DISPATCH_OBJECT_COLLECTION,
                        elementType,
                        elementType);
                arm.endControlFlow();
            }
            default -> {
                // OTHER — not reached because we skip OTHER in the caller, but for safety
            }
        }

        return arm.build();
    }
}
