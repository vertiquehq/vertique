// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Objects;

/** Resolves ordered selector paths against method metadata and live arguments. */
public final class SelectorPaths {
    private SelectorPaths() {}

    /**
     * Resolves ordered selector paths against method metadata and live invocation arguments.
     *
     * @param family diagnostic prefix, for example {@code "cache key"} or
     *     {@code "rate-limit key"}
     * @param paths ordered selector paths
     * @param metadata intercepted method metadata
     * @param arguments live invocation arguments
     * @return the resolved selector values in path order
     * @throws IllegalArgumentException when a selector path is invalid or cannot be resolved
     */
    public static Object[] resolve(String family, String[] paths, MethodMetadata metadata, Object[] arguments) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(arguments, "arguments");
        if (paths.length == 0) {
            throw new IllegalArgumentException(family + " paths must not be empty");
        }
        Object[] values = new Object[paths.length];
        for (int index = 0; index < paths.length; index++) {
            values[index] = value(family, paths[index], metadata, arguments);
        }
        return values;
    }

    private static Object value(String family, String path, MethodMetadata metadata, Object[] arguments) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(family + " selector path must not be blank");
        }
        int dot = path.indexOf('.');
        String root = dot < 0 ? path : path.substring(0, dot);
        String remainder = dot < 0 ? "" : path.substring(dot + 1);
        int index = positionalIndex(root, metadata);
        if (index < 0) {
            index = namedIndex(root, metadata);
        }
        if (index < 0 || index >= arguments.length || arguments[index] == null) {
            throw new IllegalArgumentException(family + " selector does not resolve to an argument: " + path);
        }
        Object current = arguments[index];
        for (String segment : remainder.isEmpty() ? new String[0] : remainder.split("\\.", -1)) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException(family + " property path contains a blank segment");
            }
            current = property(family, current, segment);
        }
        if (current == null) {
            throw new IllegalArgumentException(family + " selector resolved to null: " + path);
        }
        return current;
    }

    private static int positionalIndex(String root, MethodMetadata metadata) {
        try {
            int index = Integer.parseInt(root);
            return index >= 0 && index < metadata.parameters().size() ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int namedIndex(String root, MethodMetadata metadata) {
        for (ParameterMetadata parameter : metadata.parameters()) {
            if (root.equals(parameter.name())) {
                return parameter.index();
            }
        }
        return -1;
    }

    private static Object property(String family, Object source, String name) {
        Class<?> type = source.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    try {
                        return invoke(component.getAccessor(), source);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalArgumentException(family + " accessor cannot be invoked", exception);
                    }
                }
            }
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method accessor = type.getMethod("get" + suffix);
            return invoke(accessor, source);
        } catch (ReflectiveOperationException ignored) {
            try {
                Method accessor = type.getMethod("is" + suffix);
                return invoke(accessor, source);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException(
                        family + " property is not an accessible scalar path: " + name, exception);
            }
        }
    }

    private static Object invoke(Method accessor, Object source) throws ReflectiveOperationException {
        if (!accessor.canAccess(source)) {
            accessor.trySetAccessible();
        }
        return accessor.invoke(source);
    }
}
