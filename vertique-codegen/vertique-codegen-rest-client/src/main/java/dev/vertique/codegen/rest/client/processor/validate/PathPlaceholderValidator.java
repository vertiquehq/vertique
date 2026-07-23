// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.JaxRsBeanScanner;
import dev.vertique.codegen.PathPlaceholders;
import dev.vertique.codegen.rest.client.processor.ClientInterfaceModel;
import dev.vertique.codegen.rest.client.processor.MethodModel;
import dev.vertique.codegen.rest.client.processor.ParamModel;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;

/**
 * Validates that {@code @Path} placeholders and {@code @PathParam} annotations are consistent.
 *
 * <p>Two error conditions are detected per method:
 * <ol>
 *   <li>A {@code {name}} placeholder in the combined path (base + suffix) has no matching
 *       {@code @PathParam("name")} &mdash; error via {@link Diagnostics#pathPlaceholderMissingParam}.</li>
 *   <li>A {@code @PathParam("name")} parameter has no matching {@code {name}} placeholder &mdash;
 *       error via {@link Diagnostics#pathParamMissingPlaceholder}.</li>
 * </ol>
 *
 * <p>{@code @PathParam} names contributed via {@code @BeanParam} bean fields are included in the
 * candidate set so that path params expanded through bean types are also validated correctly.
 *
 * <p>Placeholder extraction delegates to {@link PathPlaceholders#extract(String)}, which correctly
 * strips regex constraints ({@code {id:[0-9]+}} &rarr; {@code id}). {@code @PathParam} names inside
 * composite bean types are resolved via {@link JaxRsBeanScanner}.
 */
public final class PathPlaceholderValidator {

    private final CodegenContext ctx;
    private final JaxRsBeanScanner jaxRsBeanScanner;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public PathPlaceholderValidator(CodegenContext ctx) {
        this.ctx = ctx;
        this.jaxRsBeanScanner = new JaxRsBeanScanner(ctx);
    }

    /**
     * Validates all methods in the given interface model for path placeholder consistency.
     * Emits compiler errors for each mismatch found.
     *
     * @param model the client interface model to validate
     * @return {@code true} when no errors were found; {@code false} if at least one error was emitted
     */
    public boolean validate(ClientInterfaceModel model) {
        boolean valid = true;
        for (MethodModel method : model.methods()) {
            valid &= validateMethod(model, method);
        }
        return valid;
    }

    // --- Private ---

    /**
     * Validates placeholder/param consistency for a single method.
     *
     * <p>The set of candidate {@code @PathParam} names is the union of:
     * <ul>
     *   <li>Direct {@link ParamModel.Kind#PATH} params on the method.</li>
     *   <li>{@code @PathParam} names inside any {@link ParamModel.Kind#BEAN} param's bean type,
     *       resolved via {@link JaxRsBeanScanner}.</li>
     * </ul>
     *
     * @param interfaceModel the enclosing interface model (for the base path)
     * @param method the method to validate
     * @return {@code true} when valid; {@code false} if errors were emitted
     */
    private boolean validateMethod(ClientInterfaceModel interfaceModel, MethodModel method) {
        String combinedPath = interfaceModel.basePath() + method.pathSuffix();
        Set<String> placeholders = PathPlaceholders.extract(combinedPath);
        Set<String> pathParams = collectPathParamNames(method);

        String methodName = method.method().getSimpleName().toString();
        boolean valid = true;

        // Check: placeholder without matching @PathParam
        for (String placeholder : placeholders) {
            if (!pathParams.contains(placeholder)) {
                ctx.diagnostics()
                        .error(method.method(), Diagnostics.pathPlaceholderMissingParam(placeholder, methodName));
                valid = false;
            }
        }

        // Check: @PathParam without matching placeholder
        for (String paramName : pathParams) {
            if (!placeholders.contains(paramName)) {
                ctx.diagnostics()
                        .error(method.method(), Diagnostics.pathParamMissingPlaceholder(paramName, methodName));
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Collects all {@code @PathParam} names visible from the method: direct PATH params and
     * {@code @PathParam} names contributed via {@link ParamModel.Kind#BEAN} param types.
     *
     * <p>Bean-type scanning is delegated to {@link JaxRsBeanScanner#pathParamNames(TypeElement)},
     * which handles both record components (checking accessors first) and class fields (walking
     * the superclass chain).
     *
     * @param method the method to inspect
     * @return ordered set of all path param names
     */
    private Set<String> collectPathParamNames(MethodModel method) {
        Set<String> pathParams = method.params().stream()
                .filter(p -> p.kind() == ParamModel.Kind.PATH)
                .map(ParamModel::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Walk BEAN params and union their @PathParam names via the shared scanner
        for (ParamModel param : method.params()) {
            if (param.kind() != ParamModel.Kind.BEAN) {
                continue;
            }
            var element = ctx.types().asElement(param.type());
            if (!(element instanceof TypeElement beanTypeElement)) {
                continue;
            }
            pathParams.addAll(jaxRsBeanScanner.pathParamNames(beanTypeElement));
        }

        return pathParams;
    }
}
