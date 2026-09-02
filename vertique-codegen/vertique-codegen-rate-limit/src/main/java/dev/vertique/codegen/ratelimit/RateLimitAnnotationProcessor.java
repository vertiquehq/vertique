// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.ratelimit;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.ratelimit.aop.RateLimited;
import java.util.List;
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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

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

    /** Qualified names of the non-primitive, non-enum types the {@code RateLimitKey} scalar allowlist accepts. */
    private static final Set<String> SCALAR_TYPE_NAMES = Set.of(
            "java.lang.String",
            "java.lang.Character",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime");

    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        elements = environment.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(RateLimited.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                RateLimited annotation = method.getAnnotation(RateLimited.class);
                validateProxyability(method);
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
        for (String path : paths) {
            validateSelectorPath(method, path);
        }
    }

    private void validateSelectorPath(ExecutableElement method, String path) {
        if (path.isBlank()) {
            error(method, "rate-limit key selector path must not be blank");
            return;
        }
        if (path.length() > 256) {
            error(method, "rate-limit key selector path must not exceed 256 characters");
            return;
        }
        String[] segments = path.split("\\.", -1);
        if (segments.length > 8 || segments[0].isBlank()) {
            error(method, "rate-limit key property paths are limited to eight segments including the root parameter");
            return;
        }
        // Segment 0 is the root: it is validated by the parameter-resolution rule below, not the
        // identifier rule (it may be a positional index such as "0" or "1").
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (!isIdentifier(segment)) {
                error(method, "rate-limit key property path contains an invalid identifier: " + segment);
                return;
            }
        }
        int parameterIndex = -1;
        try {
            parameterIndex = Integer.parseInt(segments[0]);
        } catch (NumberFormatException ignored) {
            for (int index = 0; index < method.getParameters().size(); index++) {
                if (segments[0].contentEquals(method.getParameters().get(index).getSimpleName())) {
                    parameterIndex = index;
                    break;
                }
            }
        }
        if (parameterIndex < 0 || parameterIndex >= method.getParameters().size()) {
            error(method, "rate-limit key selector does not resolve to a method parameter: " + segments[0]);
            return;
        }
        TypeMirror type = method.getParameters().get(parameterIndex).asType();
        for (int index = 1; index < segments.length; index++) {
            type = propertyType(type, segments[index]);
            if (type == null) {
                error(
                        method,
                        "rate-limit key property is not an accessible record or bean accessor: " + segments[index]);
                return;
            }
        }
        if (!isScalar(type)) {
            error(method, "rate-limit key selector must end in a supported scalar type");
        }
    }

    /**
     * Resolves the return type of the zero-arg accessor named {@code property} on {@code type},
     * mirroring {@code dev.vertique.ratelimit.aop.MethodMetadataKeyResolver}'s own runtime accessor
     * resolution exactly: a bare-name accessor (a record component's own accessor method) is
     * accepted only when {@code type}'s declaring element is a record; every other declared type
     * accepts only {@code getX}/{@code isX} (contracts/rate-limit-aop.md, "Selector path grammar").
     */
    private TypeMirror propertyType(TypeMirror type, String property) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        boolean bareNameEligible = element.getKind() == ElementKind.RECORD;
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member instanceof ExecutableElement method) {
                String name = method.getSimpleName().toString();
                boolean bareNameMatch = bareNameEligible && name.equals(property);
                if ((bareNameMatch || name.equals("get" + suffix) || name.equals("is" + suffix))
                        && method.getParameters().isEmpty()
                        && method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.STATIC)) {
                    return method.getReturnType();
                }
            }
        }
        return null;
    }

    private boolean isScalar(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.getKind() != TypeKind.VOID;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        return element.getKind() == ElementKind.ENUM
                || SCALAR_TYPE_NAMES.contains(element.getQualifiedName().toString());
    }

    private void validateProxyability(ExecutableElement method) {
        Element enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement bean)) {
            return;
        }
        if (!bean.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "rate-limited methods must be declared on a public Dagger-managed class");
        }
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            error(method, "rate-limited methods cannot be declared on a final class");
        }
        if (!hasInjectConstructor(bean)) {
            error(method, "rate-limited methods require exactly one @Inject constructor");
        }
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.FINAL)
                || modifiers.contains(Modifier.PRIVATE)
                || modifiers.contains(Modifier.STATIC)
                || modifiers.contains(Modifier.ABSTRACT)) {
            error(method, "rate-limited methods must be instance methods that can be overridden");
        }
    }

    private boolean hasInjectConstructor(TypeElement bean) {
        List<? extends ExecutableElement> constructors = ElementFilter.constructorsIn(bean.getEnclosedElements());
        return constructors.size() == 1 && hasInject(constructors.getFirst());
    }

    private boolean hasInject(Element element) {
        return AnnotationMirrors.isPresent(element, "jakarta.inject.Inject")
                || AnnotationMirrors.isPresent(element, "javax.inject.Inject");
    }

    private static boolean isIdentifier(String value) {
        return SourceVersion.isIdentifier(value);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
