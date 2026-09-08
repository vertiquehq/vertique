// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Fluent helpers for working with {@link AnnotationMirror} instances in an annotation processing
 * environment.
 *
 * <p>The JDK APT API for reading annotation values is verbose and type-unsafe. This class wraps the
 * most common patterns — finding a mirror by annotation type, reading typed attribute values,
 * handling arrays, and resolving {@code Class}-typed attributes — behind simple, null-safe methods.
 *
 * <p>Bound to a single {@link Elements} utility. Obtain an instance via
 * {@link CodegenContext#annotations()}.
 */
public final class AnnotationMirrors {

    private final Elements elements;

    /**
     * Constructs an {@code AnnotationMirrors} helper bound to the given {@link Elements} utility.
     *
     * @param elements the element utilities from the processing environment; must not be
     *                 {@code null}
     */
    public AnnotationMirrors(Elements elements) {
        this.elements = elements;
    }

    /**
     * Finds the annotation mirror for the given annotation type on the given element.
     *
     * @param element         the element to search; must not be {@code null}
     * @param annotationType  the annotation class to look for; must not be {@code null}
     * @return an {@link Optional} containing the matching mirror, or empty if the annotation is
     *         not present
     */
    public Optional<AnnotationMirror> find(Element element, Class<? extends Annotation> annotationType) {
        return findByFqn(element, annotationType.getName());
    }

    /**
     * Finds an annotation mirror by its fully-qualified name without requiring the annotation
     * class to be loadable on the processor's runtime classpath.
     *
     * <p>Useful when the annotation type lives in a downstream module that is not a compile
     * dependency of the processor jar (e.g., {@code dev.vertique.kafka.KafkaListener} when used by
     * {@code vertique-codegen-dagger}). The class-based {@link #find(Element, Class)} overload
     * delegates to this method.
     *
     * @param element        the element to search; must not be {@code null}
     * @param annotationFqn  the fully-qualified name of the annotation; must not be {@code null}
     * @return an {@link Optional} containing the matching mirror, or empty if absent
     */
    public static Optional<AnnotationMirror> findByFqn(Element element, String annotationFqn) {
        return element.getAnnotationMirrors().stream()
                .filter(m -> {
                    var annotationElement = m.getAnnotationType().asElement();
                    if (annotationElement instanceof javax.lang.model.element.TypeElement typeEl) {
                        return typeEl.getQualifiedName().toString().equals(annotationFqn);
                    }
                    return annotationElement.toString().equals(annotationFqn);
                })
                .map(m -> (AnnotationMirror) m)
                .findFirst();
    }

    /**
     * Convenience predicate that returns {@code true} when the element carries the annotation
     * identified by the given fully-qualified name. Equivalent to
     * {@link #findByFqn(Element, String)}{@code .isPresent()}.
     *
     * @param element        the element to test; must not be {@code null}
     * @param annotationFqn  the fully-qualified annotation name; must not be {@code null}
     * @return {@code true} if the annotation is present
     */
    public static boolean isPresent(Element element, String annotationFqn) {
        return findByFqn(element, annotationFqn).isPresent();
    }

    /**
     * Reads a single named attribute from the given annotation mirror and attempts to cast it to
     * the requested type.
     *
     * <p>Handles the following {@link AnnotationValue} value types: {@link String},
     * {@link Integer} (also covers {@code byte}, {@code short}), {@link Long}, {@link Boolean},
     * {@link javax.lang.model.element.VariableElement} (enum constants), and
     * {@link AnnotationMirror} (nested annotations).
     *
     * @param mirror the annotation mirror to read from; must not be {@code null}
     * @param name   the attribute name to look up; must not be {@code null}
     * @param type   the expected value type; must not be {@code null}
     * @param <T>    the expected value type
     * @return an {@link Optional} containing the attribute value cast to {@code T}, or empty if
     *         the attribute is absent or the value is not an instance of {@code T}
     */
    public <T> Optional<T> attribute(AnnotationMirror mirror, String name, Class<T> type) {
        return values(mirror, name).filter(type::isInstance).map(type::cast).findFirst();
    }

    /**
     * Reads an array-typed attribute from the given annotation mirror.
     *
     * @param mirror the annotation mirror to read from; must not be {@code null}
     * @param name   the attribute name to look up; must not be {@code null}
     * @return the list of {@link AnnotationValue} elements if the attribute is present and is an
     *         array; an empty list otherwise
     */
    @SuppressWarnings("unchecked")
    public List<AnnotationValue> attributeArray(AnnotationMirror mirror, String name) {
        return values(mirror, name)
                .filter(v -> v instanceof List<?>)
                .map(v -> (List<AnnotationValue>) v)
                .findFirst()
                .orElse(List.of());
    }

    /**
     * Reads a {@code Class}-typed attribute from the given annotation mirror.
     *
     * <p>Annotation attributes that reference a class are represented at processing time as
     * {@link TypeMirror} values inside the {@link AnnotationValue} map; this method extracts the
     * mirror directly without needing the {@code MirroredTypeException} reflective fallback.
     *
     * @param mirror the annotation mirror to read from; must not be {@code null}
     * @param name   the attribute name to look up; must not be {@code null}
     * @return an {@link Optional} containing the {@link TypeMirror} for the class attribute, or
     *         empty if the attribute is absent
     */
    public Optional<TypeMirror> attributeClass(AnnotationMirror mirror, String name) {
        return values(mirror, name)
                .filter(TypeMirror.class::isInstance)
                .map(TypeMirror.class::cast)
                .findFirst();
    }

    /**
     * Returns a stream of attribute values for the named attribute on the given mirror, including
     * default values declared on the annotation type. Most attributes have a single value; arrays
     * appear here as a single {@link List} value.
     */
    private Stream<Object> values(AnnotationMirror mirror, String name) {
        return elements.getElementValuesWithDefaults(mirror).entrySet().stream()
                .filter(e -> e.getKey().getSimpleName().toString().equals(name))
                .map(e -> e.getValue().getValue());
    }

    /**
     * Returns the {@link Elements} utility this instance is bound to.
     *
     * @return the element utilities; never {@code null}
     */
    public Elements elements() {
        return elements;
    }
}
