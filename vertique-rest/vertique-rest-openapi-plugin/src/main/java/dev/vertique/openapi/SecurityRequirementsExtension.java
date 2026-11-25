// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import io.swagger.v3.jaxrs2.ext.AbstractOpenAPIExtension;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtension;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Swagger Core {@link OpenAPIExtension} that makes the security the generated document publishes for
 * an operation the security the runtime enforces.
 *
 * <p>Swagger's reader adds the class-level {@code @SecurityRequirement} requirements to every
 * operation and then appends the method's own, so the document lists both. The framework resolves one
 * level only: the requirements declared on the method when there are any, otherwise those declared on
 * the resource class. An empty {@code @SecurityRequirements({})} on the resource class or on the
 * method marks the operation public; it counts only when neither level declares a requirement. This
 * extension applies the same order to the generated {@link Operation}:
 *
 * <ul>
 *   <li>the method declares requirements ({@code @SecurityRequirement}, {@code @SecurityRequirements} or
 *       {@code @Operation(security = ...)}): they replace whatever the reader collected;</li>
 *   <li>otherwise the class declares requirements: the reader's result stays;</li>
 *   <li>otherwise an empty marker on the method or on the class that declares it: the operation gets
 *       an empty {@code security} list, which OpenAPI reads as public;</li>
 *   <li>otherwise the operation is left as the reader built it (no {@code security} member).</li>
 * </ul>
 *
 * <p>The requirements are read from the method and the declarations it overrides, as the runtime reads
 * them. The empty marker is read only from the method and the class that declares it: a marker on a
 * subclass of that class is not seen here, and one inherited from a superclass is not honoured by the
 * runtime either (it refuses to start).
 *
 * <p>Registered automatically via {@code ServiceLoader}, like {@link RequestParamsExtension}.
 */
public class SecurityRequirementsExtension extends AbstractOpenAPIExtension {

    /**
     * {@inheritDoc}
     *
     * <p>Applies the method-over-class order described on the class, then delegates to the next
     * extension in the chain.
     *
     * @param operation the operation the reader built for {@code method}
     * @param method the resource method
     * @param chain the remaining extension chain
     */
    @Override
    public void decorateOperation(
            io.swagger.v3.oas.models.Operation operation, Method method, Iterator<OpenAPIExtension> chain) {
        List<SecurityRequirement> methodLevel = methodLevelRequirements(method);
        if (!methodLevel.isEmpty()) {
            operation.setSecurity(methodLevel);
        } else if (isEmpty(operation.getSecurity()) && isPublicMarked(method)) {
            operation.setSecurity(new ArrayList<>());
        }
        super.decorateOperation(operation, method, chain);
    }

    private static boolean isEmpty(List<SecurityRequirement> requirements) {
        return requirements == null || requirements.isEmpty();
    }

    /**
     * The requirements declared on the method itself, one model requirement per annotation: standalone
     * {@code @SecurityRequirement}, the {@code @SecurityRequirements} container, and the entries of
     * {@code @Operation(security = ...)}, in declaration order, as the runtime reads them. The
     * declarations the method overrides count too.
     */
    private static List<SecurityRequirement> methodLevelRequirements(Method method) {
        List<SecurityRequirement> requirements = new ArrayList<>();
        for (Annotation annotation : declaredAnnotations(method)) {
            if (annotation instanceof io.swagger.v3.oas.annotations.security.SecurityRequirement single) {
                add(requirements, single);
            } else if (annotation instanceof SecurityRequirements container) {
                Arrays.stream(container.value()).forEach(entry -> add(requirements, entry));
            } else if (annotation instanceof Operation operation) {
                Arrays.stream(operation.security()).forEach(entry -> add(requirements, entry));
            }
        }
        return requirements;
    }

    /**
     * The annotations declared on the method and on every declaration it overrides, the method first,
     * then the superclass chain, then the interfaces, each annotation once: the set the runtime reads.
     */
    private static List<Annotation> declaredAnnotations(Method method) {
        List<Annotation> annotations = new ArrayList<>();
        for (Method declaration : declarations(method)) {
            for (Annotation annotation : declaration.getDeclaredAnnotations()) {
                if (!annotations.contains(annotation)) {
                    annotations.add(annotation);
                }
            }
        }
        return annotations;
    }

    private static List<Method> declarations(Method method) {
        List<Method> declarations = new ArrayList<>();
        declarations.add(method);
        List<Class<?>> interfaces = new ArrayList<>();
        for (Class<?> type = method.getDeclaringClass(); type != null; type = type.getSuperclass()) {
            if (type != method.getDeclaringClass()) {
                overridden(type, method).ifPresent(declarations::add);
            }
            interfaces.addAll(Arrays.asList(type.getInterfaces()));
        }
        for (int i = 0; i < interfaces.size(); i++) {
            Class<?> type = interfaces.get(i);
            overridden(type, method).ifPresent(declarations::add);
            interfaces.addAll(Arrays.asList(type.getInterfaces()));
        }
        return declarations;
    }

    private static java.util.Optional<Method> overridden(Class<?> type, Method method) {
        try {
            return java.util.Optional.of(type.getDeclaredMethod(method.getName(), method.getParameterTypes()));
        } catch (NoSuchMethodException e) {
            return java.util.Optional.empty();
        }
    }

    private static void add(
            List<SecurityRequirement> requirements,
            io.swagger.v3.oas.annotations.security.SecurityRequirement annotation) {
        SecurityRequirement requirement = new SecurityRequirement();
        if (annotation.combine().length > 0) {
            Arrays.stream(annotation.combine())
                    .forEach(entry -> requirement.addList(entry.name(), Arrays.asList(entry.scopes())));
        } else if (!annotation.name().isBlank()) {
            requirement.addList(annotation.name(), Arrays.asList(annotation.scopes()));
        }
        if (!requirement.isEmpty()) {
            requirements.add(requirement);
        }
    }

    /** Whether the method, or the class that declares it, carries an empty {@code @SecurityRequirements}. */
    private static boolean isPublicMarked(Method method) {
        return isPublicMarker(method.getDeclaredAnnotation(SecurityRequirements.class))
                || isPublicMarker(method.getDeclaringClass().getDeclaredAnnotation(SecurityRequirements.class));
    }

    private static boolean isPublicMarker(SecurityRequirements container) {
        return container != null && container.value().length == 0;
    }
}
