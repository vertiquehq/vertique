// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.jaxrs.publication.ApiDocsInstalled;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The composer step 1 runtime backstop's reflective re-check of the {@code @RestApplication}
 * allow list: the rule {@code vertique-codegen-jaxrs}'s compile-time
 * {@code ApplicationAnnotationValidator} enforces on the declaring interface and its
 * superinterfaces, applied here by reflection to every registration's declaring interface so a
 * registration the processor did not produce still fails startup.
 *
 * <p>{@link Class#getDeclaredAnnotations()} already returns runtime-retained annotations only, so
 * no separate retention check is needed. Every {@code RUNTIME}-retained annotation directly present
 * on the declaring interface must be one of {@code @RestApplication}, the annotation whose type name
 * equals {@link ApiDocsInstalled#ANNOTATION_NAME} (read by name so this module never depends on the
 * docs module that declares the real annotation), {@link OpenAPIDefinition} with every element other
 * than {@code info} at its declared default (compared with {@link Method#getDefaultValue()} via
 * {@link Objects#deepEquals}), or a type in package {@code java.lang} or
 * {@code java.lang.annotation}. On a superinterface, only a {@code java.lang} or
 * {@code java.lang.annotation} type is allowed: one of the three honored-only annotations above gets
 * a dedicated "honored only on the declaring interface" message; any other disallowed annotation
 * gets the same message the declaring interface would.
 *
 * <p>The two {@code SOURCE}-retained honored-only annotations ({@code @ConditionalOnProperty},
 * {@code @NoAutoWire}) have no runtime row: reflection cannot see them, and the processor enforces
 * them on a superinterface at compile time.
 *
 * <p>The scope in which annotations are checked is the declaring interface itself, then every
 * interface {@link TypeResolver#getAllInterfaces} finds transitively reachable from it.
 */
final class ApplicationAnnotationAllowList {

    private ApplicationAnnotationAllowList() {}

    /**
     * Returns one violation message per (type in scope, annotation) pair outside the allow list, for
     * {@code declaringType}'s scope.
     *
     * @param name          the application name, named in every violation message
     * @param declaringType the registration's declaring interface
     * @return the violation messages, in scope-walk order; empty when every type-declaration
     *     annotation in scope is allow-listed
     */
    static List<String> violations(String name, Class<?> declaringType) {
        List<String> violations = new ArrayList<>();
        checkType(name, declaringType, declaringType, true, violations);
        for (Class<?> superinterface : TypeResolver.getAllInterfaces(declaringType)) {
            checkType(name, declaringType, superinterface, false, violations);
        }
        return violations;
    }

    /**
     * Checks every annotation directly present on {@code typeInScope} against the allow list.
     *
     * @param name           the application name, for the violation message
     * @param declaringType  the registration's declaring interface, for the violation message
     * @param typeInScope    the type in scope whose own declared annotations are checked
     * @param isDeclaringType whether {@code typeInScope} is {@code declaringType} itself
     * @param violations     a violation message is appended here for each disallowed annotation
     */
    private static void checkType(
            String name,
            Class<?> declaringType,
            Class<?> typeInScope,
            boolean isDeclaringType,
            List<String> violations) {
        for (Annotation annotation : typeInScope.getDeclaredAnnotations()) {
            checkAnnotation(name, declaringType, typeInScope, isDeclaringType, annotation, violations);
        }
    }

    /**
     * Checks one annotation directly present on {@code typeInScope}, appending a violation message
     * when it is not allow-listed there.
     */
    private static void checkAnnotation(
            String name,
            Class<?> declaringType,
            Class<?> typeInScope,
            boolean isDeclaringType,
            Annotation annotation,
            List<String> violations) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        String packageName = annotationType.getPackageName();
        if ("java.lang".equals(packageName) || "java.lang.annotation".equals(packageName)) {
            return;
        }

        boolean isRestApplication = annotationType == RestApplication.class;
        boolean isApiDocs = ApiDocsInstalled.ANNOTATION_NAME.equals(annotationType.getName());
        boolean isOpenApiDefinition = annotationType == OpenAPIDefinition.class;
        boolean honoredOnly = isRestApplication || isApiDocs || isOpenApiDefinition;

        if (isDeclaringType) {
            if (isRestApplication || isApiDocs) {
                return;
            }
            if (isOpenApiDefinition) {
                if (isDefaultOpenApiDefinition((OpenAPIDefinition) annotation)) {
                    return;
                }
                violations.add(violation(name, declaringType, typeInScope, annotationType, true));
                return;
            }
            violations.add(violation(name, declaringType, typeInScope, annotationType, false));
            return;
        }

        if (honoredOnly) {
            violations.add(honoredOnlyOnDeclaringInterface(name, declaringType, typeInScope, annotationType));
            return;
        }
        violations.add(violation(name, declaringType, typeInScope, annotationType, false));
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
     * @param name           the application name
     * @param declaringType  the registration's declaring interface
     * @param typeInScope    the type carrying the offending annotation
     * @param annotationType the offending annotation's type
     * @param openApiSuffix  whether to append the {@code @OpenAPIDefinition}-only suffix
     * @return the violation message
     */
    private static String violation(
            String name,
            Class<?> declaringType,
            Class<?> typeInScope,
            Class<? extends Annotation> annotationType,
            boolean openApiSuffix) {
        String base = "Application '" + name + "' (" + declaringType.getName() + ") carries @"
                + annotationType.getName() + " on " + typeInScope.getName()
                + ", which is not allowed: application declarations carry no resource semantics";
        return openApiSuffix ? base + "; only its info element may be set" : base;
    }

    /**
     * Builds the violation message for an honored-only annotation ({@code @RestApplication}, the
     * {@code @ApiDocs}-named annotation, or {@code @OpenAPIDefinition}) found on a superinterface.
     *
     * @param name           the application name
     * @param declaringType  the registration's declaring interface
     * @param typeInScope    the superinterface carrying the offending annotation
     * @param annotationType the offending annotation's type
     * @return the violation message
     */
    private static String honoredOnlyOnDeclaringInterface(
            String name, Class<?> declaringType, Class<?> typeInScope, Class<? extends Annotation> annotationType) {
        return "Application '" + name + "' (" + declaringType.getName() + ") carries @" + annotationType.getName()
                + " on " + typeInScope.getName() + ", which is honored only on the declaring interface";
    }
}
