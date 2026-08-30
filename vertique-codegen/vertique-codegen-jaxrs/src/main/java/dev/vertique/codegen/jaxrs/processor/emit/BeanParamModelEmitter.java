// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emitter for per-bean {@code {Bean}_BeanParamModel} companions (CG-010 step 4c).
 *
 * <p>For each {@code @BeanParam} or {@code @RequestParams} type discovered across the validated
 * resource contracts, this emitter generates a {@code public final class {Bean}_BeanParamModel}
 * in the bean's own package. The generated class implements
 * {@code dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel<Bean>} and returns a
 * pre-computed {@code List<BeanParamFieldMeta>} from its {@code fields()} method.
 *
 * <h2>Type resolution</h2>
 *
 * <p>Field types are resolved via {@code GeneratedJaxRsDescriptorSupport} string-FQN helpers,
 * but the resolution happens in a {@code private static final} field initializer so it occurs once
 * at class-load time rather than on every {@code fields()} call. This avoids the need to pass a
 * {@code support} parameter into the no-arg {@code fields()} method.
 *
 * <h2>Field traversal semantics</h2>
 *
 * <p>The field traversal mirrors the runtime {@code ParameterExtractor.computeBeanFields}
 * semantics exactly, including the adjacent-defect-ex-#30 subclass-wins field-hiding dedup:
 * <ul>
 *   <li><strong>Records</strong> — iterates {@code getRecordComponents()} in declaration order.
 *       JAX-RS annotations land on the synthetic accessor method; both component and accessor are
 *       checked to mirror the runtime lookup.</li>
 *   <li><strong>Classes</strong> — walks the subclass-to-superclass chain; the first declaration
 *       of a given field name wins (subclass-wins dedup). This matches JLS § 8.3 field-hiding and
 *       the compile-time {@code JaxRsBeanScanner} algorithm.</li>
 * </ul>
 *
 * <h2>Annotation semantics for bean fields</h2>
 *
 * <p>For bean fields, the {@code annotations} slot on each {@code ParamMeta} is populated by
 * loading the field's declared annotations at class-load time via a private static
 * {@code loadFieldAnnotations} helper. This ensures that input-policy annotations
 * ({@code @Canonicalize}, {@code @Sanitize}, {@code @SkipCanonicalization},
 * {@code @SkipSanitization}) declared on individual bean-param fields are visible to
 * {@code ParameterExtractor.materializeBean}, which derives per-field
 * {@link dev.vertique.input.processing.EffectiveInputPolicies} from the annotation array.
 * Without populated annotations the codegen path silently drops field-level policies,
 * creating parity divergence with the reflective path.
 *
 * <p>For records, annotations are loaded from the accessor method (where the Java compiler
 * propagates JAX-RS and input-policy annotations due to their {@code @Target} sets).
 * For classes, annotations are loaded from the declared field itself.
 */
public final class BeanParamModelEmitter {

    // --- Well-known FQNs ---

    private static final String BEAN_PARAM_MODEL_FQN = "dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamModel";
    private static final String DEFAULT_VALUE_FQN = "jakarta.ws.rs.DefaultValue";
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor";

    // --- JavaPoet ClassNames ---

    private static final ClassName BEAN_PARAM_MODEL = ClassName.bestGuess(BEAN_PARAM_MODEL_FQN);
    private static final ClassName BEAN_PARAM_FIELD_META =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "BeanParamFieldMeta");
    private static final ClassName PARAM_META =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamMeta");
    private static final ClassName PARAM_SOURCE =
            ClassName.get("dev.vertique.rest.jaxrs", "ResourceMethodMeta", "ParamSource");
    private static final ClassName SUPPORT =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsDescriptorSupport");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    // --- State ---

    private final CodegenContext ctx;

    // --- Constructor ---

    /**
     * Creates an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public BeanParamModelEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Public API ---

    /**
     * Emits the {@code {Bean}_BeanParamModel} source file for the given bean type.
     *
     * <p>The generated class is placed in the same package as the bean type. Fields are emitted in
     * subclass-wins declaration order (subclass fields first, then superclass), with subclass-wins
     * dedup applied for duplicate field names. Record components are emitted in declaration order.
     *
     * @param beanType the bean type element ({@code @BeanParam} or {@code @RequestParams} type);
     *                 must not be {@code null}
     */
    public void emit(TypeElement beanType) {
        String pkg = ctx.packageNameOf(beanType);
        String simpleName = Identifiers.generatedClassName(beanType, "_BeanParamModel");

        // ClassName.get(TypeElement) yields the source-form nested name (Outer.Inner) which is
        // what generated source code needs as a type reference. Don't reconstruct it manually
        // from getSimpleName(), or nested @BeanParam types fail to compile.
        ClassName beanClass = ClassName.get(beanType);
        ClassName modelClass = ClassName.get(pkg, simpleName);
        ParameterizedTypeName modelInterface = ParameterizedTypeName.get(BEAN_PARAM_MODEL, beanClass);

        // Collect field entries
        List<BeanFieldSpec> fields = collectFields(beanType);

        // --- SELF_CL constant: the model class's own classloader, used by annotation loaders ---
        // This ensures that loadFieldAnnotations / loadRecordComponentAnnotations resolve the
        // bean type in the same classloader that loaded the generated companion. Using the
        // model class's own classloader is essential in isolated classloader environments
        // (e.g. APT test harnesses) where the bean class is not on the system classpath.
        FieldSpec selfClField = FieldSpec.builder(
                        ClassLoader.class, "SELF_CL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.class.getClassLoader()", ClassName.get(pkg, simpleName))
                .build();

        // --- Static type constants per field ---
        List<FieldSpec> staticFields = new ArrayList<>();
        staticFields.add(selfClField);
        staticFields.addAll(buildTypeConstants(fields));

        // --- beanType() method ---
        MethodSpec beanTypeMethod = MethodSpec.methodBuilder("beanType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), beanClass))
                .addStatement("return $T.class", beanClass)
                .build();

        // --- fields() method ---
        MethodSpec fieldsMethod = buildFieldsMethod(fields);

        // --- private static helpers emitted into generated class ---
        MethodSpec resolveClassHelper = buildResolveClassHelper();
        MethodSpec loadFieldAnnHelper = buildLoadFieldAnnotationsHelper();
        MethodSpec loadRecordAnnHelper = buildLoadRecordComponentAnnotationsHelper();

        // --- assemble class ---
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(ClassName.get(Generated.class))
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addSuperinterface(modelInterface);

        for (FieldSpec sf : staticFields) {
            classBuilder.addField(sf);
        }
        classBuilder.addMethod(beanTypeMethod);
        classBuilder.addMethod(fieldsMethod);
        classBuilder.addMethod(resolveClassHelper);
        classBuilder.addMethod(loadFieldAnnHelper);
        classBuilder.addMethod(loadRecordAnnHelper);

        JavaFile javaFile = JavaFile.builder(pkg, classBuilder.build()).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(beanType, "Failed to write %s: %s", modelClass.canonicalName(), e.getMessage());
        }
    }

    // --- Private: field collection ---

    /**
     * Immutable compile-time representation of a single bean field entry.
     *
     * @param fieldIndex   the zero-based position in the collected list (used for constant names)
     * @param fieldName    the Java field name
     * @param paramName    the JAX-RS param name (from annotation value)
     * @param source       the compile-time param source classification
     * @param typeFqn      the erased FQN of the field type
     * @param defaultValue the {@code @DefaultValue} value, or {@code null}
     * @param isRecord     {@code true} when the enclosing bean type is a record (so the generated
     *                     annotation-loader uses the accessor method rather than the field)
     * @param beanTypeFqn  the erased FQN of the owning bean class (needed by the annotation loader)
     */
    private record BeanFieldSpec(
            int fieldIndex,
            String fieldName,
            String paramName,
            JaxRsParamSource source,
            String typeFqn,
            String defaultValue,
            boolean isRecord,
            String beanTypeFqn) {}

    /**
     * Collects the ordered list of {@link BeanFieldSpec} entries for the given bean type.
     *
     * <p>For records, iterates record components in declaration order. For classes, walks
     * subclass-to-superclass with subclass-wins field-hiding dedup (first-seen-by-name wins).
     *
     * @param beanType the bean type element
     * @return ordered, deduped list of field specs; never {@code null}
     */
    private List<BeanFieldSpec> collectFields(TypeElement beanType) {
        if (beanType.getKind() == ElementKind.RECORD) {
            return collectRecordFields(beanType);
        }
        return collectClassFields(beanType);
    }

    /**
     * Collects fields from a record type in component declaration order.
     *
     * @param beanType the record type element
     * @return ordered field specs
     */
    private List<BeanFieldSpec> collectRecordFields(TypeElement beanType) {
        // Binary name (Outer$Inner) — Class.forName at runtime needs this form, not the
        // source-form Outer.Inner that getQualifiedName() returns.
        String beanFqn = TypeMirrorFqn.binaryName(beanType, ctx);
        List<BeanFieldSpec> result = new ArrayList<>();
        int idx = 0;
        for (RecordComponentElement component : beanType.getRecordComponents()) {
            BeanFieldSpec spec = resolveRecordComponentSpec(component, idx, beanFqn);
            if (spec != null) {
                result.add(spec);
                idx++;
            }
        }
        return result;
    }

    /**
     * Collects fields from a class hierarchy using subclass-wins dedup.
     *
     * <p>Walks from the concrete class to each superclass (stopping before {@code Object}),
     * collecting each non-static field once. When a subclass and a superclass both declare a field
     * with the same name, only the subclass field is included (JLS § 8.3 field-hiding).
     *
     * @param beanType the class type element
     * @return ordered field specs
     */
    private List<BeanFieldSpec> collectClassFields(TypeElement beanType) {
        List<BeanFieldSpec> result = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        TypeElement current = beanType;
        int idx = 0;

        while (current != null && !isObjectType(current)) {
            // Binary name (Outer$Inner) — Class.forName at runtime needs this form, not the
            // source-form Outer.Inner that getQualifiedName() returns.
            String currentFqn = TypeMirrorFqn.binaryName(current, ctx);
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                VariableElement field = (VariableElement) enclosed;
                if (field.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                String fieldName = field.getSimpleName().toString();
                if (!seenNames.add(fieldName)) {
                    // Subclass already reserved this name — skip.
                    continue;
                }
                BeanFieldSpec spec = resolveFieldSpec(field, fieldName, idx, currentFqn);
                if (spec != null) {
                    result.add(spec);
                    idx++;
                }
            }
            current = superclassOf(current);
        }
        return result;
    }

    /**
     * Resolves a {@link BeanFieldSpec} for a record component, checking the accessor method
     * first (since JAX-RS annotations land on the accessor method, not the component itself).
     *
     * @param component  the record component element
     * @param fieldIndex the zero-based index for naming static type constants
     * @param beanFqn    the erased FQN of the enclosing bean record (used by the annotation loader)
     * @return the field spec, or {@code null} if no JAX-RS annotation found
     */
    private BeanFieldSpec resolveRecordComponentSpec(RecordComponentElement component, int fieldIndex, String beanFqn) {
        ExecutableElement accessor = component.getAccessor();
        String fieldName = component.getSimpleName().toString();
        String typeFqn = erasedFqn(component.asType());

        // Check accessor first, then component (mirrors ParameterExtractor.resolveComponentParam)
        String defaultValue = getAnnotationValue(accessor, (Element) component, DEFAULT_VALUE_FQN, "value");

        String qpName = getAnnotationValue(accessor, (Element) component, JaxRsAnnotations.QUERY_PARAM, "value");
        if (qpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, qpName, JaxRsParamSource.QUERY, typeFqn, defaultValue, true, beanFqn);
        }
        String ppName = getAnnotationValue(accessor, (Element) component, JaxRsAnnotations.PATH_PARAM, "value");
        if (ppName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, ppName, JaxRsParamSource.PATH, typeFqn, defaultValue, true, beanFqn);
        }
        String hpName = getAnnotationValue(accessor, (Element) component, JaxRsAnnotations.HEADER_PARAM, "value");
        if (hpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, hpName, JaxRsParamSource.HEADER, typeFqn, defaultValue, true, beanFqn);
        }
        String cpName = getAnnotationValue(accessor, (Element) component, JaxRsAnnotations.COOKIE_PARAM, "value");
        if (cpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, cpName, JaxRsParamSource.COOKIE, typeFqn, defaultValue, true, beanFqn);
        }
        String fpName = getAnnotationValue(accessor, (Element) component, JaxRsAnnotations.FORM_PARAM, "value");
        if (fpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, fpName, JaxRsParamSource.FORM, typeFqn, defaultValue, true, beanFqn);
        }
        return null;
    }

    /**
     * Resolves a {@link BeanFieldSpec} for a class field.
     *
     * @param field         the field variable element
     * @param fieldName     the Java field name
     * @param fieldIndex    the zero-based index for naming static type constants
     * @param declaringFqn  the erased FQN of the class that declares this field (may be a
     *                      superclass — used by the annotation loader to find the right
     *                      {@link java.lang.reflect.Field})
     * @return the field spec, or {@code null} if no JAX-RS annotation found
     */
    private BeanFieldSpec resolveFieldSpec(
            VariableElement field, String fieldName, int fieldIndex, String declaringFqn) {
        String typeFqn = erasedFqn(field.asType());
        String defaultValue = annotationValue(field, DEFAULT_VALUE_FQN, "value");

        String qpName = annotationValue(field, JaxRsAnnotations.QUERY_PARAM, "value");
        if (qpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, qpName, JaxRsParamSource.QUERY, typeFqn, defaultValue, false, declaringFqn);
        }
        String ppName = annotationValue(field, JaxRsAnnotations.PATH_PARAM, "value");
        if (ppName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, ppName, JaxRsParamSource.PATH, typeFqn, defaultValue, false, declaringFqn);
        }
        String hpName = annotationValue(field, JaxRsAnnotations.HEADER_PARAM, "value");
        if (hpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, hpName, JaxRsParamSource.HEADER, typeFqn, defaultValue, false, declaringFqn);
        }
        String cpName = annotationValue(field, JaxRsAnnotations.COOKIE_PARAM, "value");
        if (cpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, cpName, JaxRsParamSource.COOKIE, typeFqn, defaultValue, false, declaringFqn);
        }
        String fpName = annotationValue(field, JaxRsAnnotations.FORM_PARAM, "value");
        if (fpName != null) {
            return new BeanFieldSpec(
                    fieldIndex, fieldName, fpName, JaxRsParamSource.FORM, typeFqn, defaultValue, false, declaringFqn);
        }
        return null;
    }

    // --- Private: static type constant builders ---

    /**
     * Builds one {@code private static final Class<?> TYPE_n = resolveClass("fqn");} constant
     * and one {@code private static final Annotation[] ANN_n = loadFieldAnnotations(...);} constant
     * per field, resolved once at class-load time.
     *
     * <p>The {@code ANN_n} constant carries the field's declared annotations so that
     * {@code ParameterExtractor.materializeBean} can derive per-field
     * {@link dev.vertique.input.processing.EffectiveInputPolicies} from input-policy annotations
     * (e.g. {@code @Canonicalize}, {@code @Sanitize}) on individual bean-param fields.
     *
     * @param fields the collected field specs
     * @return the list of static field specs (TYPE_n followed by ANN_n for each field)
     */
    private static List<FieldSpec> buildTypeConstants(List<BeanFieldSpec> fields) {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        ArrayTypeName annotationArray = ArrayTypeName.of(ClassName.get(java.lang.annotation.Annotation.class));
        List<FieldSpec> result = new ArrayList<>();
        for (BeanFieldSpec spec : fields) {
            String typeConst = "TYPE_" + spec.fieldIndex();
            result.add(FieldSpec.builder(classOfQ, typeConst, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("resolveClass($S)", spec.typeFqn())
                    .build());

            String annConst = "ANN_" + spec.fieldIndex();
            CodeBlock annInit;
            if (spec.isRecord()) {
                // For records, annotations are on the accessor method (Java compiler propagates
                // annotations with METHOD target to the generated accessor).
                annInit = CodeBlock.of("loadRecordComponentAnnotations($S, $S)", spec.beanTypeFqn(), spec.fieldName());
            } else {
                // For classes, annotations are on the declared field itself.
                annInit = CodeBlock.of("loadFieldAnnotations($S, $S)", spec.beanTypeFqn(), spec.fieldName());
            }
            result.add(FieldSpec.builder(annotationArray, annConst, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(annInit)
                    .build());
        }
        return result;
    }

    // --- Private: fields() method builder ---

    /**
     * Builds the {@code fields()} method returning the {@code List<BeanParamFieldMeta>}.
     *
     * @param fields the collected field specs
     * @return the method spec
     */
    private MethodSpec buildFieldsMethod(List<BeanFieldSpec> fields) {
        ParameterizedTypeName listOfFieldMeta = ParameterizedTypeName.get(LIST, BEAN_PARAM_FIELD_META);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return $T.of(\n", LIST);
        body.indent();

        for (int i = 0; i < fields.size(); i++) {
            BeanFieldSpec spec = fields.get(i);
            body.add(buildBeanParamFieldMeta(spec, i < fields.size() - 1));
        }

        body.unindent();
        body.addStatement(")");

        return MethodSpec.methodBuilder("fields")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfFieldMeta)
                .addCode(body.build())
                .build();
    }

    /**
     * Builds the code block for a single {@code new BeanParamFieldMeta(...)} constructor call.
     *
     * <p>{@code genericType} is {@code null} and {@code componentType} is {@code null}. Bean param
     * fields are not generic collections at this level; if needed, component type support can be
     * added later.
     * TODO(CG-010-later): emit componentType for List-valued bean param fields.
     *
     * <p>{@code annotations} is populated from the compile-time {@code ANN_n} static constant,
     * which is loaded at class-load time by {@code loadFieldAnnotations} /
     * {@code loadRecordComponentAnnotations}. This ensures that input-policy annotations
     * ({@code @Canonicalize}, {@code @Sanitize}, etc.) declared on individual bean-param fields
     * are visible to {@code ParameterExtractor.materializeBean} for per-field policy derivation.
     *
     * @param spec     the bean field spec
     * @param addComma whether to append a trailing comma
     * @return the code block
     */
    private static CodeBlock buildBeanParamFieldMeta(BeanFieldSpec spec, boolean addComma) {
        CodeBlock.Builder cb = CodeBlock.builder();
        String typeConst = "TYPE_" + spec.fieldIndex();
        String annConst = "ANN_" + spec.fieldIndex();

        cb.add(
                "new $T($S, new $T($S, $T.$L, $L, null, null, ",
                BEAN_PARAM_FIELD_META,
                spec.fieldName(),
                PARAM_META,
                spec.paramName(),
                PARAM_SOURCE,
                spec.source().name(),
                typeConst);

        if (spec.defaultValue() != null) {
            cb.add("$S, ", spec.defaultValue());
        } else {
            cb.add("null, ");
        }

        // annotations — loaded at class-load time from field/accessor declarations
        cb.add("$L))", annConst);

        if (addComma) {
            cb.add(",\n");
        } else {
            cb.add("\n");
        }
        return cb.build();
    }

    // --- Private: resolve helper method (emitted into generated class) ---

    /**
     * Builds the {@code resolveClass} private static helper used in the static field initializers
     * for {@code TYPE_n} field-type constants.
     *
     * <p>Uses {@code SELF_CL} (the generated model class's own classloader) — NOT
     * {@code Thread.class.getClassLoader()} which returns {@code null} (bootstrap) and only
     * resolves JDK types. Application-defined field types (enums, domain classes, wrapper types)
     * declared on a {@code @BeanParam} bean are not visible to bootstrap, so the bootstrap loader
     * causes companion class-init to fail with {@code ClassNotFoundException} on first request.
     *
     * @return the method spec
     */
    private static MethodSpec buildResolveClassHelper() {
        ParameterizedTypeName classOfQ = ParameterizedTypeName.get(
                ClassName.get(Class.class), com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder("resolveClass")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(classOfQ)
                .addParameter(String.class, "fqn")
                .beginControlFlow("try")
                .addStatement("return new $T().resolveClass(fqn, SELF_CL)", SUPPORT)
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement(
                        "throw new $T(\"Failed to load bean-param field type \" + fqn"
                                + " + \" (companion classloader=\" + SELF_CL + \")\", e)",
                        IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code loadFieldAnnotations(String beanFqn, String fieldName)} private static
     * helper emitted into the generated class. Uses reflection to read declared annotations from
     * the named field at class-load time, ensuring per-field input-policy annotations
     * ({@code @Canonicalize}, {@code @Sanitize}, etc.) are available to
     * {@code ParameterExtractor.materializeBean}.
     *
     * <p>Class resolution uses the {@code SELF_CL} constant (the generated model class's own
     * classloader) so that the bean type is found in the same classloader that loaded the
     * companion. This is essential in isolated classloader environments (e.g. APT test harnesses)
     * where the bean class is not on the system/bootstrap classpath.
     *
     * @return the method spec
     */
    private static MethodSpec buildLoadFieldAnnotationsHelper() {
        ArrayTypeName annArray = ArrayTypeName.of(ClassName.get(java.lang.annotation.Annotation.class));
        return MethodSpec.methodBuilder("loadFieldAnnotations")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(annArray)
                .addParameter(String.class, "beanFqn")
                .addParameter(String.class, "fieldName")
                .beginControlFlow("try")
                .addStatement("$T beanClass = new $T().resolveClass(beanFqn, SELF_CL)", Class.class, SUPPORT)
                .addStatement(
                        "$T f = beanClass.getDeclaredField(fieldName)", ClassName.get(java.lang.reflect.Field.class))
                .addStatement("return f.getAnnotations()")
                .nextControlFlow("catch ($T | $T e)", ClassNotFoundException.class, NoSuchFieldException.class)
                .addStatement(
                        "throw new $T(\"Failed to load annotations for bean field \" + beanFqn + \"#\" + fieldName"
                                + " + \" (companion classloader=\" + SELF_CL + \")\", e)",
                        IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Builds the {@code loadRecordComponentAnnotations(String beanFqn, String componentName)}
     * private static helper emitted into the generated class. For records, input-policy
     * annotations are placed on the synthetic accessor method (the Java compiler propagates them
     * there because JAX-RS {@code @Target} sets include {@code METHOD} but not
     * {@code RECORD_COMPONENT}). This helper returns the accessor method's annotations so that
     * {@code ParameterExtractor.materializeBean} sees the full annotation set.
     *
     * <p>Uses {@code SELF_CL} for class resolution — see {@link #buildLoadFieldAnnotationsHelper()}.
     *
     * @return the method spec
     */
    private static MethodSpec buildLoadRecordComponentAnnotationsHelper() {
        ArrayTypeName annArray = ArrayTypeName.of(ClassName.get(java.lang.annotation.Annotation.class));
        ClassName recordComponent = ClassName.get(java.lang.reflect.RecordComponent.class);
        return MethodSpec.methodBuilder("loadRecordComponentAnnotations")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(annArray)
                .addParameter(String.class, "beanFqn")
                .addParameter(String.class, "componentName")
                .beginControlFlow("try")
                .addStatement("$T beanClass = new $T().resolveClass(beanFqn, SELF_CL)", Class.class, SUPPORT)
                .beginControlFlow("for ($T rc : beanClass.getRecordComponents())", recordComponent)
                .beginControlFlow("if (rc.getName().equals(componentName))")
                .addStatement("return rc.getAccessor().getAnnotations()")
                .endControlFlow()
                .endControlFlow()
                .addStatement(
                        "throw new $T(\"Record component not found: \" + beanFqn + \"#\" + componentName"
                                + " + \" (companion classloader=\" + SELF_CL + \")\")",
                        IllegalStateException.class)
                .nextControlFlow("catch ($T e)", ClassNotFoundException.class)
                .addStatement(
                        "throw new $T(\"Failed to load annotations for record component \" + beanFqn + \"#\""
                                + " + componentName + \" (companion classloader=\" + SELF_CL + \")\", e)",
                        IllegalStateException.class)
                .endControlFlow()
                .build();
    }

    // --- Private: type helpers ---

    /**
     * Returns the erased binary FQN of {@code type}. Delegates to {@link TypeMirrorFqn}
     * so the three CG-010 emitters share one source of truth on nested-type form.
     */
    private String erasedFqn(TypeMirror type) {
        return TypeMirrorFqn.erasedFqn(type, ctx);
    }

    /**
     * Returns the superclass {@link TypeElement}, or {@code null} if the superclass is
     * {@code Object} or unavailable.
     *
     * @param type the type element
     * @return the superclass element, or {@code null}
     */
    private TypeElement superclassOf(TypeElement type) {
        return ctx.asTypeElement(type.getSuperclass())
                .filter(te -> !isObjectType(te))
                .orElse(null);
    }

    /**
     * Returns {@code true} when the type element represents {@code java.lang.Object}.
     *
     * @param type the type element to check
     * @return {@code true} if this is {@code Object}
     */
    private static boolean isObjectType(TypeElement type) {
        return "java.lang.Object".equals(type.getQualifiedName().toString());
    }

    // --- Private: annotation value helpers ---

    /**
     * Returns the {@code attributeName} value of the given annotation FQN on {@code element}, or
     * {@code null} if the annotation is not present.
     *
     * @param element       the element to inspect
     * @param annotationFqn the fully-qualified annotation name
     * @param attributeName the annotation attribute to read
     * @return the string value, or {@code null}
     */
    private String annotationValue(Element element, String annotationFqn, String attributeName) {
        return AnnotationMirrors.findByFqn(element, annotationFqn)
                .flatMap(m -> ctx.annotations().attribute(m, attributeName, String.class))
                .orElse(null);
    }

    /**
     * Returns the {@code attributeName} value of the given annotation FQN by checking
     * {@code primary} first, then falling back to {@code fallback}.
     *
     * <p>This mirrors the record-component lookup: JAX-RS annotations land on the accessor method
     * ({@code primary}) but may also be on the component itself ({@code fallback}).
     *
     * @param primary       the primary element to check (accessor method)
     * @param fallback      the fallback element (record component)
     * @param annotationFqn the annotation FQN
     * @param attributeName the attribute name
     * @return the string value, or {@code null}
     */
    private String getAnnotationValue(Element primary, Element fallback, String annotationFqn, String attributeName) {
        String val = annotationValue(primary, annotationFqn, attributeName);
        return val != null ? val : annotationValue(fallback, annotationFqn, attributeName);
    }
}
