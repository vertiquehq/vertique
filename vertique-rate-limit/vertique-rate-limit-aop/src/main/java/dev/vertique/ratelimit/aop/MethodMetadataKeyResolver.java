// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Objects;

/**
 * Resolves ordered {@code @RateLimited(key = ...)} selector paths against method metadata and
 * live invocation arguments, using the same grammar as {@code vertique-cache-aop}'s
 * {@code MethodMetadataKeyResolver} (contracts/rate-limit-aop.md, "Selector path grammar"): each
 * path is a parameter root (name or position) plus optional record/bean accessor segments. This
 * copy is deliberately self-contained rather than shared — {@code vertique-rate-limit-aop} never
 * depends on {@code vertique-cache} (contracts/rate-limit-aop.md, precedent note).
 */
final class MethodMetadataKeyResolver {
    private MethodMetadataKeyResolver() {}

    static Object[] resolve(String[] paths, MethodMetadata metadata, Object[] arguments) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(arguments, "arguments");
        if (paths.length == 0) {
            throw new IllegalArgumentException("rate-limit key paths must not be empty");
        }
        Object[] values = new Object[paths.length];
        for (int index = 0; index < paths.length; index++) {
            values[index] = value(paths[index], metadata, arguments);
        }
        return values;
    }

    private static Object value(String path, MethodMetadata metadata, Object[] arguments) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("rate-limit key selector path must not be blank");
        }
        int dot = path.indexOf('.');
        String root = dot < 0 ? path : path.substring(0, dot);
        String remainder = dot < 0 ? "" : path.substring(dot + 1);
        int index = positionalIndex(root, metadata);
        if (index < 0) {
            index = namedIndex(root, metadata);
        }
        if (index < 0 || index >= arguments.length || arguments[index] == null) {
            throw new IllegalArgumentException("rate-limit key selector does not resolve to an argument: " + path);
        }
        Object current = arguments[index];
        for (String segment : remainder.isEmpty() ? new String[0] : remainder.split("\\.", -1)) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("rate-limit key property path contains a blank segment");
            }
            current = property(current, segment);
        }
        if (current == null) {
            throw new IllegalArgumentException("rate-limit key selector resolved to null: " + path);
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

    private static Object property(Object source, String name) {
        Class<?> type = source.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(name)) {
                    try {
                        return component.getAccessor().invoke(source);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalArgumentException("rate-limit key accessor cannot be invoked", exception);
                    }
                }
            }
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method accessor = type.getMethod("get" + suffix);
            return accessor.invoke(source);
        } catch (ReflectiveOperationException ignored) {
            try {
                Method accessor = type.getMethod("is" + suffix);
                return accessor.invoke(source);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException(
                        "rate-limit key property is not an accessible scalar path: " + name, exception);
            }
        }
    }
}
