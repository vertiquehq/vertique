// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dev.vertique.core.validation.PropertyPath;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;

/**
 * Converts the Jakarta property path of a method-parameter violation into a {@link PropertyPath}
 * relative to the violated parameter value.
 *
 * <p>Only the nodes after the {@link ElementKind#PARAMETER} node are considered. A node inside an
 * iterable contributes an {@link PropertyPath.Index} when it has a position and a
 * {@link PropertyPath.Opaque} marker otherwise; the key of a map entry is never read. A property node
 * contributes a {@link PropertyPath.Property}; container-element and bean nodes contribute nothing
 * beyond their position.
 *
 * <p>The owner type of a property is the runtime class of the object hosting it. The first property
 * takes the class of the validated argument; when the argument is itself a {@link RandomAccess} list
 * or an array, the element reached through the leading positions is used instead (any other root
 * container yields no owner, so no sequential walk happens). Later properties take the class of the
 * value Hibernate Validator reports on the preceding node, which for a cascade into a collection
 * element is the element itself. The owner is {@code null} whenever it cannot be derived, including
 * when the validator is not Hibernate Validator. Values are only asked for their class and are never
 * retained.
 *
 * <p>The conversion never throws: any failure yields a {@code null} path.
 */
final class PropertyPathConverter {

    /**
     * The result of a conversion.
     *
     * @param parameterIndex the zero-based parameter index, or {@code -1} when it could not be determined
     * @param path           the path relative to the parameter value, or {@code null} when the
     *                       conversion failed
     */
    record Converted(int parameterIndex, @Nullable PropertyPath path) {}

    private PropertyPathConverter() {}

    /**
     * Converts the property path of a method-parameter violation.
     *
     * @param violation the constraint violation from executable validation
     * @return the parameter index and structured path; the path is {@code null} when conversion failed
     */
    static Converted convert(ConstraintViolation<?> violation) {
        int parameterIndex = -1;
        try {
            List<PropertyPath.Segment> segments = new ArrayList<>();
            boolean pastParameter = false;
            boolean propertySeen = false;
            Class<?> previousValueClass = null;
            for (Path.Node node : violation.getPropertyPath()) {
                ElementKind kind = node.getKind();
                if (kind == ElementKind.PARAMETER) {
                    parameterIndex = node.as(Path.ParameterNode.class).getParameterIndex();
                    pastParameter = true;
                    continue;
                }
                if (!pastParameter) {
                    continue;
                }
                if (node.isInIterable()) {
                    Integer index = node.getIndex();
                    segments.add(index != null ? new PropertyPath.Index(index) : new PropertyPath.Opaque());
                }
                if (kind == ElementKind.PROPERTY) {
                    Class<?> owner = propertySeen
                            ? previousValueClass
                            : rootOwner(violation.getExecutableParameters(), parameterIndex, segments);
                    segments.add(new PropertyPath.Property(node.getName(), owner));
                    propertySeen = true;
                }
                previousValueClass = valueClass(node, kind);
            }
            return new Converted(parameterIndex, new PropertyPath(segments));
        } catch (RuntimeException | StackOverflowError e) {
            return new Converted(parameterIndex, null);
        }
    }

    /**
     * The runtime class hosting the first property: the argument's class or, for a root random-access
     * list or array, the class of the element reached through the leading positions.
     */
    private static @Nullable Class<?> rootOwner(
            @Nullable Object[] arguments, int parameterIndex, List<PropertyPath.Segment> leading) {
        if (arguments == null || parameterIndex < 0 || parameterIndex >= arguments.length) {
            return null;
        }
        Object current = arguments[parameterIndex];
        for (PropertyPath.Segment segment : leading) {
            if (current == null || !(segment instanceof PropertyPath.Index position)) {
                return null;
            }
            current = elementAt(current, position.index());
        }
        return current == null ? null : current.getClass();
    }

    private static @Nullable Object elementAt(Object container, int index) {
        if (container.getClass().isArray()) {
            return index < Array.getLength(container) ? Array.get(container, index) : null;
        }
        if (container instanceof List<?> list && container instanceof RandomAccess) {
            return index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    /**
     * The runtime class of the value Hibernate Validator reports on a property or container-element
     * node, or {@code null} for other kinds, a {@code null} value or a node that is not Hibernate
     * Validator's.
     */
    private static @Nullable Class<?> valueClass(Path.Node node, ElementKind kind) {
        try {
            Object value;
            if (kind == ElementKind.PROPERTY) {
                value = node.as(org.hibernate.validator.path.PropertyNode.class).getValue();
            } else if (kind == ElementKind.CONTAINER_ELEMENT) {
                value = node.as(org.hibernate.validator.path.ContainerElementNode.class)
                        .getValue();
            } else {
                return null;
            }
            return value == null ? null : value.getClass();
        } catch (ClassCastException e) {
            return null;
        }
    }
}
