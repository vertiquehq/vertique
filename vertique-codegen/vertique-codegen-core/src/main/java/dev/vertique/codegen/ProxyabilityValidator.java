// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Validates the proxyability preconditions for methods intercepted by an annotation family.
 */
public final class ProxyabilityValidator {

    private final CodegenContext context;
    private final String family;
    private final Map<TypeElement, Boolean> classValidation = new HashMap<>();

    /**
     * Creates a proxyability validator.
     *
     * @param context annotation-processing context used for type inspection and diagnostics
     * @param family diagnostic family prefix
     */
    public ProxyabilityValidator(CodegenContext context, String family) {
        this.context = context;
        this.family = family;
    }

    /**
     * Reports proxyability violations and memoizes class-level checks per enclosing class.
     *
     * @param method method to validate
     * @return {@code true} when the enclosing class and method satisfy all proxyability checks
     */
    public boolean validate(ExecutableElement method) {
        if (!(method.getEnclosingElement() instanceof TypeElement bean)) {
            return true;
        }

        boolean classValid = classValidation.computeIfAbsent(bean, ignored -> validateClass(bean, method));
        boolean methodValid = validateMethod(method);
        return classValid && methodValid;
    }

    private boolean validateClass(TypeElement bean, ExecutableElement source) {
        boolean valid = true;
        if (!bean.getModifiers().contains(Modifier.PUBLIC)) {
            context.diagnostics().error(source, Diagnostics.methodsNotOnPublicClass(family));
            valid = false;
        }
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            context.diagnostics().error(source, Diagnostics.methodsOnFinalClass(family));
            valid = false;
        }
        if (!hasInjectConstructor(bean)) {
            context.diagnostics().error(source, Diagnostics.methodsRequireInjectConstructor(family));
            valid = false;
        }
        return valid;
    }

    private boolean validateMethod(ExecutableElement method) {
        var modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.FINAL)
                || modifiers.contains(Modifier.PRIVATE)
                || modifiers.contains(Modifier.STATIC)
                || modifiers.contains(Modifier.ABSTRACT)) {
            context.diagnostics().error(method, Diagnostics.methodsNotOverridable(family));
            return false;
        }
        return true;
    }

    private boolean hasInjectConstructor(TypeElement bean) {
        List<? extends ExecutableElement> constructors = ElementFilter.constructorsIn(bean.getEnclosedElements());
        return constructors.size() == 1 && hasInject(constructors.getFirst());
    }

    private boolean hasInject(Element element) {
        return AnnotationMirrors.isPresent(element, "jakarta.inject.Inject")
                || AnnotationMirrors.isPresent(element, "javax.inject.Inject");
    }
}
