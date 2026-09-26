// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The composer step 1a runtime backstop's reflective re-check of the {@code Application} annotation
 * allow list: the same rule {@code ApplicationAnnotationValidator} enforces at compile time, applied
 * by reflection to a hand-written registration's class hierarchy so a registration the processor did
 * not produce still fails startup.
 *
 * <p>Only type-declaration annotations are checked; member annotations, such as one on the
 * overriding {@code getClasses()} method, have no effect on an application and are never inspected.
 * {@link Class#getDeclaredAnnotations()} already returns runtime-retained annotations only, so no
 * separate retention check is needed here. Every {@code RUNTIME}-retained annotation directly
 * present on a type in scope must be one of:
 *
 * <ul>
 *   <li>{@link ApplicationPath}, but not on an interface;
 *   <li>a type meta-annotated with {@code jakarta.inject.Scope}, {@code jakarta.inject.Qualifier},
 *       {@code javax.inject.Scope}, or {@code javax.inject.Qualifier} (matched by fully qualified
 *       name, since {@code javax.inject} is not on this module's classpath);
 *   <li>a type in package {@code java.lang};
 *   <li>{@link OpenAPIDefinition} in which every element other than {@code info} equals its declared
 *       default, compared with {@link Method#getDefaultValue()} via {@link Objects#deepEquals}.
 * </ul>
 *
 * <p>A repeatable container annotation is checked as an annotation in its own right:
 * {@link Class#getDeclaredAnnotations()} already returns the container, not its repeated elements,
 * so no further unwrapping is needed.
 *
 * <p>The scope in which annotations are checked is the application class itself, every superclass
 * strictly below {@link Application}, nearest first, and every interface any of them implements,
 * transitively including superinterfaces, in the same deterministic order the compile-time
 * validator's hierarchy walk uses.
 */
final class ApplicationAnnotationAllowList {

    /**
     * The fully qualified names of the scope and qualifier meta-annotations that exempt an
     * annotation type from the allow list, matched by name because {@code javax.inject} is not on
     * this module's classpath.
     */
    private static final Set<String> SCOPE_OR_QUALIFIER_META_ANNOTATIONS =
            Set.of("jakarta.inject.Scope", "jakarta.inject.Qualifier", "javax.inject.Scope", "javax.inject.Qualifier");

    private ApplicationAnnotationAllowList() {}

    /**
     * Returns one violation message per (declaring type, annotation) pair outside the allow list,
     * anywhere in {@code applicationType}'s scope.
     *
     * @param applicationType the application's declared registration type
     * @return the violation messages, in scope-walk order; empty when every type-declaration
     *     annotation in scope is allow-listed
     */
    static List<String> violations(Class<? extends Application> applicationType) {
        List<String> violations = new ArrayList<>();
        for (Class<?> declaringType : scope(applicationType)) {
            for (Annotation annotation : declaringType.getDeclaredAnnotations()) {
                checkAnnotation(applicationType, declaringType, annotation, violations);
            }
        }
        return violations;
    }

    /**
     * Returns {@code applicationType}'s annotation-check scope: the type itself, then every
     * superclass strictly below {@link Application}, nearest first, then every interface any of
     * them implements, transitively including superinterfaces, in
     * {@link TypeResolver#getAllInterfaces} discovery order.
     *
     * @param applicationType the application's declared registration type
     * @return the ordered scope
     */
    private static List<Class<?>> scope(Class<? extends Application> applicationType) {
        List<Class<?>> scope = new ArrayList<>();
        scope.add(applicationType);
        for (Class<?> superclass = applicationType.getSuperclass();
                superclass != null && superclass != Application.class;
                superclass = superclass.getSuperclass()) {
            scope.add(superclass);
        }
        scope.addAll(TypeResolver.getAllInterfaces(applicationType));
        return scope;
    }

    /**
     * Checks one annotation directly present on one type in scope against the allow list, appending
     * a violation message when it is not allow-listed.
     *
     * @param applicationType the application under check, for the violation message
     * @param declaringType   the type in scope that directly carries {@code annotation}
     * @param annotation      the annotation instance to check
     * @param violations      a violation message is appended here, if any
     */
    private static void checkAnnotation(
            Class<? extends Application> applicationType,
            Class<?> declaringType,
            Annotation annotation,
            List<String> violations) {
        Class<? extends Annotation> annotationType = annotation.annotationType();

        if (annotationType == ApplicationPath.class) {
            if (!declaringType.isInterface()) {
                return;
            }
            violations.add(violation(applicationType, declaringType, annotationType, false));
            return;
        }

        if ("java.lang".equals(annotationType.getPackageName())) {
            return;
        }

        if (isScopeOrQualifier(annotationType)) {
            return;
        }

        if (annotationType == OpenAPIDefinition.class) {
            if (isDefaultOpenApiDefinition((OpenAPIDefinition) annotation)) {
                return;
            }
            violations.add(violation(applicationType, declaringType, annotationType, true));
            return;
        }

        violations.add(violation(applicationType, declaringType, annotationType, false));
    }

    /**
     * Returns whether {@code annotationType} is meta-annotated with one of the scope or qualifier
     * annotations in {@link #SCOPE_OR_QUALIFIER_META_ANNOTATIONS}, matched by fully qualified name.
     *
     * @param annotationType the annotation type to inspect
     * @return {@code true} when {@code annotationType} is a scope or qualifier annotation
     */
    private static boolean isScopeOrQualifier(Class<? extends Annotation> annotationType) {
        for (Annotation meta : annotationType.getAnnotations()) {
            if (SCOPE_OR_QUALIFIER_META_ANNOTATIONS.contains(
                    meta.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether every element of {@code annotation} other than {@code info} equals its
     * declared default, compared structurally with {@link Objects#deepEquals}.
     *
     * @param annotation the {@link OpenAPIDefinition} instance to check
     * @return {@code true} when every element other than {@code info} is at its declared default
     */
    private static boolean isDefaultOpenApiDefinition(OpenAPIDefinition annotation) {
        for (Method element : OpenAPIDefinition.class.getDeclaredMethods()) {
            if ("info".equals(element.getName())) {
                continue;
            }
            if (!Objects.deepEquals(invoke(element, annotation), element.getDefaultValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Invokes an {@link OpenAPIDefinition} element accessor on {@code annotation}.
     *
     * @param element    the element accessor method
     * @param annotation the annotation instance to read
     * @return the element's value
     */
    private static Object invoke(Method element, OpenAPIDefinition annotation) {
        try {
            return element.invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to read OpenAPIDefinition element " + element.getName() + " via reflection", e);
        }
    }

    /**
     * Builds the allow-list violation message naming the application, the offending annotation, and
     * the type in scope that carries it.
     *
     * @param applicationType the application under check
     * @param declaringType   the type carrying the offending annotation
     * @param annotationType  the offending annotation's type
     * @param openApiSuffix   whether to append the {@code @OpenAPIDefinition}-only suffix
     * @return the violation message
     */
    private static String violation(
            Class<? extends Application> applicationType,
            Class<?> declaringType,
            Class<? extends Annotation> annotationType,
            boolean openApiSuffix) {
        String base = ("Application %s carries @%s on %s, which is not allowed: application classes carry no"
                        + " resource semantics")
                .formatted(applicationType.getName(), annotationType.getName(), declaringType.getName());
        return openApiSuffix ? base + "; only its info element may be set" : base;
    }
}
