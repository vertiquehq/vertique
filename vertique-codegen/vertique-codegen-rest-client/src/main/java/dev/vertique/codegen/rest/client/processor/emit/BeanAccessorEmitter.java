// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.rest.client.processor.ParamModel;
import dev.vertique.codegen.rest.client.processor.scan.BeanModel;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates {@code {Bean}_BeanParamAccessor} implementation classes.
 *
 * <p>Each emitted class:
 * <ul>
 *   <li>Is {@code public final} with a {@code public} no-arg constructor (required for
 *       {@link dev.vertique.rest.client.BeanParamAccessorRegistry}'s {@code Class.forName} lookup).</li>
 *   <li>Implements {@code BeanParamAccessor<BeanType>}.</li>
 *   <li>Uses a {@code switch} expression in {@code extract()} for zero-reflection field access.</li>
 *   <li>Returns a {@code List.of(...)} from {@code fieldNames()} with all field names in order.</li>
 * </ul>
 *
 * <p>Bean-level fallback: if the {@link BeanModel#fullyGeneratable()} flag is {@code false}, the
 * emitter logs a note and skips generation entirely. The
 * {@link dev.vertique.rest.client.BeanParamAccessorRegistry} will then fall back to the
 * reflective accessor for the entire bean.
 *
 * <p>Deduplication: the {@link #emit(Set, Set)} method tracks emitted bean type FQNs across the
 * round and emits each accessor only once, even when multiple {@code @RestClient} interfaces
 * reference the same bean type.
 */
public final class BeanAccessorEmitter {

    private static final ClassName BEAN_PARAM_ACCESSOR = ClassName.get("dev.vertique.rest.client", "BeanParamAccessor");
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.rest.client.processor.RestClientProcessor";

    private final CodegenContext ctx;

    /** FQNs of bean types for which an accessor has already been emitted in this compilation round. */
    private final Set<String> emittedBeans = new LinkedHashSet<>();

    /**
     * Creates a new emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public BeanAccessorEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits {@code {Bean}_BeanParamAccessor} classes for all beans in the given set that are
     * fully generatable and have not already been emitted in this round.
     *
     * @param beanModels the set of bean models to emit; non-generatable beans are skipped with a note
     * @param externalBeanTypes bean type FQNs identified as external (not in the compilation unit);
     *     a warning is emitted for each; no accessor is generated for them
     */
    public void emit(Set<BeanModel> beanModels, Set<String> externalBeanTypes) {
        for (BeanModel beanModel : beanModels) {
            String fqn = beanModel.beanType().getQualifiedName().toString();
            if (emittedBeans.contains(fqn)) {
                continue; // Deduplicate across interfaces in the same compilation
            }
            if (externalBeanTypes.contains(fqn)) {
                // Already warned during scan; skip
                emittedBeans.add(fqn);
                continue;
            }
            if (!beanModel.fullyGeneratable()) {
                ctx.diagnostics()
                        .note(
                                beanModel.beanType(),
                                "%s has fields not accessible without setAccessible;"
                                        + " runtime reflective fallback applies",
                                fqn);
                emittedBeans.add(fqn);
                continue;
            }
            if (beanModel.fields().isEmpty()) {
                emittedBeans.add(fqn);
                continue;
            }
            emitOne(beanModel);
            emittedBeans.add(fqn);
        }
    }

    // --- Internal ---

    /**
     * Resolves a {@link TypeElement} to a JavaPoet {@link ClassName}, correctly handling nested
     * and inner classes by using {@link TypeName#get(TypeMirror)} on the erased type.
     *
     * <p>{@code TypeName.get(erasure)} on a declared type always returns a {@link ClassName} in
     * the APT environment. The cast is therefore safe; an {@link IllegalStateException} here
     * would signal an unexpected JavaPoet or APT contract violation.
     *
     * @param typeElement the type element to resolve
     * @return the ClassName for the type
     * @throws IllegalStateException if the erased type does not resolve to a {@link ClassName}
     */
    private ClassName resolveClassName(TypeElement typeElement) {
        javax.lang.model.type.TypeMirror erased = ctx.types().erasure(typeElement.asType());
        TypeName typeName = TypeName.get(erased);
        if (typeName instanceof ClassName cn) {
            return cn;
        }
        throw new IllegalStateException("Expected ClassName for erased TypeElement but got: " + typeName);
    }

    /**
     * Emits a single {@code {Bean}_BeanParamAccessor} class.
     *
     * @param beanModel the bean model to generate for
     */
    private void emitOne(BeanModel beanModel) {
        TypeElement beanType = beanModel.beanType();
        // Always use the origin package — never the -Avertique.codegen.package override —
        // so the emitted FQN matches what GeneratedNames.companionFqn() looks up at runtime.
        String packageName = ctx.packageNameOf(beanType);
        // Use Identifiers.generatedClassName to flatten nested types:
        // e.g. Outer.Inner -> Outer_Inner_BeanParamAccessor (not Inner_BeanParamAccessor)
        String generatedSimpleName = Identifiers.generatedClassName(beanType, "_BeanParamAccessor");

        // Resolve ClassName via TypeName.get() to correctly handle nested/inner classes
        // (e.g. BenchmarkBeanFixtures.RecordBean) — ClassName.get(pkg, simpleName) would
        // silently omit the enclosing class, producing an unresolvable reference.
        ClassName beanClassName = resolveClassName(beanType);
        ParameterizedTypeName accessorTypeName = ParameterizedTypeName.get(BEAN_PARAM_ACCESSOR, beanClassName);

        CodeBlock extractBody = buildExtractBody(beanModel.fields());
        CodeBlock fieldNamesBody = buildFieldNamesBody(beanModel.fields());

        // Constructor
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc(
                        "Required public no-arg constructor for {@link dev.vertique.rest.client.BeanParamAccessorRegistry} discovery.")
                .build();

        // extract() method
        MethodSpec extractMethod = MethodSpec.methodBuilder("extract")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addParameter(beanClassName, "bean")
                .addParameter(String.class, "fieldName")
                .addCode(extractBody)
                .addJavadoc("Extracts the named field value from the bean using direct field/method access.\n\n"
                        + "@param bean the bean instance\n"
                        + "@param fieldName the field name to access\n"
                        + "@return the field value\n"
                        + "@throws IllegalArgumentException if the field name is unknown\n")
                .build();

        // fieldNames() method
        MethodSpec fieldNamesMethod = MethodSpec.methodBuilder("fieldNames")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class)))
                .addCode(fieldNamesBody)
                .addJavadoc("Returns the ordered list of field names this accessor can extract.\n\n"
                        + "@return the list of field names\n")
                .build();

        TypeSpec typeSpec = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(accessorTypeName)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addJavadoc(
                        "Generated {@link dev.vertique.rest.client.BeanParamAccessor} for {@link $T}.\n\n"
                                + "<p>This class is generated by {@code $L} and provides zero-reflection access\n"
                                + "to the fields of {@link $T} by using direct method/field references.\n"
                                + "It is discovered at runtime by {@link dev.vertique.rest.client.BeanParamAccessorRegistry}\n"
                                + "via {@code Class.forName(\"$L\")}.\n",
                        beanClassName,
                        PROCESSOR_FQN,
                        beanClassName,
                        packageName + "." + generatedSimpleName)
                .addMethod(constructor)
                .addMethod(extractMethod)
                .addMethod(fieldNamesMethod)
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();
        String generatedFqn = packageName + "." + generatedSimpleName;
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(null, "Failed to write generated source file '%s': %s", generatedFqn, e.getMessage());
        }
    }

    /**
     * Builds the body of the {@code extract()} method as a {@code switch (fieldName)} expression.
     *
     * <p>A switch expression is used so the JIT can apply {@code tableswitch} or {@code lookupswitch}
     * with {@code indy}-backed {@link String} hashing, giving significantly lower dispatch overhead
     * than an if/else-if chain for beans with many fields.
     *
     * <p>Switch cases use {@link ParamModel#javaName()} (the Java source identifier) because
     * {@link dev.vertique.rest.client.BeanParamAccessor#extract(Object, String)} is called by the
     * runtime with the Java member name, not the JAX-RS wire name. For example, a field declared as
     * {@code @QueryParam("q") String query} must be looked up as {@code "query"}, not {@code "q"}.
     *
     * <p>Each {@code case} arm dispatches via {@link ParamModel#accessExpression()} which is one of:
     * record component accessor ({@code bean.name()}), public/package-private field access
     * ({@code bean.name}), or public getter call ({@code bean.getName()}) — all resolved at scan
     * time by {@code BeanParamScanner}.
     *
     * @param fields the ordered list of field models
     * @return the code block for the method body
     */
    private CodeBlock buildExtractBody(List<ParamModel> fields) {
        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return switch (fieldName) {\n").indent();
        for (ParamModel field : fields) {
            body.add("case $S -> $L;\n", field.javaName(), field.accessExpression());
        }
        body.add("default -> throw new $T($S + fieldName);\n", IllegalArgumentException.class, "Unknown field: ");
        body.unindent().addStatement("}");
        return body.build();
    }

    /**
     * Builds the body of the {@code fieldNames()} method.
     *
     * <p>Returns the ordered list of <em>Java member names</em> (using {@link ParamModel#javaName()})
     * because callers (e.g. {@code RestClientRequestFactory.collectParamsWithMeta}) iterate this list and
     * pass each name back to {@link dev.vertique.rest.client.BeanParamAccessor#extract(Object, String)},
     * which is keyed by Java member name. Returning JAX-RS wire names here would cause
     * {@code extract()} to throw {@link IllegalArgumentException} on renamed fields
     * (e.g. {@code @QueryParam("q") String query}).
     *
     * @param fields the ordered list of field models
     * @return the code block returning {@code List.of(...)}
     */
    private CodeBlock buildFieldNamesBody(List<ParamModel> fields) {
        if (fields.isEmpty()) {
            return CodeBlock.of("return $T.of();\n", List.class);
        }
        CodeBlock.Builder args = CodeBlock.builder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                args.add(", ");
            }
            args.add("$S", fields.get(i).javaName());
        }
        return CodeBlock.of("return $T.of($L);\n", List.class, args.build());
    }
}
