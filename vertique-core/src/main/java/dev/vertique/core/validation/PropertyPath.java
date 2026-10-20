// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * A Java-side path from a validated value to a violated element: property names, list and array
 * positions, and opaque markers where the position has no stable identity.
 *
 * <p>INTERNAL framework seam for sibling framework modules; outside the Stable maturity promise and
 * may change without a deprecation cycle. It is not an application extension point.
 *
 * <p>The path is mapper-independent and free of Jakarta Validation types. It never stores a map key,
 * a set member or any validated value; a property segment may record the runtime class of the object
 * that hosts it.
 *
 * <p>Immutable; segments are non-null and in order from the validated value. New segment kinds may be
 * added; a consumer treats a segment it does not recognize like {@link Opaque}. The type's name and
 * package are fixed for sibling framework modules; its contents may evolve.
 *
 * @param segments the steps from the validated value to the violated element, in order; empty for the
 *                 validated value itself
 */
public record PropertyPath(List<Segment> segments) {

    private static final PropertyPath ROOT = new PropertyPath(List.of());

    /**
     * Copies the list; rejects a null list and null elements.
     *
     * @throws NullPointerException if the list or any element is {@code null}
     */
    public PropertyPath {
        segments = List.copyOf(segments);
    }

    /**
     * The path with no segments: the validated value itself.
     *
     * @return the root path
     */
    public static PropertyPath root() {
        return ROOT;
    }

    /**
     * Whether this path has no segments.
     *
     * @return {@code true} for the root path
     */
    public boolean isRoot() {
        return segments.isEmpty();
    }

    /**
     * Renders the Java path: a property is its name, preceded by {@code .} unless it is the first
     * rendered token; an index is {@code [n]}; an opaque marker or unknown segment is {@code []}. The
     * root renders as the empty string. Examples: {@code items[0].zipCode}, {@code byKey[].name},
     * {@code lists[0][1]}, {@code [2].name}, {@code tags[]}.
     *
     * @return the Java path; never {@code null}
     */
    public String toJavaPath() {
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            if (segment instanceof Property property) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(property.name());
            } else if (segment instanceof Index index) {
                out.append('[').append(index.index()).append(']');
            } else {
                out.append("[]");
            }
        }
        return out.toString();
    }

    /** One step of a property path. Implemented only by the framework's nested records. */
    public interface Segment {}

    /**
     * A bean property by its Java name.
     *
     * @param name      the Java property name; non-null, non-empty
     * @param ownerType the runtime class of the object hosting the property when the producer knew it,
     *                  otherwise {@code null}; a consumer falls back to the declared type
     */
    public record Property(String name, @Nullable Class<?> ownerType) implements Segment {

        /**
         * Rejects a null or empty name.
         *
         * @throws IllegalArgumentException if the name is {@code null} or empty
         */
        public Property {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("property name must be non-empty");
            }
        }
    }

    /**
     * A zero-based list or array position.
     *
     * @param index the position; {@code index >= 0}
     */
    public record Index(int index) implements Segment {

        /**
         * Rejects a negative position.
         *
         * @throws IllegalArgumentException if {@code index} is negative
         */
        public Index {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative: " + index);
            }
        }
    }

    /** A position without a stable identity: a map entry (its key is never stored) or a set member. */
    public record Opaque() implements Segment {}
}
