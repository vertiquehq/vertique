// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.rest.client.processor.ClientInterfaceModel;
import dev.vertique.codegen.rest.client.processor.MethodModel;
import dev.vertique.codegen.rest.client.processor.ParamModel;
import dev.vertique.codegen.rest.client.processor.scan.BeanModel;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emitter that generates {@code {Client}_RestClientProxy} implementation classes for
 * {@code @RestClient}-annotated interfaces.
 *
 * <p>Each generated proxy:
 * <ul>
 *   <li>Is {@code public final} and implements the client interface.</li>
 *   <li>Holds static {@code Method} constants initialised in a class initializer, keyed per method
 *       overload (signature-precise, same as the runtime {@code Map<Method, ClientMethodMeta>} contract).</li>
 *   <li>Caches per-method {@code ClientMethodMeta} references in {@code final} fields, resolved
 *       from the constructor's {@code Map<Method, ClientMethodMeta>} argument.</li>
 *   <li>Caches one {@code BeanParamAccessor<T>} per referenced bean type.</li>
 *   <li>In each method implementation, uses the dispatcher's fluent API to assemble the request
 *       without any reflective field access.</li>
 * </ul>
 *
 * <p><strong>Package and name policy:</strong> the generated proxy is always emitted into the
 * <em>origin</em> package of the annotated interface (via
 * {@link dev.vertique.codegen.CodegenContext#packageNameOf}), never into any
 * {@code -Avertique.codegen.package} override. The class name is derived by
 * {@link Identifiers#generatedClassName} so that nested interfaces are flattened correctly
 * (e.g. {@code Outer.Inner} → {@code Outer_Inner_RestClientProxy}).
 *
 * <p>This policy is mandatory for runtime discovery: {@link dev.vertique.rest.client.RestClientBuilder}
 * locates the proxy via {@link dev.vertique.core.util.GeneratedNames#companionFqn}, which also uses
 * the origin package and {@code $}-to-{@code _} flattening. Emitting into an override package or
 * using {@code getSimpleName()} for nested interfaces would cause a {@code ClassNotFoundException}
 * at runtime and a silent fallback to the JDK reflective proxy.
 *
 * <p>Null-safety and {@code @DefaultValue} semantics:
 * <ul>
 *   <li>{@code QUERY}, {@code HEADER}, {@code COOKIE}: if the argument is {@code null} and a
 *       {@code @DefaultValue} is present, the default is used; if {@code null} and no default,
 *       the call is omitted (matches runtime behaviour).</li>
 *   <li>{@code PATH}: if the argument is {@code null} and no {@code @DefaultValue} is present, a
 *       {@link dev.vertique.rest.client.exception.RestClientException} is thrown with a clear message.
 *       The reflective path's {@code RestClientRequestFactory.collectParamsWithMeta} (and its
 *       {@code buildPath} counterpart) fail fast identically on a null required {@code PATH} param —
 *       both proxy paths reject the call at the same point rather than letting an unresolved
 *       {@code {placeholder}} reach the URI.</li>
 *   <li>{@code URL}: the argument is passed to
 *       {@link dev.vertique.rest.client.RestClientDispatcher#applyUrlParam}, which validates it
 *       (absolute, authority, host, http/https scheme, no fragment) and stores the resolved URI
 *       via {@link dev.vertique.rest.client.RestRequestBuilder#absoluteUri(String)}; a {@code null}
 *       argument is passed through unchanged so the dispatcher's URI-assembly step raises the
 *       clear-message {@link dev.vertique.rest.client.exception.RestClientException}.</li>
 *   <li>{@code BODY}: passed to {@link dev.vertique.rest.client.RestClientDispatcher#applyBody},
 *       which serializes the value according to the method's {@code @Consumes} media type
 *       (text/plain, octet-stream, or JSON); {@code null} means no body.</li>
 * </ul>
 *
 * <p>Constructor signature:
 * {@code (RestClientDispatcher, BeanParamAccessorRegistry, Map<Method, ClientMethodMeta>)}.
 * The {@link dev.vertique.rest.client.RestClientBuilder} uses this exact signature for the
 * generated-proxy selection path.
 */
public final class ProxyEmitter {

    // --- Well-known type names ---
    private static final ClassName REST_CLIENT_DISPATCHER =
            ClassName.get("dev.vertique.rest.client", "RestClientDispatcher");
    private static final ClassName BEAN_PARAM_ACCESSOR_REGISTRY =
            ClassName.get("dev.vertique.rest.client", "BeanParamAccessorRegistry");
    private static final ClassName BEAN_PARAM_ACCESSOR = ClassName.get("dev.vertique.rest.client", "BeanParamAccessor");
    private static final ClassName REST_REQUEST_BUILDER =
            ClassName.get("dev.vertique.rest.client", "RestRequestBuilder");
    private static final ClassName REST_CLIENT_EXCEPTION =
            ClassName.get("dev.vertique.rest.client.exception", "RestClientException");
    private static final ClassName CLIENT_METHOD_META =
            ClassName.get("dev.vertique.rest.client.meta", "ClientMethodMeta");
    private static final ClassName FUTURE = ClassName.get("io.vertx.core", "Future");
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.rest.client.processor.RestClientProcessor";

    private final CodegenContext ctx;

    /**
     * Creates a new emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ProxyEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the generated proxy class for the given interface model.
     *
     * @param model the client interface model to generate a proxy for
     * @param beanModelsByFqn pre-scanned bean models keyed by bean FQN; supplied by the processor
     *     so the proxy can emit per-field {@code accessor.extract(bean, name)} calls for every
     *     {@code @BeanParam} parameter. External (non-compilation-unit) bean types are absent from
     *     this map; their per-field expansion happens at runtime in the dispatcher.
     */
    public void emit(ClientInterfaceModel model, Map<String, BeanModel> beanModelsByFqn) {
        TypeElement clientType = model.clientType();
        // Always pin to the ORIGIN package — never the -Avertique.codegen.package override.
        // RestClientBuilder resolves the proxy via GeneratedNames.companionFqn (origin package,
        // '$' → '_') so the emitter must match that exact location. See class javadoc.
        String packageName = ctx.packageNameOf(clientType);
        // Flatten nested types: Outer.Inner → Outer_Inner_RestClientProxy.
        // Using getSimpleName() alone would produce Inner_RestClientProxy and miss the enclosing
        // chain, making GeneratedNames.companionFqn (Outer$Inner → Outer_Inner_...) unable to
        // find the class at runtime.
        String generatedSimpleName = Identifiers.generatedClassName(clientType, "_RestClientProxy");
        // ClassName.get(TypeElement) correctly models nested interfaces (Outer.Inner) so that
        // the addSuperinterface call and getDeclaredMethod references compile with the right type.
        ClassName clientClassName = ClassName.get(clientType);
        String generatedFqn = packageName + "." + generatedSimpleName;

        // Collect unique bean types referenced by BEAN params (deduped by type FQN)
        Map<String, ClassName> beanTypesByFqn = collectBeanTypes(model);

        // Build the class spec
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(clientClassName)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addJavadoc(
                        "Generated static proxy for {@link $T}.\n\n"
                                + "<p>This class is generated by {@code $L} and provides zero-reflection dispatch\n"
                                + "for all methods of {@link $T}. It is selected at runtime by\n"
                                + "{@link dev.vertique.rest.client.RestClientBuilder#build(Class)} when this class\n"
                                + "is on the classpath.\n",
                        clientClassName,
                        PROCESSOR_FQN,
                        clientClassName);

        // --- Static Method constants ---
        List<FieldSpec> methodFields = new ArrayList<>();
        CodeBlock.Builder staticInit = CodeBlock.builder();
        staticInit.beginControlFlow("try");
        int methodIndex = 0;
        for (MethodModel method : model.methods()) {
            String constantName =
                    "M_" + method.method().getSimpleName().toString().toUpperCase() + "_" + methodIndex;
            FieldSpec methodField = FieldSpec.builder(
                            Method.class, constantName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc(
                            "Static {@link Method} constant for {@code $L}.",
                            method.method().getSimpleName())
                    .build();
            methodFields.add(methodField);

            // Build the getDeclaredMethod call
            // Use erased (raw) types for getDeclaredMethod — you cannot use .class on parameterized types
            List<VariableElement> params = new ArrayList<>(method.method().getParameters());
            CodeBlock.Builder getDeclaredMethod = CodeBlock.builder();
            getDeclaredMethod.add(
                    "$L = $T.class.getDeclaredMethod($S",
                    constantName,
                    clientClassName,
                    method.method().getSimpleName().toString());
            for (VariableElement param : params) {
                TypeName paramTypeName = resolveErasedTypeName(param.asType());
                getDeclaredMethod.add(", $T.class", paramTypeName);
            }
            getDeclaredMethod.add(")");
            staticInit.addStatement(getDeclaredMethod.build());
            methodIndex++;
        }
        staticInit.nextControlFlow("catch ($T e)", NoSuchMethodException.class);
        staticInit.addStatement("throw new $T(e)", ExceptionInInitializerError.class);
        staticInit.endControlFlow();

        for (FieldSpec f : methodFields) {
            classBuilder.addField(f);
        }
        classBuilder.addStaticBlock(staticInit.build());

        // --- Instance fields ---
        classBuilder.addField(FieldSpec.builder(REST_CLIENT_DISPATCHER, "dispatcher", Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("The shared dispatch pipeline.")
                .build());

        // One BeanParamAccessor field per unique bean type
        for (Map.Entry<String, ClassName> entry : beanTypesByFqn.entrySet()) {
            ClassName beanClassName = entry.getValue();
            String accessorFieldName = beanAccessorFieldName(beanClassName.simpleName());
            ParameterizedTypeName accessorType = ParameterizedTypeName.get(BEAN_PARAM_ACCESSOR, beanClassName);
            classBuilder.addField(FieldSpec.builder(accessorType, accessorFieldName, Modifier.PRIVATE, Modifier.FINAL)
                    .addJavadoc("Cached accessor for {@link $T} {@code @BeanParam} fields.", beanClassName)
                    .build());
        }

        // One ClientMethodMeta field per method
        methodIndex = 0;
        for (MethodModel method : model.methods()) {
            String metaFieldName =
                    "meta" + capitalise(method.method().getSimpleName().toString()) + "_" + methodIndex;
            classBuilder.addField(FieldSpec.builder(CLIENT_METHOD_META, metaFieldName, Modifier.PRIVATE, Modifier.FINAL)
                    .addJavadoc(
                            "Cached method metadata for {@code $L}.",
                            method.method().getSimpleName())
                    .build());
            methodIndex++;
        }

        // Collect method meta field names in order (parallel to methods list)
        List<String> metaFieldNames = new ArrayList<>();
        methodIndex = 0;
        for (MethodModel method : model.methods()) {
            metaFieldNames.add(
                    "meta" + capitalise(method.method().getSimpleName().toString()) + "_" + methodIndex);
            methodIndex++;
        }

        // --- Constructor ---
        classBuilder.addMethod(buildConstructor(model, beanTypesByFqn, methodFields, metaFieldNames));

        // --- Method implementations ---
        methodIndex = 0;
        for (MethodModel method : model.methods()) {
            String metaFieldName = metaFieldNames.get(methodIndex);
            classBuilder.addMethod(buildMethodImpl(method, metaFieldName, beanTypesByFqn, beanModelsByFqn));
            methodIndex++;
        }

        TypeSpec typeSpec = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();

        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(null, "Failed to write generated source file '%s': %s", generatedFqn, e.getMessage());
        }
    }

    // --- Constructor ---

    /**
     * Builds the constructor for the generated proxy.
     *
     * @param model the interface model
     * @param beanTypesByFqn map of bean FQN to ClassName
     * @param methodFields the static Method constant field specs
     * @param metaFieldNames the names of the cached meta fields (parallel to methods)
     * @return the constructor MethodSpec
     */
    private MethodSpec buildConstructor(
            ClientInterfaceModel model,
            Map<String, ClassName> beanTypesByFqn,
            List<FieldSpec> methodFields,
            List<String> metaFieldNames) {
        ParameterizedTypeName methodMetaMap =
                ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(Method.class), CLIENT_METHOD_META);

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(REST_CLIENT_DISPATCHER, "dispatcher")
                        .build())
                .addParameter(ParameterSpec.builder(BEAN_PARAM_ACCESSOR_REGISTRY, "registry")
                        .build())
                .addParameter(
                        ParameterSpec.builder(methodMetaMap, "methodMetas").build())
                .addJavadoc(
                        "Constructs the generated proxy with its dispatch dependencies.\n\n"
                                + "@param dispatcher the shared HTTP dispatch pipeline\n"
                                + "@param registry the accessor registry for resolving {@code @BeanParam} accessors\n"
                                + "@param methodMetas the pre-built map from {@link Method} to\n"
                                + "    {@link $T} for all methods in this interface\n",
                        CLIENT_METHOD_META);

        ctor.addStatement("this.dispatcher = dispatcher");

        // Resolve bean accessors
        for (Map.Entry<String, ClassName> entry : beanTypesByFqn.entrySet()) {
            ClassName beanClassName = entry.getValue();
            String fieldName = beanAccessorFieldName(beanClassName.simpleName());
            ctor.addStatement("this.$L = registry.resolve($T.class)", fieldName, beanClassName);
        }

        // Cache method metas
        int methodIndex = 0;
        for (MethodModel method : model.methods()) {
            String metaFieldName = metaFieldNames.get(methodIndex);
            ctor.addStatement(
                    "this.$L = methodMetas.get($L)",
                    metaFieldName,
                    methodFields.get(methodIndex).name());
            methodIndex++;
        }

        return ctor.build();
    }

    // --- Method implementation ---

    /**
     * Builds the implementation of a single interface method.
     *
     * @param method the method model
     * @param metaFieldName the name of the cached meta field for this method
     * @param beanTypesByFqn map of bean FQN to ClassName (for accessor field name lookup)
     * @param beanModelsByFqn pre-scanned bean models keyed by bean FQN
     * @return the method spec
     */
    private MethodSpec buildMethodImpl(
            MethodModel method,
            String metaFieldName,
            Map<String, ClassName> beanTypesByFqn,
            Map<String, BeanModel> beanModelsByFqn) {
        TypeName returnTypeName = resolveTypeName(method.returnType());

        MethodSpec.Builder builder = MethodSpec.methodBuilder(
                        method.method().getSimpleName().toString())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnTypeName)
                .addJavadoc(
                        "Generated dispatch for {@code $L}.\n\n"
                                + "@return a {@link io.vertx.core.Future} completing with the deserialized response\n",
                        method.method().getSimpleName());

        // Add parameters
        for (VariableElement param : method.method().getParameters()) {
            TypeName paramTypeName = resolveTypeName(param.asType());
            builder.addParameter(
                    ParameterSpec.builder(paramTypeName, param.getSimpleName().toString())
                            .build());
        }

        // Use a mangled local variable name to avoid colliding with user-supplied parameter names.
        // (e.g. a @BeanParam parameter named "req" would clash with the obvious "req" choice.)
        builder.addStatement("$T _req_ = dispatcher.newRequest($L)", REST_REQUEST_BUILDER, metaFieldName);

        for (ParamModel param : method.params()) {
            switch (param.kind()) {
                case PATH -> emitPathParam(builder, param, metaFieldName);
                case QUERY -> emitQueryParam(builder, param, metaFieldName);
                case HEADER -> emitHeaderParam(builder, param, metaFieldName);
                case COOKIE -> emitCookieParam(builder, param, metaFieldName);
                case URL ->
                    // Route through dispatcher.applyUrlParam so the generated proxy shares the
                    // exact same @Url validation rules (absolute, authority, host, scheme,
                    // fragment) as the JDK reflective proxy path. A null value is passed through
                    // unchanged — the dispatcher defers the null-argument error to assembleUri,
                    // matching the established null-deferral contract.
                    builder.addStatement(
                            "_req_ = dispatcher.applyUrlParam(_req_, $L, $L)",
                            metaFieldName,
                            param.element().getSimpleName());
                case BEAN -> emitBeanParam(builder, param, metaFieldName, beanTypesByFqn, beanModelsByFqn);
                case BODY ->
                    // Route through dispatcher.applyBody so the generated proxy respects
                    // @Consumes media-type branching (text/plain, octet-stream, JSON) exactly
                    // as the JDK reflective proxy path does.
                    builder.addStatement(
                            "_req_ = dispatcher.applyBody(_req_, $L, $L)",
                            metaFieldName,
                            param.element().getSimpleName());
                default -> throw new IllegalStateException("Unhandled ParamModel.Kind: " + param.kind());
            }
        }

        builder.addStatement("return dispatcher.send(_req_, $L)", metaFieldName);
        return builder.build();
    }

    // --- Per-kind emit helpers ---

    /**
     * Emits the path parameter assignment via {@code dispatcher.applyPathParam}. When the argument
     * is {@code null} and no {@code @DefaultValue} is present, a null-check guard throws
     * {@link dev.vertique.rest.client.exception.RestClientException} to fail fast.
     *
     * @param builder the method body builder
     * @param param the path parameter model
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitPathParam(MethodSpec.Builder builder, ParamModel param, String metaFieldName) {
        String argName = param.element().getSimpleName().toString();
        emitPathParamCall(builder, param.name(), param.defaultValue(), argName, metaFieldName);
    }

    /**
     * Emits the query parameter assignment via {@code dispatcher.applyQueryParam} with optional
     * {@code @DefaultValue}.
     *
     * @param builder the method body builder
     * @param param the query parameter model
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitQueryParam(MethodSpec.Builder builder, ParamModel param, String metaFieldName) {
        String argName = param.element().getSimpleName().toString();
        emitQueryParamCall(builder, param.name(), param.defaultValue(), argName, metaFieldName);
    }

    /**
     * Emits the header parameter assignment via {@code dispatcher.applyHeaderParam} with optional
     * {@code @DefaultValue}.
     *
     * @param builder the method body builder
     * @param param the header parameter model
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitHeaderParam(MethodSpec.Builder builder, ParamModel param, String metaFieldName) {
        String argName = param.element().getSimpleName().toString();
        emitHeaderParamCall(builder, param.name(), param.defaultValue(), argName, metaFieldName);
    }

    /**
     * Emits the cookie parameter assignment via {@code dispatcher.applyCookieParam} with optional
     * {@code @DefaultValue}.
     *
     * @param builder the method body builder
     * @param param the cookie parameter model
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitCookieParam(MethodSpec.Builder builder, ParamModel param, String metaFieldName) {
        String argName = param.element().getSimpleName().toString();
        emitCookieParamCall(builder, param.name(), param.defaultValue(), argName, metaFieldName);
    }

    // --- Shared guarded emission helpers (used by top-level and bean-field cases) ---

    /**
     * Emits a {@code dispatcher.applyPathParam} call for the given value expression. Thin wrapper
     * over {@link #emitApplyParamCall} with {@code failFastOnNull = true}: when the value is
     * {@code null} and no default is present, the proxy itself throws
     * {@link dev.vertique.rest.client.exception.RestClientException} before the dispatcher call
     * (path params must fail fast — see {@link #emitApplyParamCall}).
     *
     * @param builder the method body builder
     * @param paramName the JAX-RS path parameter name
     * @param defaultValue the {@code @DefaultValue} string, or {@code null}
     * @param valueExpr the Java expression that produces the value (a variable name or accessor call)
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitPathParamCall(
            MethodSpec.Builder builder, String paramName, String defaultValue, String valueExpr, String metaFieldName) {
        emitApplyParamCall(builder, "applyPathParam", paramName, defaultValue, valueExpr, metaFieldName, true);
    }

    /**
     * Emits a {@code dispatcher.applyQueryParam} call for the given value expression. The dispatcher
     * handles collection expansion (element-by-element) and null/default semantics.
     *
     * @param builder the method body builder
     * @param paramName the JAX-RS query parameter name
     * @param defaultValue the {@code @DefaultValue} string, or {@code null}
     * @param valueExpr the Java expression that produces the value
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitQueryParamCall(
            MethodSpec.Builder builder, String paramName, String defaultValue, String valueExpr, String metaFieldName) {
        emitApplyParamCall(builder, "applyQueryParam", paramName, defaultValue, valueExpr, metaFieldName, false);
    }

    /**
     * Emits a {@code dispatcher.applyHeaderParam} call for the given value expression. The
     * dispatcher handles null/default semantics and serializes via the
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver} — no {@code String.valueOf}
     * at the proxy layer.
     *
     * @param builder the method body builder
     * @param paramName the HTTP header name
     * @param defaultValue the {@code @DefaultValue} string, or {@code null}
     * @param valueExpr the Java expression that produces the value
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitHeaderParamCall(
            MethodSpec.Builder builder, String paramName, String defaultValue, String valueExpr, String metaFieldName) {
        emitApplyParamCall(builder, "applyHeaderParam", paramName, defaultValue, valueExpr, metaFieldName, false);
    }

    /**
     * Emits a {@code dispatcher.applyCookieParam} call for the given value expression. The
     * dispatcher handles null/default semantics and serializes via the
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver} — no {@code String.valueOf}
     * at the proxy layer.
     *
     * @param builder the method body builder
     * @param paramName the cookie name
     * @param defaultValue the {@code @DefaultValue} string, or {@code null}
     * @param valueExpr the Java expression that produces the value
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     */
    private void emitCookieParamCall(
            MethodSpec.Builder builder, String paramName, String defaultValue, String valueExpr, String metaFieldName) {
        emitApplyParamCall(builder, "applyCookieParam", paramName, defaultValue, valueExpr, metaFieldName, false);
    }

    /**
     * Emits a {@code dispatcher.<dispatcherMethodName>(...)} call for the given value expression,
     * shared by {@link #emitPathParamCall}, {@link #emitQueryParamCall}, {@link #emitHeaderParamCall},
     * and {@link #emitCookieParamCall} — these four call shapes are byte-for-byte identical apart
     * from the dispatcher method name and the path-only fail-fast null guard.
     *
     * <p>Passes the raw typed value to the dispatcher rather than calling {@code .toString()} —
     * the dispatcher serializes via the {@link dev.vertique.rest.core.convert.ParamConversionResolver}.
     *
     * <p>When {@code failFastOnNull} is {@code true} (path params only) and no {@code defaultValue}
     * is present, a null-check guard is emitted that throws
     * {@link dev.vertique.rest.client.exception.RestClientException} before the dispatcher call —
     * a null path param would otherwise leave an unresolved {@code {placeholder}} in the URI.
     *
     * @param builder the method body builder
     * @param dispatcherMethodName the {@code RestClientDispatcher} method to call (e.g.
     *     {@code "applyPathParam"})
     * @param paramName the JAX-RS wire name of the parameter
     * @param defaultValue the {@code @DefaultValue} string, or {@code null}
     * @param valueExpr the Java expression that produces the value (a variable name or accessor call)
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     * @param failFastOnNull {@code true} to emit a null-check guard that throws
     *     {@link dev.vertique.rest.client.exception.RestClientException} before the dispatcher call
     *     when {@code defaultValue} is {@code null}; {@code false} to let the dispatcher silently
     *     omit the call
     */
    private void emitApplyParamCall(
            MethodSpec.Builder builder,
            String dispatcherMethodName,
            String paramName,
            String defaultValue,
            String valueExpr,
            String metaFieldName,
            boolean failFastOnNull) {
        if (defaultValue != null) {
            builder.addStatement(
                    "_req_ = dispatcher.$L(_req_, $L, $S, $L, $S)",
                    dispatcherMethodName,
                    metaFieldName,
                    paramName,
                    valueExpr,
                    defaultValue);
        } else {
            if (failFastOnNull) {
                // Null path params must fail fast — delegate the null-guard + exception to the apply
                // method. The dispatcher throws RestClientException when value is null and defaultValue
                // is null.
                builder.beginControlFlow("if ($L == null)", valueExpr)
                        .addStatement(
                                "throw new $T($S)",
                                REST_CLIENT_EXCEPTION,
                                "path param '" + paramName + "' was null and has no @DefaultValue")
                        .endControlFlow();
            }
            builder.addStatement(
                    "_req_ = dispatcher.$L(_req_, $L, $S, $L, null)",
                    dispatcherMethodName,
                    metaFieldName,
                    paramName,
                    valueExpr);
        }
    }

    /**
     * Emits the {@code @BeanParam} expansion using the cached accessor, routing each field through
     * the dispatcher's {@code applyXxxParam} methods for typed serialization.
     *
     * @param builder the method body builder
     * @param param the bean parameter model
     * @param metaFieldName the name of the cached {@code ClientMethodMeta} field for this method
     * @param beanTypesByFqn map of bean FQN to ClassName
     * @param beanModelsByFqn pre-scanned bean models keyed by bean FQN
     */
    private void emitBeanParam(
            MethodSpec.Builder builder,
            ParamModel param,
            String metaFieldName,
            Map<String, ClassName> beanTypesByFqn,
            Map<String, BeanModel> beanModelsByFqn) {
        ClassName beanClass = resolveClassName(param.type());
        if (beanClass == null) {
            return;
        }
        String accessorField = beanAccessorFieldName(beanClass.simpleName());
        String beanVarName = param.element().getSimpleName().toString();
        BeanModel beanModel = beanModelsByFqn.get(beanClass.canonicalName());
        // External-bean case is filtered out at the interface level by RestClientProcessor
        // (see canEmitProxy). If we reach here without a bean model, that's an invariant
        // violation, not a runtime concern.
        if (beanModel == null) {
            throw new IllegalStateException(
                    "Internal: missing BeanModel for in-compilation bean " + beanClass.canonicalName());
        }
        // Inline per-field expansion using the cached accessor.
        // The value expression uses the accessor to extract the field value from the bean.
        // IMPORTANT: extract() is keyed by Java member name (javaName), not JAX-RS wire name (name).
        // The dispatcher apply calls use the JAX-RS wire name (name) for the param lookup in meta.
        for (ParamModel field : beanModel.fields()) {
            // Pass javaName to extract() — the accessor switch is keyed by Java member name
            String valueExpr = accessorField + ".extract(" + beanVarName + ", \"" + field.javaName() + "\")";
            switch (field.kind()) {
                // Use field.name() (JAX-RS wire name) for the dispatcher apply call
                case PATH -> emitPathParamCall(builder, field.name(), field.defaultValue(), valueExpr, metaFieldName);
                case QUERY -> emitQueryParamCall(builder, field.name(), field.defaultValue(), valueExpr, metaFieldName);
                case HEADER ->
                    emitHeaderParamCall(builder, field.name(), field.defaultValue(), valueExpr, metaFieldName);
                case COOKIE ->
                    emitCookieParamCall(builder, field.name(), field.defaultValue(), valueExpr, metaFieldName);
                default -> {
                    // BEAN-within-BEAN, URL, and BODY are not valid on bean fields; ignore.
                }
            }
        }
    }

    // --- Helper utilities ---

    /**
     * Collects all unique bean types referenced via {@code @BeanParam} across all methods.
     * Keyed by the bean type's fully-qualified name.
     *
     * <p>Uses {@link TypeName#get(TypeMirror)} to derive the ClassName, which correctly handles
     * nested/inner classes (e.g. {@code Outer.Inner}) by preserving the nesting structure in
     * the generated import and reference.
     *
     * @param model the interface model to scan
     * @return map from bean FQN to its ClassName
     */
    private Map<String, ClassName> collectBeanTypes(ClientInterfaceModel model) {
        Map<String, ClassName> result = new LinkedHashMap<>();
        for (TypeElement beanType : model.referencedBeans()) {
            String fqn = beanType.getQualifiedName().toString();
            if (!result.containsKey(fqn)) {
                ClassName cn = resolveClassName(beanType.asType());
                if (cn != null) {
                    result.put(fqn, cn);
                }
            }
        }
        return result;
    }

    /**
     * Resolves a {@link TypeMirror} to a JavaPoet {@link TypeName}.
     *
     * @param type the type mirror to resolve
     * @return the corresponding TypeName
     */
    private TypeName resolveTypeName(TypeMirror type) {
        try {
            return TypeName.get(type);
        } catch (Exception e) {
            return ClassName.OBJECT;
        }
    }

    /**
     * Resolves a {@link TypeMirror} to its erased (raw) {@link TypeName}, suitable for use with
     * {@code .class} literals in {@code getDeclaredMethod} calls.
     *
     * <p>Parameterized types like {@code MultivaluedMap<String,String>} are erased to their raw
     * form ({@code MultivaluedMap}), since {@code Foo<Bar>.class} is not valid Java.
     *
     * @param type the type mirror to erase and resolve
     * @return the erased TypeName; falls back to {@code Object.class} on resolution failure
     */
    private TypeName resolveErasedTypeName(TypeMirror type) {
        try {
            TypeMirror erased = ctx.types().erasure(type);
            return TypeName.get(erased);
        } catch (Exception e) {
            return ClassName.OBJECT;
        }
    }

    /**
     * Attempts to resolve a {@link TypeMirror} to a {@link ClassName} (for declared types only).
     *
     * <p>Uses JavaPoet's {@link TypeName#get(TypeMirror)} to derive the name, which correctly
     * handles nested/inner classes by preserving the nesting structure. If the result is a
     * {@link ParameterizedTypeName}, returns its raw type to get the bare class name.
     *
     * @param type the type mirror to resolve
     * @return the ClassName, or {@code null} if not a declared type or resolution fails
     */
    private ClassName resolveClassName(TypeMirror type) {
        try {
            TypeMirror erased = ctx.types().erasure(type);
            TypeName typeName = TypeName.get(erased);
            if (typeName instanceof ClassName cn) {
                return cn;
            }
        } catch (Exception e) {
            // Fall through to null
        }
        return null;
    }

    /**
     * Derives the accessor field name from a bean type's simple name.
     * {@code PageRequest} &rarr; {@code pageRequestAccessor}.
     *
     * @param simpleName the bean's simple name
     * @return the field name
     */
    private String beanAccessorFieldName(String simpleName) {
        if (simpleName.isEmpty()) return "accessor";
        char first = Character.toLowerCase(simpleName.charAt(0));
        return first + simpleName.substring(1) + "Accessor";
    }

    /**
     * Capitalises the first character of a string.
     *
     * @param s the string to capitalise
     * @return the capitalised string
     */
    private String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
