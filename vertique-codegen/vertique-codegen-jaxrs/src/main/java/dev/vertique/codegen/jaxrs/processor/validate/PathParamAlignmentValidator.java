// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.JaxRsBeanScanner;
import dev.vertique.codegen.PathPlaceholders;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.jaxrs.EffectiveResourceContract;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates bidirectional alignment between {@code @Path} placeholders and {@code @PathParam}
 * declarations on JAX-RS resource methods at compile time (CG-009 Tier-A pre-empted defect).
 *
 * <p>The runtime never validates this — a placeholder without a matching {@code @PathParam} causes
 * the parameter to silently resolve to {@code null} at dispatch time. This validator catches the
 * mismatch at compile time.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Read the class-level {@code @Path} value; if absent, return (not a JAX-RS resource).</li>
 *   <li>Optionally concatenate the method-level {@code @Path} value; normalize consecutive
 *       {@code /} characters (mirrors runtime {@code ResourceScanner.resolvePath}).</li>
 *   <li>Extract placeholder names via {@link PathPlaceholders#extract(String)}, which handles
 *       regex-constrained placeholders ({@code {id:[0-9]+}} &rarr; {@code id}).</li>
 *   <li>Collect {@code @PathParam} names from direct method parameters and from any composite
 *       parameter ({@code @BeanParam} or {@code @RequestParams}-annotated type), using
 *       {@link JaxRsBeanScanner} to scan composite types.</li>
 *   <li>Emit an {@code ERROR} for each placeholder with no matching {@code @PathParam}.</li>
 *   <li>Emit an {@code ERROR} for each {@code @PathParam} with no matching placeholder.</li>
 * </ol>
 *
 * <p>Two APIs are provided: the original element-based API (used by CG-009 tests) and an
 * {@link EffectiveResourceContract}/{@link EffectiveMethodContract}-based API used by
 * {@code JaxRsPipelineProcessor} (CG-010).
 */
public final class PathParamAlignmentValidator {

    /** Compiled pattern for collapsing consecutive {@code /} characters. */
    private static final Pattern SLASHES = Pattern.compile("/+");

    private final CodegenContext ctx;
    private final JaxRsBeanScanner beanScanner;

    /**
     * Creates a new {@code PathParamAlignmentValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public PathParamAlignmentValidator(CodegenContext ctx) {
        this.ctx = ctx;
        this.beanScanner = new JaxRsBeanScanner(ctx);
    }

    // --- Element-based API (CG-009, preserved for existing tests) ---

    /**
     * Validates bidirectional alignment between {@code @Path} placeholders and {@code @PathParam}
     * declarations for the given resource method.
     *
     * <p>Class-level {@code @Path} is read from the leaf resource type only — inherited base-path
     * annotations are ignored, matching runtime {@code ResourceScanner} semantics.
     *
     * <p>Emits an {@code ERROR} diagnostic on the method element for each placeholder that has no
     * matching {@code @PathParam}, and another {@code ERROR} for each {@code @PathParam} that has
     * no matching placeholder.
     *
     * @param resource the resource type carrying the class-level {@code @Path}; must not be
     *                 {@code null}
     * @param method   the resource method to validate; must not be {@code null}
     */
    public void validate(TypeElement resource, ExecutableElement method) {
        // Step 1: Class-level @Path — if absent this isn't a JAX-RS resource
        String classPath = AnnotationMirrors.findByFqn(resource, JaxRsAnnotations.PATH)
                .flatMap(m -> ctx.annotations().attribute(m, "value", String.class))
                .orElse(null);
        if (classPath == null) {
            return;
        }

        // Step 2: Method-level @Path (optional) — concatenate and normalize
        String methodPath = AnnotationMirrors.findByFqn(method, JaxRsAnnotations.PATH)
                .flatMap(m -> ctx.annotations().attribute(m, "value", String.class))
                .orElse("");

        String combinedPath = normalizePath(classPath + "/" + methodPath);

        // Step 3: Extract placeholder names (strips :regex suffix automatically)
        Set<String> placeholders = PathPlaceholders.extract(combinedPath);

        // Step 4: Collect @PathParam names from direct parameters and composites
        String methodName = method.getSimpleName().toString();
        Set<String> pathParams = collectPathParams(method);

        // Step 5: Placeholder without matching @PathParam
        for (String placeholder : placeholders) {
            if (!pathParams.contains(placeholder)) {
                ctx.diagnostics().error(method, Diagnostics.pathPlaceholderMissingParam(placeholder, methodName));
            }
        }

        // Step 6: @PathParam without matching placeholder
        for (String param : pathParams) {
            if (!placeholders.contains(param)) {
                ctx.diagnostics().error(method, Diagnostics.pathParamMissingPlaceholder(param, methodName));
            }
        }
    }

    // --- Contract-based API (CG-010) ---

    /**
     * Validates bidirectional alignment between {@code @Path} placeholders and {@code @PathParam}
     * declarations for the given method contract.
     *
     * <p>Reads the class-level path from {@code resourceContract.classPath()} and the method-level
     * path from {@code methodContract.methodPath()}; the same error messages and algorithm as the
     * element-based {@link #validate(TypeElement, ExecutableElement)} overload apply.
     *
     * @param resourceContract the resolved resource contract; must not be {@code null}
     * @param methodContract   the resolved method contract; must not be {@code null}
     */
    public void validate(EffectiveResourceContract resourceContract, EffectiveMethodContract methodContract) {
        // Step 1: Class-level path from contract
        String classPath = resourceContract.classPath();
        if (classPath == null) {
            return;
        }

        // Step 2: Method-level path from contract — concatenate and normalize
        String methodPath = methodContract.methodPath() != null ? methodContract.methodPath() : "";
        String combinedPath = normalizePath(classPath + "/" + methodPath);

        // Step 3: Extract placeholder names
        Set<String> placeholders = PathPlaceholders.extract(combinedPath);

        // Step 4: Collect @PathParam names from effective param contracts and composites
        String methodName = methodContract.concreteMethod().getSimpleName().toString();
        Set<String> pathParams = collectPathParamsFromContract(methodContract);

        // Step 5: Placeholder without matching @PathParam
        for (String placeholder : placeholders) {
            if (!pathParams.contains(placeholder)) {
                ctx.diagnostics()
                        .error(
                                methodContract.concreteMethod(),
                                Diagnostics.pathPlaceholderMissingParam(placeholder, methodName));
            }
        }

        // Step 6: @PathParam without matching placeholder
        for (String param : pathParams) {
            if (!placeholders.contains(param)) {
                ctx.diagnostics()
                        .error(
                                methodContract.concreteMethod(),
                                Diagnostics.pathParamMissingPlaceholder(param, methodName));
            }
        }
    }

    // --- Private helpers (element-based) ---

    /**
     * Collects all {@code @PathParam} names from the method's direct parameters and from any
     * composite parameters ({@code @BeanParam} or {@code @RequestParams}-annotated types).
     *
     * @param method the resource method
     * @return the union of all {@code @PathParam} names
     */
    private Set<String> collectPathParams(ExecutableElement method) {
        Set<String> pathParams = new LinkedHashSet<>();
        for (VariableElement param : method.getParameters()) {
            TypeElement paramTypeElement = ctx.asTypeElement(param.asType()).orElse(null);

            if (isComposite(param, paramTypeElement)) {
                if (paramTypeElement != null) {
                    pathParams.addAll(beanScanner.pathParamNames(paramTypeElement));
                }
            } else {
                // Preserve the literal @PathParam value, including blank — mirrors runtime
                // ParameterExtractor and the bean-scan branch in JaxRsBeanScanner. A blank value
                // intentionally lands in the candidate set so the bidirectional check emits a
                // useful "@PathParam('') has no matching placeholder" diagnostic.
                AnnotationMirrors.findByFqn(param, JaxRsAnnotations.PATH_PARAM)
                        .flatMap(m -> ctx.annotations().attribute(m, "value", String.class))
                        .ifPresent(pathParams::add);
            }
        }
        return pathParams;
    }

    /**
     * Collects all {@code @PathParam} names from the effective parameter contracts, scanning
     * into bean param types via {@link JaxRsBeanScanner} where needed.
     *
     * @param methodContract the resolved method contract
     * @return the union of all effective {@code @PathParam} names
     */
    private Set<String> collectPathParamsFromContract(EffectiveMethodContract methodContract) {
        Set<String> pathParams = new LinkedHashSet<>();
        for (EffectiveParamContract param : methodContract.params()) {
            if (param.source() == JaxRsParamSource.BEAN_PARAM) {
                TypeElement beanTypeElement = ctx.asTypeElement(param.type()).orElse(null);
                if (beanTypeElement != null) {
                    pathParams.addAll(beanScanner.pathParamNames(beanTypeElement));
                }
            } else if (param.source() == JaxRsParamSource.PATH) {
                // Preserve blank names for bidirectional-check parity
                if (param.name() != null) {
                    pathParams.add(param.name());
                }
            }
        }
        return pathParams;
    }

    /**
     * Returns {@code true} if the parameter is a composite parameter object — either the parameter
     * itself carries {@code @BeanParam} or its declared type is annotated with {@code @RequestParams}.
     *
     * @param param            the method parameter to inspect
     * @param paramTypeElement the type element for the parameter's declared type, or {@code null}
     * @return {@code true} if the parameter is a composite
     */
    private boolean isComposite(VariableElement param, TypeElement paramTypeElement) {
        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.BEAN_PARAM)) {
            return true;
        }
        if (paramTypeElement != null
                && AnnotationMirrors.isPresent(paramTypeElement, JaxRsAnnotations.REQUEST_PARAMS)) {
            return true;
        }
        return false;
    }

    /**
     * Normalizes a combined JAX-RS path by collapsing multiple consecutive {@code /} characters
     * into one, mirrors {@code ResourceScanner.normalizePath}.
     *
     * @param path the raw concatenated path
     * @return the normalized path
     */
    private String normalizePath(String path) {
        return SLASHES.matcher(path).replaceAll("/");
    }
}
