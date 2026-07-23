// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.jaxrs.JaxRsParamClassifier;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates body/form parameter exclusivity on JAX-RS resource methods at compile time
 * (CG-009 Tier-A).
 *
 * <p>Mirrors {@code RouteValidator.validateMethodParams} (runtime rule):
 * <ul>
 *   <li>A method may have at most one BODY parameter.</li>
 *   <li>A method may not mix {@code @FormParam}/file-upload parameters with a BODY parameter.</li>
 * </ul>
 *
 * <p>Parameter classification delegates to {@link JaxRsParamClassifier#classify}, which uses the
 * same ordered dispatch as the runtime {@code ResourceScanner.resolveParams}. Each classification
 * slot corresponds to a value of {@link JaxRsParamSource}.
 *
 * <p>Two APIs are provided: the original element-based API (used by CG-009 tests) and an
 * {@link EffectiveMethodContract}-based API used by {@code JaxRsPipelineProcessor} (CG-010).
 */
public final class BodyFormValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new {@code BodyFormValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public BodyFormValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Element-based API (CG-009, preserved for existing tests) ---

    /**
     * Validates the parameter list of the given resource method, checking that at most one body
     * parameter is present and that body and form/file-upload parameters are not mixed.
     *
     * <p>Emits an {@code ERROR} diagnostic on the method element for each violation found. The
     * diagnostic message includes the declaring class simple name derived from
     * {@code method.getEnclosingElement()}, matching the runtime
     * {@code RouteValidator.validateMethodParams} wording for inherited resource methods.
     *
     * @param method the resource method to validate; must not be {@code null}
     */
    public void validate(ExecutableElement method) {
        Element enclosing = method.getEnclosingElement();
        String resourceClassName =
                enclosing instanceof TypeElement te ? te.getSimpleName().toString() : ""; // defensive fallback
        String methodName = method.getSimpleName().toString();
        int bodyCount = 0;
        boolean hasFormOrUpload = false;

        for (VariableElement param : method.getParameters()) {
            JaxRsParamSource source = JaxRsParamClassifier.classify(param, ctx.types(), ctx.elements());
            switch (source) {
                case BODY -> bodyCount++;
                case FORM, FILE_UPLOADS, ENTITY_PARTS -> hasFormOrUpload = true;
                default -> {
                    // Not relevant to this check
                }
            }
        }

        if (bodyCount > 1) {
            ctx.diagnostics().error(method, Diagnostics.multipleBodyParams(resourceClassName, methodName, bodyCount));
        }
        if (hasFormOrUpload && bodyCount > 0) {
            ctx.diagnostics().error(method, Diagnostics.formAndBodyConflict(resourceClassName, methodName));
        }
    }

    // --- Contract-based API (CG-010) ---

    /**
     * Validates the parameter list of the given method contract, checking that at most one body
     * parameter is present and that body and form/file-upload parameters are not mixed.
     *
     * <p>Reads from the pre-classified {@link EffectiveParamContract} list; the error wording is
     * identical to the element-based {@link #validate(ExecutableElement)} overload.
     *
     * @param methodContract the resolved method contract; must not be {@code null}
     */
    public void validate(EffectiveMethodContract methodContract) {
        ExecutableElement method = methodContract.concreteMethod();
        Element enclosing = method.getEnclosingElement();
        String resourceClassName =
                enclosing instanceof TypeElement te ? te.getSimpleName().toString() : "";
        String methodName = method.getSimpleName().toString();
        int bodyCount = 0;
        boolean hasFormOrUpload = false;

        for (EffectiveParamContract param : methodContract.params()) {
            switch (param.source()) {
                case BODY -> bodyCount++;
                case FORM, FILE_UPLOADS, ENTITY_PARTS -> hasFormOrUpload = true;
                default -> {
                    // Not relevant to this check
                }
            }
        }

        if (bodyCount > 1) {
            ctx.diagnostics().error(method, Diagnostics.multipleBodyParams(resourceClassName, methodName, bodyCount));
        }
        if (hasFormOrUpload && bodyCount > 0) {
            ctx.diagnostics().error(method, Diagnostics.formAndBodyConflict(resourceClassName, methodName));
        }
    }
}
