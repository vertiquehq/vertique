// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Converts a Jakarta property-path string into an RFC 6901 JSON pointer over the wire names of the
 * supplied mapper.
 *
 * <p>INTERNAL framework seam for sibling framework modules that render constraint violations as JSON
 * pointers. It is not an application extension point and may change without a deprecation cycle.
 *
 * <p>The input is the path text produced for a constraint violation: nodes joined by {@code .}, each
 * node a property name with an optional {@code <...>} marker and an optional {@code [index]} or
 * {@code [key]} suffix. The string is scanned rather than split, because a map key may itself hold a
 * dot or a closing bracket.
 *
 * <ul>
 *   <li>The property name is the text up to the first {@code [} or {@code <}; a node that starts
 *       with {@code <} is a container-element marker and is dropped together with its suffix.
 *   <li>A bracket holding one or more digits is a sequence index only when the current property
 *       type resolves to a list, set, collection or array that is not map-like; the pointer then
 *       continues into the element type. In every other case the pointer ends at the property and
 *       the rest of the path is ignored: empty or non-numeric bracket content, a map, and a property
 *       whose type is unresolved, ignored, without accessors, {@code Object}, a scalar or a bean. A
 *       numeric map key is therefore never taken for an index, and a caller-chosen key never reaches
 *       the output. A bracket closes only at a {@code ]} that is followed by {@code .}, {@code [} or
 *       the end of the string.
 *   <li>Each property name is mapped to its wire name along the type path. A segment that cannot be
 *       resolved keeps its Java name and ends type tracking, and the remaining segments keep their
 *       Java names too.
 * </ul>
 *
 * <p>The mapper is supplied by the caller and never held in a static field, so a route or a process can
 * pass the one that binds its payloads. The conversion never throws for odd input: it yields the
 * pointer built so far.
 */
public final class JakartaPathPointers {

    private JakartaPathPointers() {}

    /**
     * Converts a property path into a JSON pointer.
     *
     * @param jakartaPath the property-path string of a constraint violation; may be empty
     * @param rootType the type the path starts at; may be {@code null} when unknown
     * @param mapper the mapper whose property naming decides the wire names
     * @return the RFC 6901 pointer, or the empty string for an empty path
     */
    public static String toPointer(String jakartaPath, Type rootType, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (jakartaPath == null || jakartaPath.isEmpty()) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        try {
            JavaType current = rootType == null ? null : mapper.constructType(rootType);
            int length = jakartaPath.length();
            int pos = 0;
            while (pos < length) {
                char first = jakartaPath.charAt(pos);
                if (first == '.') {
                    pos++;
                    continue;
                }
                if (first == '<') {
                    pos = skipDroppedNode(jakartaPath, pos);
                    continue;
                }
                int nameEnd = pos;
                while (nameEnd < length && !isNameTerminator(jakartaPath.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (nameEnd == pos) {
                    // A bracket with no property in front of it: nothing safe to emit.
                    break;
                }
                String javaName = jakartaPath.substring(pos, nameEnd);
                BeanPropertyDefinition property = findProperty(mapper, current, javaName);
                String wireName = property == null ? javaName : property.getName();
                JavaType propertyType = property == null ? null : propertyType(property);
                pointer.append('/').append(escape(wireName));
                pos = nameEnd;
                if (pos < length && jakartaPath.charAt(pos) == '<') {
                    pos = skipMarker(jakartaPath, pos);
                }
                current = propertyType;
                boolean stopped = false;
                while (pos < length && jakartaPath.charAt(pos) == '[') {
                    int close = findClose(jakartaPath, pos + 1);
                    if (close < 0) {
                        stopped = true;
                        break;
                    }
                    String content = jakartaPath.substring(pos + 1, close);
                    if (!isIndex(content) || !isSequenceType(current)) {
                        stopped = true;
                        break;
                    }
                    pointer.append('/').append(content);
                    current = current.getContentType();
                    pos = close + 1;
                }
                if (stopped) {
                    break;
                }
                if (pos < length && jakartaPath.charAt(pos) != '.') {
                    break;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            // Odd input or an unintrospectable type: keep the pointer built so far.
        }
        return pointer.toString();
    }

    private static boolean isNameTerminator(char c) {
        return c == '.' || c == '[' || c == '<';
    }

    /** Skips a node that starts with a marker, including any bracket suffix, up to the next separator. */
    private static int skipDroppedNode(String path, int pos) {
        int next = skipMarker(path, pos);
        while (next < path.length() && path.charAt(next) == '[') {
            int close = findClose(path, next + 1);
            if (close < 0) {
                return path.length();
            }
            next = close + 1;
        }
        return next;
    }

    /** Returns the position after the {@code >} that closes the marker at {@code pos}, or the end. */
    private static int skipMarker(String path, int pos) {
        int close = path.indexOf('>', pos);
        return close < 0 ? path.length() : close + 1;
    }

    /** Returns the index of the {@code ]} closing a bracket that opened before {@code from}, or -1. */
    private static int findClose(String path, int from) {
        int length = path.length();
        for (int i = from; i < length; i++) {
            if (path.charAt(i) == ']') {
                int after = i + 1;
                if (after == length || path.charAt(after) == '.' || path.charAt(after) == '[') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isIndex(String content) {
        if (content.isEmpty()) {
            return false;
        }
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static BeanPropertyDefinition findProperty(ObjectMapper mapper, JavaType type, String javaName) {
        if (type == null) {
            return null;
        }
        try {
            for (BeanPropertyDefinition candidate :
                    mapper.getDeserializationConfig().introspect(type).findProperties()) {
                if (javaName.equals(candidate.getInternalName())) {
                    return candidate;
                }
            }
        } catch (RuntimeException e) {
            // Not introspectable: the caller keeps the Java name.
        }
        return null;
    }

    private static JavaType propertyType(BeanPropertyDefinition property) {
        try {
            return property.getPrimaryType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Returns whether the type is a resolved list, set, collection or array, and not map-like. */
    private static boolean isSequenceType(JavaType type) {
        return type != null && !type.isMapLikeType() && (type.isArrayType() || type.isCollectionLikeType());
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
