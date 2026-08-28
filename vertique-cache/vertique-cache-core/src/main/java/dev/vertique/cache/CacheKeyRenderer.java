// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Renders the deliberately small, non-expression cache-key template language. */
public final class CacheKeyRenderer {
    private CacheKeyRenderer() {}

    /** Renders positional or named scalar selectors from a generated method metadata view. */
    public static String render(String template, MethodMetadata metadata, Object[] arguments) {
        return render(template, metadata, arguments, CacheKeyRenderer::encode);
    }

    /** Renders selectors using the caller's canonical scalar encoding policy. */
    static String renderCanonical(
            String template,
            MethodMetadata metadata,
            Object[] arguments,
            java.util.function.Function<Object, String> encoder) {
        return render(template, metadata, arguments, encoder);
    }

    private static String render(
            String template,
            MethodMetadata metadata,
            Object[] arguments,
            java.util.function.Function<Object, String> encoder) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(encoder, "encoder");
        StringBuilder output = new StringBuilder(template.length());
        for (int index = 0; index < template.length(); index++) {
            char character = template.charAt(index);
            if (character == '{') {
                if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
                    output.append('{');
                    index++;
                    continue;
                }
                int end = template.indexOf('}', index + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("cache key contains an unmatched '{'");
                }
                String selector = template.substring(index + 1, end);
                output.append(encoder.apply(value(selector, metadata, arguments)));
                index = end;
            } else if (character == '}') {
                if (index + 1 < template.length() && template.charAt(index + 1) == '}') {
                    output.append('}');
                    index++;
                } else {
                    throw new IllegalArgumentException("cache key contains an unmatched '}'");
                }
            } else {
                if (!isLiteralCharacter(character)) {
                    throw new IllegalArgumentException("cache key contains an unsupported literal character");
                }
                output.append(character);
            }
        }
        return output.toString();
    }

    private static Object value(String selector, MethodMetadata metadata, Object[] arguments) {
        if (selector.isBlank()) {
            throw new IllegalArgumentException("cache key selector must not be blank");
        }
        int dot = selector.indexOf('.');
        String root = dot < 0 ? selector : selector.substring(0, dot);
        String path = dot < 0 ? "" : selector.substring(dot + 1);
        int index = positionalIndex(root, metadata);
        if (index < 0) {
            index = namedIndex(root, metadata);
        }
        if (index < 0 || index >= arguments.length || arguments[index] == null) {
            throw new IllegalArgumentException("cache key selector does not resolve to an argument: " + selector);
        }
        Object current = arguments[index];
        for (String segment : path.isEmpty() ? new String[0] : path.split("\\.", -1)) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("cache key property path contains a blank segment");
            }
            current = property(current, segment);
        }
        if (current == null || !isScalar(current)) {
            throw new IllegalArgumentException("cache key selector must end in a scalar value");
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
                        throw new IllegalArgumentException("cache key accessor cannot be invoked", exception);
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
                        "cache key property is not an accessible scalar path: " + name, exception);
            }
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Character
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof Enum<?>
                || value instanceof java.util.UUID
                || value instanceof java.time.temporal.TemporalAccessor;
    }

    private static String encode(Object value) {
        String text = value instanceof Enum<?> enumeration
                ? enumeration.name().toLowerCase(Locale.ROOT)
                : String.valueOf(value);
        text = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder encoded = new StringBuilder(text.length());
        for (byte octet : text.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = octet & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '.'
                    || unsigned == '-'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isLiteralCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.'
                || character == '_'
                || character == '~'
                || character == ':'
                || character == '/'
                || character == '-'
                || character == '='
                || character == '%';
    }
}
