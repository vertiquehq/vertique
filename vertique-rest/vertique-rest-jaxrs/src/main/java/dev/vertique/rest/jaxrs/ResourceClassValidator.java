// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.context.RestContextMessages;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup checks on a resource class as a whole, as opposed to one of its methods. They run for every
 * resource, whether it is scanned reflectively or described by generated metadata, so the two paths
 * fail the same way; the annotation processor reports the same conditions at compile time.
 *
 * <ul>
 *   <li><b>{@code @Context} on a member.</b> The framework injects {@code @Context} only into
 *       resource-method parameters; the resource is built by the application's dependency graph. A
 *       {@code @Context} field or constructor parameter, on the resource or any superclass, would
 *       stay unset.</li>
 *   <li><b>Conflicting interface paths.</b> When neither the class nor a superclass declares
 *       {@code @Path}, the class-level path comes from the interfaces. Two interfaces with different
 *       values leave no way to choose, so the resource is rejected.</li>
 * </ul>
 *
 * <p>A class with no {@code @Path} anywhere in its hierarchy is not a root resource and is not
 * checked. All methods are static; this class is not intended to be instantiated.
 */
final class ResourceClassValidator {

    private ResourceClassValidator() {}

    /**
     * Checks one resource class.
     *
     * @param resourceClass the runtime class of a registered resource
     * @return the violations found; empty when the class is acceptable or is not a root resource
     */
    static List<RouteRegistrationViolation> validate(Class<?> resourceClass) {
        List<RouteRegistrationViolation> violations = new ArrayList<>();
        String declaredPath = classChainPath(resourceClass);
        Map<Class<?>, String> interfacePaths = interfacePaths(resourceClass);
        if (declaredPath == null && interfacePaths.isEmpty()) {
            return violations;
        }
        if (declaredPath == null) {
            addConflictingInterfacePaths(resourceClass, interfacePaths, violations);
        }
        try {
            addContextMembers(resourceClass, violations);
        } catch (LinkageError e) {
            // A field or constructor parameter whose type is missing at runtime (an optional dependency)
            // cannot carry a framework-injected value either; reading it must not fail startup.
        }
        return violations;
    }

    @Nullable
    private static String classChainPath(Class<?> resourceClass) {
        for (Class<?> current = resourceClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            Path path = current.getAnnotation(Path.class);
            if (path != null) {
                return path.value();
            }
        }
        return null;
    }

    private static Map<Class<?>, String> interfacePaths(Class<?> resourceClass) {
        Map<Class<?>, String> paths = new LinkedHashMap<>();
        for (Class<?> iface : TypeResolver.getAllInterfaces(resourceClass)) {
            Path path = iface.getAnnotation(Path.class);
            if (path != null) {
                paths.put(iface, path.value());
            }
        }
        return paths;
    }

    private static void addConflictingInterfacePaths(
            Class<?> resourceClass, Map<Class<?>, String> interfacePaths, List<RouteRegistrationViolation> violations) {
        Class<?> firstInterface = null;
        String firstPath = null;
        for (Map.Entry<Class<?>, String> entry : interfacePaths.entrySet()) {
            if (firstPath == null) {
                firstInterface = entry.getKey();
                firstPath = entry.getValue();
            } else if (!firstPath.equals(entry.getValue())) {
                violations.add(new RouteRegistrationViolation(
                        resourceClass.getName(),
                        RouteRegistrationViolation.ViolationType.CONFLICTING_CLASS_PATH,
                        String.format(
                                "Class %s implements two interfaces with conflicting @Path declarations: %s (%s) vs %s (%s);"
                                        + " declare @Path directly on the implementing class to resolve",
                                resourceClass.getSimpleName(),
                                firstInterface.getName(),
                                firstPath,
                                entry.getKey().getName(),
                                entry.getValue())));
                return;
            }
        }
    }

    private static void addContextMembers(Class<?> resourceClass, List<RouteRegistrationViolation> violations) {
        for (Class<?> current = resourceClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Context.class)) {
                    violations.add(contextViolation(resourceClass, current, "field '" + field.getName() + "'"));
                }
            }
            for (Constructor<?> constructor : current.getDeclaredConstructors()) {
                Parameter[] parameters = constructor.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (parameters[i].isAnnotationPresent(Context.class)) {
                        violations.add(
                                contextViolation(resourceClass, current, "parameter " + (i + 1) + " of a constructor"));
                    }
                }
            }
        }
    }

    private static RouteRegistrationViolation contextViolation(
            Class<?> resourceClass, Class<?> declaring, String memberDescription) {
        return new RouteRegistrationViolation(
                resourceClass.getName(),
                RouteRegistrationViolation.ViolationType.CONTEXT_ON_RESOURCE_MEMBER,
                RestContextMessages.contextOnResourceMember(declaring.getSimpleName(), memberDescription));
    }
}
