// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.ratelimit;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.ProxyabilityValidator;
import dev.vertique.codegen.SelectorPathValidator;
import dev.vertique.ratelimit.aop.RateLimited;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Compile-time validation for {@code @RateLimited} usage: policy-name syntax, {@code cost}, selector
 * paths, and proxyability preconditions ({@code contracts/rate-limit-aop.md}, "Compile-time
 * validation").
 *
 * <p>Generates no sources of its own — {@link #process} always returns {@code false} — and exists
 * purely to fail the build on invalid usage that would otherwise only surface at runtime.
 */
@SupportedAnnotationTypes("dev.vertique.ratelimit.aop.RateLimited")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class RateLimitAnnotationProcessor extends AbstractProcessor {

    private static final Pattern POLICY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private SelectorPathValidator selectorPathValidator;
    private ProxyabilityValidator proxyabilityValidator;

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        CodegenContext context = new CodegenContext(environment);
        selectorPathValidator = new SelectorPathValidator(context, "rate-limit key");
        proxyabilityValidator = new ProxyabilityValidator(context, "rate-limited");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(RateLimited.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                RateLimited annotation = method.getAnnotation(RateLimited.class);
                proxyabilityValidator.validate(method);
                validatePolicyName(method, annotation.policy());
                validateCost(method, annotation.cost());
                validateSelector(method, annotation.key());
            }
        }
        return false;
    }

    private void validatePolicyName(ExecutableElement method, String policy) {
        if (!POLICY_NAME_PATTERN.matcher(policy).matches()) {
            error(method, "rate-limit policy name must match [A-Za-z0-9._~-]{1,128}: " + policy);
        }
    }

    private void validateCost(ExecutableElement method, long cost) {
        if (cost < 1) {
            error(method, "rate-limit cost must be at least 1: " + cost);
        }
    }

    private void validateSelector(ExecutableElement method, String[] paths) {
        // An explicitly empty declared component list means the key is derived from the subject
        // alone; there is nothing to resolve.
        selectorPathValidator.validate(method, paths);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
