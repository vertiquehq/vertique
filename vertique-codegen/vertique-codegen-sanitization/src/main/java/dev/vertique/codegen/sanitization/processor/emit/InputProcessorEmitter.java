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
import java.util.List;
import java.util.Map;
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
 *   <li>Implements {@code process(...)} with a {@code switch} over JSON field names, calling
 *       {@code GeneratedSupport.applyString}, {@code applyStringCollection}, or
 *       {@code dispatchObjectCollection} depending on field kind.</li>
 * </ul>
 *
 * <p>The emitted code shape exactly matches the hand-written companions in
 * {@code GeneratedVsReflectiveEquivalenceTest_Generated_InputProcessor} and
 * {@code GeneratedVsReflectiveEquivalenceTest_GeneratedSkip_InputProcessor}.
 */
public final class InputProcessorEmitter {

    // --- Well-known type names ---

    private static final ClassName GENERATED_INPUT_PROCESSOR =
            ClassName.get("dev.vertique.rest.core.request", "GeneratedInputProcessor");
    private static final ClassName EFFECTIVE_INPUT_POLICIES =
            ClassName.get("dev.vertique.rest.core.request", "EffectiveInputPolicies");
    private static final ClassName INPUT_LOCATION = ClassName.get("dev.vertique.core.sanitization", "InputLocation");
    private static final ClassName CHAIN_RESOLVER = ClassName.get("dev.vertique.rest.core.request", "ChainResolver");
    private static final ClassName DISPATCHER =
            ClassName.get("dev.vertique.rest.core.request", "GeneratedInputProcessorDispatcher");
    private static final ClassName INPUT_TRAVERSAL_CONTEXT =
            ClassName.get("dev.vertique.rest.core.request", "InputTraversalContext");
    private static final ClassName CANONICALIZER = ClassName.get("dev.vertique.core.sanitization", "Canonicalizer");
    private static final ClassName SANITIZER = ClassName.get("dev.vertique.core.sanitization", "Sanitizer");
    private static final ClassName NULLABLE = ClassName.get("jakarta.annotation", "Nullable");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    // GeneratedSupport static import target
    private static final ClassName GENERATED_SUPPORT =
            ClassName.get("dev.vertique.rest.core.request", "GeneratedSupport");
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
        classBuilder.addMethod(constructor);
        classBuilder.addMethod(targetTypeMethod);
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

        // Seed root context
        body.addStatement(
                "$T rootCtx = parent != null ? parent : $T.fromRoute(policies)",
                INPUT_TRAVERSAL_CONTEXT,
                INPUT_TRAVERSAL_CONTEXT);

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

        // Switch on key. Unknown keys flow through applyDefault with empty field-level chain/skip
        // so route + object-level + ancestor chains still apply to them — matching the reflective
        // walker. Annotated OTHER-kind fields get their own arm with their declared field-level
        // constants so a class-level @Canonicalize PLUS a field-level @Canonicalize on an
        // Object-typed field both compose correctly.
        boolean anyEmittable = hasEmittableFields(model) || hasAnyAnnotatedOther(model);
        if (!model.fields().isEmpty() && anyEmittable) {
            body.beginControlFlow("switch (k)");
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
        // (e.g. raw collections without an element type).
        com.palantir.javapoet.TypeName nestedMapOwnerName =
                field.declaredType() != null ? rawTypeName(field.declaredType(), originClass) : originClass;
        return CodeBlock.builder()
                .addStatement(
                        "case $S -> out.put(k, $T.applyDefault(v, rootCtx,"
                                + " OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT,"
                                + " $L, $L, $L, $L,"
                                + " resolver, location, childPath, k, $T.class, $T.class, dispatcher))",
                        fieldName,
                        GENERATED_SUPPORT,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit(),
                        originClass,
                        nestedMapOwnerName)
                .build();
    }

    /**
     * Resolves a {@link javax.lang.model.type.TypeMirror} to a non-parameterized
     * {@link com.palantir.javapoet.TypeName} suitable for use as a class literal target. Strips
     * type parameters via {@link javax.lang.model.util.Types#erasure} so generic types render
     * as the raw class.
     *
     * @param mirror   the type mirror to convert
     * @param fallback the fallback ClassName to use if conversion fails
     * @return a TypeName usable as the operand of {@code .class} in JavaPoet output
     */
    private com.palantir.javapoet.TypeName rawTypeName(javax.lang.model.type.TypeMirror mirror, ClassName fallback) {
        try {
            javax.lang.model.type.TypeMirror erased = ctx.types().erasure(mirror);
            return com.palantir.javapoet.TypeName.get(erased);
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
                //             resolver, location, childPath, k, OriginClass.class)
                arm.addStatement(
                        "case $S -> out.put(k, $L(v, rootCtx, OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, "
                                + "$L, $L, $L, $L, resolver, location, childPath, k, $T.class))",
                        fieldName,
                        APPLY_STRING,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit(),
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
                // Descend context + dispatch nested
                String nestedTypeFqn = field.nestedTypeMirror().toString();
                arm.beginControlFlow("case $S ->", fieldName);
                arm.addStatement(
                        "$T nestedCtx = rootCtx.descend(OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, "
                                + "$L, $L, $L, $L)",
                        INPUT_TRAVERSAL_CONTEXT,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit());
                arm.addStatement(
                        "out.put(k, dispatcher.dispatchNested(v, $L.class, policies, location, resolver, nestedCtx, childPath, $L.class))",
                        nestedTypeFqn,
                        nestedTypeFqn);
                arm.endControlFlow();
            }
            case COLLECTION_OF_DTO -> {
                // Descend context + dispatch collection of objects
                String elementTypeFqn = field.nestedTypeMirror().toString();
                arm.beginControlFlow("case $S ->", fieldName);
                arm.addStatement(
                        "$T innerCtx = rootCtx.descend(OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, "
                                + "$L, $L, $L, $L)",
                        INPUT_TRAVERSAL_CONTEXT,
                        prefix + "_CANON",
                        prefix + "_SANIT",
                        field.skipCanon(),
                        field.skipSanit());
                arm.addStatement(
                        "out.put(k, $L(v, $L.class, policies, location, resolver, dispatcher, innerCtx, childPath, $L.class))",
                        DISPATCH_OBJECT_COLLECTION,
                        elementTypeFqn,
                        elementTypeFqn);
                arm.endControlFlow();
            }
            default -> {
                // OTHER — not reached because we skip OTHER in the caller, but for safety
            }
        }

        return arm.build();
    }
}
