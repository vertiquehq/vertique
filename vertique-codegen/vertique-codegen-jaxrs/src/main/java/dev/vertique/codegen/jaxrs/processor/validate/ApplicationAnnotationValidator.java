// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.JaxRsHierarchy;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * The compile-time half of the {@code Application} annotation allow list: every {@code RUNTIME}
 * -retained type-declaration annotation anywhere in an eligible application's scope must be one of
 * a small allow list, or compilation fails naming the application, the declaring type, and the
 * annotation.
 *
 * <p>The checked scope is the application itself, every superclass strictly below {@code
 * jakarta.ws.rs.core.Application}, nearest first, and every interface any of them implements,
 * transitively including superinterfaces, in {@link JaxRsHierarchy#allInterfaces} discovery order.
 * Only annotations on the type declarations in that scope are checked; annotations on members, such
 * as the {@code @Inject} constructor or an overriding method, have no effect on an application and
 * are not inspected.
 *
 * <p>Every type-level annotation mirror in scope whose type is {@code RUNTIME}-retained must be one
 * of:
 *
 * <ul>
 *   <li>{@code jakarta.ws.rs.ApplicationPath}, but not on an interface;
 *   <li>a type meta-annotated with {@code jakarta.inject.Scope}, {@code jakarta.inject.Qualifier},
 *       {@code javax.inject.Scope}, or {@code javax.inject.Qualifier} (matched by fully qualified
 *       name, since {@code javax.inject} is not on this module's classpath);
 *   <li>a type in package {@code java.lang};
 *   <li>{@code io.swagger.v3.oas.annotations.OpenAPIDefinition} in which every element other than
 *       {@code info} equals its declared default, compared structurally against {@link
 *       ExecutableElement#getDefaultValue()}.
 * </ul>
 *
 * <p>A repeatable container annotation (for example the compiler-synthesized {@code
 * ConditionalOnProperties} when two or more {@code @ConditionalOnProperty} annotations appear on
 * one type) is checked as an annotation in its own right; {@link TypeElement#getAnnotationMirrors()}
 * already returns the container, not its repeated elements, so no further unwrapping is needed.
 *
 * <p>{@code @ConditionalOnProperty}, {@code @ConditionalOnProperties}, and {@code @NoAutoWire}
 * (package {@code dev.vertique.codegen}) are {@code SOURCE}-retained and honored only on the
 * application itself; on any supertype compiled in the same build they are a compile error, because
 * the processor would otherwise ignore them silently. Every other {@code SOURCE}- or {@code
 * CLASS}-retained annotation, including one with no {@code @Retention} at all (which defaults to
 * {@code CLASS}), is unchecked.
 *
 * <p>Only {@link TypeElement#getAnnotationMirrors()} is read, never the inherited-annotation
 * variant, so an {@code @Inherited} meta-annotated annotation (such as {@code @OpenAPIDefinition})
 * is checked only where it is directly present.
 *
 * <p>A class annotated {@code @NoAutoWire} is never passed to this validator: {@code
 * JaxRsApplicationScanner#scan} excludes it before this validator runs, so it is neither registered
 * nor validated.
 */
public final class ApplicationAnnotationValidator {

    /** FQN of {@code jakarta.ws.rs.core.Application}, the scope's upper bound. */
    private static final String APPLICATION_FQN = "jakarta.ws.rs.core.Application";

    /** FQN of {@code jakarta.ws.rs.ApplicationPath}. */
    private static final String APPLICATION_PATH_FQN = "jakarta.ws.rs.ApplicationPath";

    /** FQN of {@code io.swagger.v3.oas.annotations.OpenAPIDefinition}. */
    private static final String OPEN_API_DEFINITION_FQN = "io.swagger.v3.oas.annotations.OpenAPIDefinition";

    /**
     * The fully qualified names of the {@code SOURCE}-retained annotations honored only on the
     * application itself; on any other type in scope they are a compile error.
     */
    private static final Set<String> SOURCE_RETAINED_CHECKED = Set.of(
            "dev.vertique.codegen.ConditionalOnProperty",
            "dev.vertique.codegen.ConditionalOnProperties",
            "dev.vertique.codegen.NoAutoWire");

    /**
     * The fully qualified names of the scope and qualifier meta-annotations that exempt an
     * annotation type from the allow list, matched by name because {@code javax.inject} is not on
     * this module's classpath.
     */
    private static final Set<String> SCOPE_OR_QUALIFIER_META_ANNOTATIONS =
            Set.of("jakarta.inject.Scope", "jakarta.inject.Qualifier", "javax.inject.Scope", "javax.inject.Qualifier");

    private final CodegenContext ctx;

    /**
     * Creates a new {@code ApplicationAnnotationValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ApplicationAnnotationValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates every application in {@code applications} against the {@code Application} annotation
     * allow list.
     *
     * @param applications the eligible applications to validate; must not be {@code null}
     */
    public void validate(List<TypeElement> applications) {
        for (TypeElement application : applications) {
            validateApplication(application);
        }
    }

    /**
     * Validates one application's scope against the allow list, reporting one compile error per
     * offending (declaring type, annotation) pair, anchored on {@code application}'s own element.
     *
     * @param application the eligible application to validate
     */
    private void validateApplication(TypeElement application) {
        for (TypeElement declaringType : scope(application)) {
            for (AnnotationMirror mirror : declaringType.getAnnotationMirrors()) {
                checkAnnotation(application, declaringType, mirror);
            }
        }
    }

    /**
     * Returns {@code application}'s annotation-check scope: the application itself, then every
     * superclass strictly below {@link #APPLICATION_FQN}, nearest first, then every interface any of
     * them implements, transitively including superinterfaces, in {@link
     * JaxRsHierarchy#allInterfaces} discovery order.
     *
     * @param application the eligible application
     * @return the ordered scope
     */
    private List<TypeElement> scope(TypeElement application) {
        List<TypeElement> scope = new ArrayList<>();
        scope.add(application);
        TypeElement current = JaxRsHierarchy.superClass(ctx, application);
        while (current != null
                && !APPLICATION_FQN.equals(current.getQualifiedName().toString())) {
            scope.add(current);
            current = JaxRsHierarchy.superClass(ctx, current);
        }
        scope.addAll(JaxRsHierarchy.allInterfaces(ctx, application));
        return scope;
    }

    /**
     * Checks one annotation directly present on one type in {@code application}'s scope, reporting a
     * compile error when it is a source-retained annotation honored only on {@code application}
     * itself but found on a supertype, or when it is a {@code RUNTIME}-retained annotation outside
     * the allow list. A {@code CLASS}-retained annotation, or one with no {@code @Retention} at all,
     * is unchecked.
     *
     * @param application   the application under validation, for the diagnostic
     * @param declaringType the type in scope that directly carries {@code mirror}
     * @param mirror        the annotation mirror to check
     */
    private void checkAnnotation(TypeElement application, TypeElement declaringType, AnnotationMirror mirror) {
        TypeElement annotationType = asTypeElement(mirror.getAnnotationType());
        if (annotationType == null) {
            return;
        }
        String annotationFqn = annotationType.getQualifiedName().toString();
        String retention = retentionPolicy(annotationType);

        if ("SOURCE".equals(retention)) {
            if (!SOURCE_RETAINED_CHECKED.contains(annotationFqn) || declaringType.equals(application)) {
                return;
            }
            ctx.diagnostics()
                    .error(
                            application,
                            sourceRetainedOnSupertypeMessage(
                                    binaryName(application), binaryName(annotationType), binaryName(declaringType)));
            return;
        }

        if (!"RUNTIME".equals(retention)) {
            return;
        }
        checkAllowList(application, declaringType, mirror, annotationType, annotationFqn);
    }

    /**
     * Checks one {@code RUNTIME}-retained annotation against the allow list, reporting a compile
     * error when it is not on the list.
     *
     * @param application    the application under validation, for the diagnostic
     * @param declaringType  the type in scope that directly carries {@code mirror}
     * @param mirror         the annotation mirror to check
     * @param annotationType the annotation's type element
     * @param annotationFqn  the annotation's fully qualified name
     */
    private void checkAllowList(
            TypeElement application,
            TypeElement declaringType,
            AnnotationMirror mirror,
            TypeElement annotationType,
            String annotationFqn) {
        if (APPLICATION_PATH_FQN.equals(annotationFqn)) {
            if (declaringType.getKind() == ElementKind.INTERFACE) {
                reportAllowListViolation(application, declaringType, annotationType, false);
            }
            return;
        }

        String annotationPackage =
                ctx.elements().getPackageOf(annotationType).getQualifiedName().toString();
        if ("java.lang".equals(annotationPackage)) {
            return;
        }

        if (isScopeOrQualifier(annotationType)) {
            return;
        }

        if (OPEN_API_DEFINITION_FQN.equals(annotationFqn)) {
            if (isDefaultOpenApiDefinition(mirror)) {
                return;
            }
            reportAllowListViolation(application, declaringType, annotationType, true);
            return;
        }

        reportAllowListViolation(application, declaringType, annotationType, false);
    }

    /**
     * Returns whether {@code annotationType} is meta-annotated with one of the scope or qualifier
     * annotations in {@link #SCOPE_OR_QUALIFIER_META_ANNOTATIONS}, matched by fully qualified name.
     *
     * @param annotationType the annotation type to inspect
     * @return {@code true} when {@code annotationType} is a scope or qualifier annotation
     */
    private boolean isScopeOrQualifier(TypeElement annotationType) {
        for (AnnotationMirror meta : annotationType.getAnnotationMirrors()) {
            TypeElement metaType = asTypeElement(meta.getAnnotationType());
            if (metaType != null
                    && SCOPE_OR_QUALIFIER_META_ANNOTATIONS.contains(
                            metaType.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether every element of {@code mirror} other than {@code info} that is explicitly set
     * equals its declared default, compared structurally since {@link AnnotationValue} has no value
     * equality of its own.
     *
     * @param mirror the {@code OpenAPIDefinition} annotation mirror to check
     * @return {@code true} when every explicitly set element other than {@code info} is at its
     *     declared default
     */
    private boolean isDefaultOpenApiDefinition(AnnotationMirror mirror) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                mirror.getElementValues().entrySet()) {
            if ("info".contentEquals(entry.getKey().getSimpleName())) {
                continue;
            }
            AnnotationValue defaultValue = entry.getKey().getDefaultValue();
            if (!structurallyEqual(entry.getValue(), defaultValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two {@link AnnotationValue}s structurally: arrays are compared element-wise in order,
     * nested annotation mirrors are compared by {@link #structurallyEqualAnnotations}, and every
     * other value kind is compared with {@link Objects#equals}.
     *
     * @param a the first value, or {@code null}
     * @param b the second value, or {@code null}
     * @return {@code true} when {@code a} and {@code b} are structurally equal
     */
    private boolean structurallyEqual(AnnotationValue a, AnnotationValue b) {
        Object av = a == null ? null : a.getValue();
        Object bv = b == null ? null : b.getValue();
        if (av instanceof List<?> aList && bv instanceof List<?> bList) {
            if (aList.size() != bList.size()) {
                return false;
            }
            for (int i = 0; i < aList.size(); i++) {
                if (!structurallyEqual((AnnotationValue) aList.get(i), (AnnotationValue) bList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (av instanceof AnnotationMirror aMirror && bv instanceof AnnotationMirror bMirror) {
            return structurallyEqualAnnotations(aMirror, bMirror);
        }
        return Objects.equals(av, bv);
    }

    /**
     * Compares two nested annotation mirrors of the same annotation type element-wise, reading every
     * element (defaulted or explicit) of each through {@link
     * javax.lang.model.util.Elements#getElementValuesWithDefaults}.
     *
     * @param a the first nested annotation mirror
     * @param b the second nested annotation mirror
     * @return {@code true} when every element of {@code a} and {@code b} is structurally equal
     */
    private boolean structurallyEqualAnnotations(AnnotationMirror a, AnnotationMirror b) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> aValues =
                ctx.elements().getElementValuesWithDefaults(a);
        Map<? extends ExecutableElement, ? extends AnnotationValue> bValues =
                ctx.elements().getElementValuesWithDefaults(b);
        if (aValues.size() != bValues.size()) {
            return false;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : aValues.entrySet()) {
            AnnotationValue bValue = findBySimpleName(bValues, entry.getKey().getSimpleName());
            if (bValue == null || !structurallyEqual(entry.getValue(), bValue)) {
                return false;
            }
        }
        return true;
    }

    private static AnnotationValue findBySimpleName(
            Map<? extends ExecutableElement, ? extends AnnotationValue> values, Name simpleName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().equals(simpleName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Reports one allow-list violation, anchored on {@code application}'s own element.
     *
     * @param application    the application under validation
     * @param declaringType  the type carrying the offending annotation
     * @param annotationType the offending annotation's type
     * @param openApiSuffix  whether to append the {@code @OpenAPIDefinition}-only suffix
     */
    private void reportAllowListViolation(
            TypeElement application, TypeElement declaringType, TypeElement annotationType, boolean openApiSuffix) {
        ctx.diagnostics()
                .error(
                        application,
                        allowListViolationMessage(
                                binaryName(application),
                                binaryName(annotationType),
                                binaryName(declaringType),
                                openApiSuffix));
    }

    /**
     * Builds the allow-list violation message naming the application, the offending annotation, and
     * the declaring type, every name as a binary name so the text equals {@code Class.getName()}.
     *
     * @param applicationBinaryName    the application's binary name
     * @param annotationBinaryName     the offending annotation's binary name
     * @param declaringTypeBinaryName  the declaring type's binary name
     * @param openApiSuffix            whether to append the {@code @OpenAPIDefinition}-only suffix
     * @return the violation message
     */
    private static String allowListViolationMessage(
            String applicationBinaryName,
            String annotationBinaryName,
            String declaringTypeBinaryName,
            boolean openApiSuffix) {
        String base = ("Application %s carries @%s on %s, which is not allowed: application classes carry no"
                        + " resource semantics")
                .formatted(applicationBinaryName, annotationBinaryName, declaringTypeBinaryName);
        return openApiSuffix ? base + "; only its info element may be set" : base;
    }

    /**
     * Builds the source-retained-on-supertype violation message, every name as a binary name so the
     * text equals {@code Class.getName()}.
     *
     * @param applicationBinaryName   the application's binary name
     * @param annotationBinaryName    the offending annotation's binary name
     * @param declaringTypeBinaryName the supertype's binary name
     * @return the violation message
     */
    private static String sourceRetainedOnSupertypeMessage(
            String applicationBinaryName, String annotationBinaryName, String declaringTypeBinaryName) {
        return "Application %s carries @%s on supertype %s, which is honored only on the application class itself"
                .formatted(applicationBinaryName, annotationBinaryName, declaringTypeBinaryName);
    }

    /**
     * Returns {@code type}'s binary name, e.g. {@code Outer$Inner} for a nested type, so diagnostic
     * text equals {@code Class.getName()}.
     *
     * @param type the type element
     * @return the binary name
     */
    private String binaryName(TypeElement type) {
        return ctx.elements().getBinaryName(type).toString();
    }

    /**
     * Resolves {@code type} to a {@link TypeElement}, or {@code null} when it does not represent a
     * declared type.
     *
     * @param type the type mirror to resolve
     * @return the resolved type element, or {@code null}
     */
    private TypeElement asTypeElement(TypeMirror type) {
        return ctx.asTypeElement(type).orElse(null);
    }

    /**
     * Returns {@code annotationType}'s {@link Retention} policy name ({@code "RUNTIME"}, {@code
     * "CLASS"}, or {@code "SOURCE"}), defaulting to {@code "CLASS"} when no {@link Retention}
     * meta-annotation is present, mirroring the Java language default.
     *
     * @param annotationType the annotation type to inspect
     * @return the retention policy's simple enum-constant name
     */
    private String retentionPolicy(TypeElement annotationType) {
        return ctx.annotations()
                .find(annotationType, Retention.class)
                .flatMap(m -> ctx.annotations().attribute(m, "value", VariableElement.class))
                .map(v -> v.getSimpleName().toString())
                .orElse("CLASS");
    }
}
