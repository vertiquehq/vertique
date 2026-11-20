// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.rest.client.processor.ClientInterfaceModel;
import dev.vertique.codegen.rest.client.processor.MethodModel;
import dev.vertique.codegen.rest.client.processor.ParamModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side scanner that mirrors the runtime {@code ClientInterfaceScanner} to produce
 * {@link ClientInterfaceModel} instances from {@code @RestClient}-annotated interfaces.
 *
 * <p>This scanner operates on APT model types ({@link TypeMirror}, {@link Element}) rather than
 * runtime reflection types ({@code Class<?>}, {@code Field}) &mdash; the two are intentionally
 * separate because the type models are incompatible.
 *
 * <p>Discovery rules (mirror the runtime scanner at
 * {@code vertique-rest-client/.../meta/ClientInterfaceScanner.java}):
 * <ul>
 *   <li>Class-level {@code @Path} value becomes the base path.</li>
 *   <li>Each non-{@code default} interface method is scanned for an HTTP verb.</li>
 *   <li>Method-level {@code @Path} value is the path suffix.</li>
 *   <li>Parameters are classified by their JAX-RS annotations; {@code @Url} is recognised
 *       <em>first</em> (before the JAX-RS annotation cascade) and its constraints are validated at
 *       compile time via {@link dev.vertique.codegen.Diagnostics#error}:
 *       <ul>
 *         <li>Type must be {@code java.net.URI}.</li>
 *         <li>{@code @DefaultValue} is not allowed.</li>
 *         <li>No other JAX-RS annotation may appear on the same parameter.</li>
 *         <li>At most one {@code @Url} parameter per method.</li>
 *         <li>No method-level {@code @Path} annotation on the method.</li>
 *         <li>No {@code @PathParam} parameters (top-level or inside {@code @BeanParam}) on the
 *             same method.</li>
 *       </ul></li>
 *   <li>{@code @DefaultValue} is read for each parameter and stored in
 *       {@link ParamModel#defaultValue()}.</li>
 *   <li>{@code @BeanParam} parameters cause the bean type to be added to
 *       {@link ClientInterfaceModel#referencedBeans()}.</li>
 * </ul>
 */
public final class ClientInterfaceScanner {

    // --- JAX-RS annotation FQNs ---
    private static final String PATH_FQN = "jakarta.ws.rs.Path";
    private static final String GET_FQN = "jakarta.ws.rs.GET";
    private static final String POST_FQN = "jakarta.ws.rs.POST";
    private static final String PUT_FQN = "jakarta.ws.rs.PUT";
    private static final String DELETE_FQN = "jakarta.ws.rs.DELETE";
    private static final String PATCH_FQN = "jakarta.ws.rs.PATCH";
    private static final String HEAD_FQN = "jakarta.ws.rs.HEAD";
    private static final String PATH_PARAM_FQN = "jakarta.ws.rs.PathParam";
    private static final String QUERY_PARAM_FQN = "jakarta.ws.rs.QueryParam";
    private static final String HEADER_PARAM_FQN = "jakarta.ws.rs.HeaderParam";
    private static final String COOKIE_PARAM_FQN = "jakarta.ws.rs.CookieParam";
    private static final String BEAN_PARAM_FQN = "jakarta.ws.rs.BeanParam";
    private static final String DEFAULT_VALUE_FQN = "jakarta.ws.rs.DefaultValue";

    /**
     * All JAX-RS parameter-binding annotation FQNs that are mutually exclusive with {@code @Url}.
     * Used by {@link #validateUrlParameter} to detect illegal combinations.
     */
    private static final List<String> JAX_RS_PARAM_ANNOTATIONS =
            List.of(PATH_PARAM_FQN, QUERY_PARAM_FQN, HEADER_PARAM_FQN, COOKIE_PARAM_FQN, BEAN_PARAM_FQN);

    // --- Vertique annotation FQNs ---
    private static final String URL_FQN = "dev.vertique.rest.client.Url";

    private final CodegenContext ctx;
    private final BeanParamScanner beanParamScanner;

    /**
     * Creates a new scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ClientInterfaceScanner(CodegenContext ctx) {
        this.ctx = ctx;
        this.beanParamScanner = new BeanParamScanner(ctx);
    }

    /**
     * Scans the given interface type element and returns a {@link ClientInterfaceModel}.
     *
     * <p>Only non-{@code default} methods carrying a supported HTTP verb annotation are included
     * in the model. Methods without a verb annotation (including {@code @OPTIONS}) are silently
     * skipped &mdash; they will be caught by the
     * {@link dev.vertique.codegen.rest.client.processor.validate.HttpVerbValidator}.
     *
     * @param clientType the type element for the {@code @RestClient} interface
     * @return the interface model; never {@code null}
     */
    public ClientInterfaceModel scan(TypeElement clientType) {
        String basePath = resolvePathValue(clientType);
        boolean interfaceHasPath = AnnotationMirrors.isPresent(clientType, PATH_FQN);
        List<MethodModel> methods = new ArrayList<>();
        Set<TypeElement> referencedBeans = new LinkedHashSet<>();

        for (ClientMethods.ClientMethod operation : ClientMethods.collect(ctx, clientType)) {
            ExecutableElement method = operation.method();
            List<ExecutableElement> declarations = operation.declarations();

            String httpVerb = resolveHttpVerb(declarations);
            if (httpVerb == null) {
                // Validator will catch this; scanner just skips
                continue;
            }

            String pathSuffix = resolvePathValue(declarations);
            List<ParamModel> params = resolveParams(operation, referencedBeans);

            // Post-classify: validate @Url method-level constraints
            validateUrlMethodConstraints(clientType, method, declarations, params, interfaceHasPath);

            TypeMirror returnType = operation.type().getReturnType();
            TypeMirror futureValueType = ctx.unwrapFuture(returnType);

            methods.add(new MethodModel(method, httpVerb, pathSuffix, params, returnType, futureValueType));
        }

        return new ClientInterfaceModel(clientType, basePath, List.copyOf(methods), Set.copyOf(referencedBeans));
    }

    // --- HTTP verb resolution ---

    /**
     * Resolves the HTTP verb for an operation by checking the supported JAX-RS verb annotations on
     * the method and then on the declarations it overrides; the nearest declaration that carries a
     * verb decides. {@code @OPTIONS} is intentionally not supported (mirrors the runtime scanner).
     *
     * @param declarations the method followed by the declarations it overrides
     * @return the verb string (e.g. {@code "GET"}), or {@code null} if no supported verb is present
     */
    private String resolveHttpVerb(List<ExecutableElement> declarations) {
        for (ExecutableElement declaration : declarations) {
            if (AnnotationMirrors.isPresent(declaration, GET_FQN)) return "GET";
            if (AnnotationMirrors.isPresent(declaration, POST_FQN)) return "POST";
            if (AnnotationMirrors.isPresent(declaration, PUT_FQN)) return "PUT";
            if (AnnotationMirrors.isPresent(declaration, DELETE_FQN)) return "DELETE";
            if (AnnotationMirrors.isPresent(declaration, PATCH_FQN)) return "PATCH";
            if (AnnotationMirrors.isPresent(declaration, HEAD_FQN)) return "HEAD";
        }
        return null;
    }

    // --- Path resolution ---

    /**
     * Reads the {@code value} attribute of the {@code @Path} annotation on the given element.
     *
     * @param element the element to inspect (type or method)
     * @return the path value, or empty string if {@code @Path} is absent
     */
    private String resolvePathValue(List<? extends Element> elements) {
        for (Element element : elements) {
            if (AnnotationMirrors.isPresent(element, PATH_FQN)) {
                return resolvePathValue(element);
            }
        }
        return "";
    }

    /**
     * Reads the {@code value} attribute of the {@code @Path} annotation on one element.
     *
     * @param element the element to inspect
     * @return the path value, or empty string if {@code @Path} is absent
     */
    private String resolvePathValue(Element element) {
        Optional<AnnotationMirror> mirror = AnnotationMirrors.findByFqn(element, PATH_FQN);
        if (mirror.isEmpty()) {
            return "";
        }
        return ctx.annotations().attribute(mirror.get(), "value", String.class).orElse("");
    }

    // --- Parameter resolution ---

    /**
     * Resolves the parameter list for an operation. {@code @BeanParam} parameters cause the bean
     * type to be recorded in {@code referencedBeans}. A parameter's annotations are those of the same
     * parameter of the method and of every declaration it overrides, and its type is the one the
     * interface's hierarchy binds.
     *
     * @param operation the operation to inspect
     * @param referencedBeans accumulator for bean types encountered in {@code @BeanParam} params
     * @return the ordered list of parameter models
     */
    private List<ParamModel> resolveParams(ClientMethods.ClientMethod operation, Set<TypeElement> referencedBeans) {
        List<ParamModel> params = new ArrayList<>();
        List<? extends VariableElement> parameters = operation.method().getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            int index = i;
            List<VariableElement> sources = operation.declarations().stream()
                    .map(declaration ->
                            (VariableElement) declaration.getParameters().get(index))
                    .toList();
            params.add(classifyParam(
                    parameters.get(i),
                    sources,
                    operation.type().getParameterTypes().get(i),
                    referencedBeans));
        }
        return params;
    }

    /**
     * Classifies a single method parameter by inspecting its annotations.
     *
     * <p>Classification order (first match wins):
     * <ol>
     *   <li>{@code @Url} &rarr; {@link ParamModel.Kind#URL} (checked <em>first</em> — validated
     *       for type, mutual exclusivity with JAX-RS annotations, and absence of
     *       {@code @DefaultValue}; errors emitted via {@link dev.vertique.codegen.Diagnostics})</li>
     *   <li>{@code @PathParam} &rarr; {@link ParamModel.Kind#PATH}</li>
     *   <li>{@code @QueryParam} &rarr; {@link ParamModel.Kind#QUERY}</li>
     *   <li>{@code @HeaderParam} &rarr; {@link ParamModel.Kind#HEADER}</li>
     *   <li>{@code @CookieParam} &rarr; {@link ParamModel.Kind#COOKIE}</li>
     *   <li>{@code @BeanParam} &rarr; {@link ParamModel.Kind#BEAN}</li>
     *   <li>No annotation &rarr; {@link ParamModel.Kind#BODY}</li>
     * </ol>
     *
     * <p>{@code @DefaultValue} is read from the parameter regardless of kind, and stored in
     * {@link ParamModel#defaultValue()}.
     *
     * @param param the parameter element of the method the proxy implements
     * @param sources the same parameter of the method and of each declaration it overrides
     * @param type the parameter type as a member of the client interface
     * @param referencedBeans accumulator for bean types
     * @return the classified parameter model
     */
    private ParamModel classifyParam(
            VariableElement param, List<VariableElement> sources, TypeMirror type, Set<TypeElement> referencedBeans) {
        String defaultValue = resolveDefaultValue(sources);

        // javaName is always the Java parameter identifier
        String javaName = param.getSimpleName().toString();

        // @Url must be checked first — it is mutually exclusive with all JAX-RS annotations
        if (isPresentOnAny(sources, URL_FQN)) {
            validateUrlParameter(param, sources, type);
            // @DefaultValue on @Url is forbidden; null is always used regardless of any default
            return new ParamModel(ParamModel.Kind.URL, javaName, javaName, type, param, null, null);
        }

        if (isPresentOnAny(sources, PATH_PARAM_FQN)) {
            String name = literalAnnotationValue(sources, PATH_PARAM_FQN);
            return new ParamModel(ParamModel.Kind.PATH, name, javaName, type, param, null, defaultValue);
        }
        if (isPresentOnAny(sources, QUERY_PARAM_FQN)) {
            String name = literalAnnotationValue(sources, QUERY_PARAM_FQN);
            return new ParamModel(ParamModel.Kind.QUERY, name, javaName, type, param, null, defaultValue);
        }
        if (isPresentOnAny(sources, HEADER_PARAM_FQN)) {
            String name = literalAnnotationValue(sources, HEADER_PARAM_FQN);
            return new ParamModel(ParamModel.Kind.HEADER, name, javaName, type, param, null, defaultValue);
        }
        if (isPresentOnAny(sources, COOKIE_PARAM_FQN)) {
            String name = literalAnnotationValue(sources, COOKIE_PARAM_FQN);
            return new ParamModel(ParamModel.Kind.COOKIE, name, javaName, type, param, null, defaultValue);
        }
        if (isPresentOnAny(sources, BEAN_PARAM_FQN)) {
            // Record the bean type for accessor emission
            var element = ctx.types().asElement(type);
            if (element instanceof TypeElement te) {
                referencedBeans.add(te);
            }
            return new ParamModel(ParamModel.Kind.BEAN, javaName, javaName, type, param, null, null);
        }
        // No JAX-RS or @Url annotation — treat as body
        return new ParamModel(ParamModel.Kind.BODY, javaName, javaName, type, param, null, defaultValue);
    }

    /**
     * Validates per-parameter {@code @Url} constraints and emits compiler errors for violations.
     *
     * <p>Constraints:
     * <ul>
     *   <li>The parameter type must be {@code java.net.URI}.</li>
     *   <li>{@code @DefaultValue} is not allowed on {@code @Url} parameters.</li>
     *   <li>No other JAX-RS parameter-binding annotation may appear on the same parameter.</li>
     * </ul>
     *
     * @param param the {@code @Url}-annotated parameter element, where diagnostics are reported
     * @param sources the same parameter of the method and of each declaration it overrides
     * @param type the parameter's type mirror
     */
    private void validateUrlParameter(VariableElement param, List<VariableElement> sources, TypeMirror type) {
        // Type must be java.net.URI
        String typeFqn = type.toString();
        if (!"java.net.URI".equals(typeFqn)) {
            ctx.diagnostics().error(param, "@Url parameter must be java.net.URI, found: %s", typeFqn);
        }
        // @DefaultValue is forbidden on @Url
        if (isPresentOnAny(sources, DEFAULT_VALUE_FQN)) {
            ctx.diagnostics().error(param, "@DefaultValue is not allowed on @Url parameters");
        }
        // No other JAX-RS parameter-binding annotation on the same parameter
        for (String jaxRsAnnotationFqn : JAX_RS_PARAM_ANNOTATIONS) {
            if (isPresentOnAny(sources, jaxRsAnnotationFqn)) {
                String simpleName = jaxRsAnnotationFqn.substring(jaxRsAnnotationFqn.lastIndexOf('.') + 1);
                ctx.diagnostics().error(param, "@Url is mutually exclusive with @%s on the same parameter", simpleName);
            }
        }
    }

    /**
     * Validates method-level {@code @Url} constraints after parameter classification.
     *
     * <p>Mirrors the runtime {@code validateUrlParam} in
     * {@link dev.vertique.rest.client.meta.ClientInterfaceScanner}. Constraints:
     * <ul>
     *   <li>At most one {@code @Url} parameter per method.</li>
     *   <li>No class-level {@code @Path} annotation on the interface when a method has
     *       {@code @Url}.</li>
     *   <li>No method-level {@code @Path} annotation on {@code @Url} methods.</li>
     *   <li>No {@code @PathParam} parameters (top-level or inside {@code @BeanParam} fields) on
     *       the same method.</li>
     * </ul>
     *
     * @param clientType the interface type (for class-level {@code @Path} check)
     * @param method the method being scanned
     * @param declarations the method followed by the declarations it overrides
     * @param params the resolved parameter models for the method
     * @param interfaceHasPath whether the interface itself carries {@code @Path}
     */
    private void validateUrlMethodConstraints(
            TypeElement clientType,
            ExecutableElement method,
            List<ExecutableElement> declarations,
            List<ParamModel> params,
            boolean interfaceHasPath) {
        long urlCount =
                params.stream().filter(p -> p.kind() == ParamModel.Kind.URL).count();
        if (urlCount == 0) {
            return;
        }
        if (urlCount > 1) {
            ctx.diagnostics()
                    .error(
                            method,
                            "At most one @Url parameter is allowed per method: %s has %d",
                            method.getSimpleName(),
                            urlCount);
        }
        if (interfaceHasPath) {
            ctx.diagnostics()
                    .error(
                            clientType,
                            "@Url method %s must not have interface-level @Path on %s",
                            method.getSimpleName(),
                            clientType.getSimpleName());
        }
        if (isPresentOnAny(declarations, PATH_FQN)) {
            ctx.diagnostics().error(method, "@Url method %s must not have method-level @Path", method.getSimpleName());
        }
        // No @PathParam at top level
        for (ParamModel p : params) {
            if (p.kind() == ParamModel.Kind.PATH) {
                ctx.diagnostics()
                        .error(method, "@Url method %s must not have @PathParam parameters", method.getSimpleName());
                break;
            }
        }
        // No @PathParam inside @BeanParam — scan each bean type's fields at compile time
        for (ParamModel p : params) {
            if (p.kind() != ParamModel.Kind.BEAN) {
                continue;
            }
            var beanElement = ctx.types().asElement(p.type());
            if (!(beanElement instanceof TypeElement te)) {
                continue;
            }
            BeanModel beanModel = beanParamScanner.scan(te);
            boolean hasPathField = beanModel.fields().stream().anyMatch(f -> f.kind() == ParamModel.Kind.PATH);
            if (hasPathField) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@Url method %s must not have @PathParam parameters (found inside @BeanParam %s)",
                                method.getSimpleName(),
                                te.getSimpleName());
                break;
            }
        }
    }

    /**
     * Reads the {@code value} attribute from a JAX-RS param annotation preserving the literal value,
     * including blank.
     *
     * <p>JAX-RS 4.0.0 has no "blank means use Java identifier" convention (that is a Spring
     * {@code @RequestParam} behavior). The runtime client and server both preserve the literal
     * annotation value end-to-end — see
     * {@code dev.vertique.rest.client.meta.ClientInterfaceScanner} and
     * {@code dev.vertique.rest.jaxrs.ParameterExtractor}. Codegen must do the same so generated
     * proxies and reflective runtime emit the same wire request for the same source.
     *
     * @param elements the parameter and the same parameter of the declarations it overrides; the
     *     nearest carrier of the annotation wins
     * @param annotationFqn the annotation's fully-qualified name
     * @return the literal value attribute (may be blank), or empty string if the annotation is absent
     */
    private String literalAnnotationValue(List<? extends Element> elements, String annotationFqn) {
        for (Element element : elements) {
            if (AnnotationMirrors.isPresent(element, annotationFqn)) {
                return AnnotationMirrors.findByFqn(element, annotationFqn)
                        .flatMap(mirror -> ctx.annotations().attribute(mirror, "value", String.class))
                        .orElse("");
            }
        }
        return "";
    }

    /**
     * Whether any of the elements carries the annotation.
     *
     * @param elements the element and the declarations it overrides
     * @param annotationFqn the annotation's fully-qualified name
     * @return {@code true} if one of them carries it
     */
    private static boolean isPresentOnAny(List<? extends Element> elements, String annotationFqn) {
        for (Element element : elements) {
            if (AnnotationMirrors.isPresent(element, annotationFqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code value} attribute of {@code @DefaultValue} on the given element.
     *
     * @param elements the parameter and the same parameter of the declarations it overrides; the
     *     nearest {@code @DefaultValue} wins
     * @return the default value string, or {@code null} if {@code @DefaultValue} is absent
     */
    private String resolveDefaultValue(List<? extends Element> elements) {
        for (Element element : elements) {
            if (AnnotationMirrors.isPresent(element, DEFAULT_VALUE_FQN)) {
                return AnnotationMirrors.findByFqn(element, DEFAULT_VALUE_FQN)
                        .flatMap(mirror -> ctx.annotations().attribute(mirror, "value", String.class))
                        .orElse(null);
            }
        }
        return null;
    }
}
