// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Scans a JAX-RS composite parameter type ({@code @BeanParam} or {@code @RequestParams}) for
 * the {@code @PathParam} names contributed by its fields and record components. Single source
 * of truth for both rest-client codegen and jaxrs APT validators.
 *
 * <p>Two scan strategies are used depending on the type kind:
 * <ul>
 *   <li><strong>Record</strong> ({@code ElementKind.RECORD}) &mdash; iterates record components;
 *       because JAX-RS annotations carry {@code @Target({FIELD, METHOD, PARAMETER})} but NOT
 *       {@code RECORD_COMPONENT}, they land on the synthetic <em>accessor method</em>, not on the
 *       component element itself. The accessor is therefore checked first, falling back to the
 *       component element for any annotation that does target {@code RECORD_COMPONENT}.</li>
 *   <li><strong>Class</strong> &mdash; walks the declared fields on the type element and its
 *       superclass chain up to (but not including) {@code Object}. Only non-static fields are
 *       scanned. A name seen in a more-derived class hides the same name from a superclass entry,
 *       mirroring Java field-hiding semantics.</li>
 * </ul>
 */
public final class JaxRsBeanScanner {

    private final CodegenContext ctx;

    /**
     * Creates a new scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public JaxRsBeanScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the {@code @PathParam("name")} values declared on fields or record-component
     * accessors of {@code beanType}. Walks the superclass chain for class types.
     *
     * <p>Returns an empty set if no path params are present.
     *
     * @param beanType the type element to scan; must not be {@code null}
     * @return an insertion-ordered, deduplicated set of {@code @PathParam} name values
     */
    public Set<String> pathParamNames(TypeElement beanType) {
        if (beanType.getKind() == ElementKind.RECORD) {
            return scanRecord(beanType);
        }
        return scanClass(beanType);
    }

    // --- Private scanning ---

    /**
     * Scans a record type for {@code @PathParam} annotations on component accessors.
     *
     * <p>JAX-RS annotations like {@code @PathParam} carry {@code @Target({FIELD, METHOD, PARAMETER})}
     * but NOT {@code RECORD_COMPONENT}, so they land on the synthetic accessor method rather than
     * on the {@link RecordComponentElement} itself. This method checks the accessor first, then
     * falls back to the component element.
     *
     * @param beanType the record type element
     * @return ordered set of {@code @PathParam} name values found in the record
     */
    private Set<String> scanRecord(TypeElement beanType) {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponentElement component : beanType.getRecordComponents()) {
            ExecutableElement accessor = component.getAccessor();
            // Check accessor method first — that's where @PathParam lands because its
            // @Target includes METHOD but not RECORD_COMPONENT
            String name = resolvePathParamName(accessor);
            if (name == null) {
                // Fallback: some annotations do target RECORD_COMPONENT
                name = resolvePathParamName((Element) component);
            }
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Scans a class type for {@code @PathParam} annotations on non-static fields. Walks the
     * superclass chain up to (but not including) {@code Object}. More-derived declarations of the
     * same field name hide superclass entries.
     *
     * @param beanType the class type element
     * @return ordered set of {@code @PathParam} name values found in the class hierarchy
     */
    private Set<String> scanClass(TypeElement beanType) {
        Set<String> declaredNames = new java.util.HashSet<>();
        Set<String> pathParamNames = new LinkedHashSet<>();

        TypeElement current = beanType;
        while (current != null && !isObjectType(current)) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                VariableElement field = (VariableElement) enclosed;
                if (field.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                String javaName = field.getSimpleName().toString();
                if (!declaredNames.add(javaName)) {
                    // A more-derived declaration already reserved this name — skip.
                    continue;
                }
                String paramName = resolvePathParamName(field);
                if (paramName != null) {
                    pathParamNames.add(paramName);
                }
            }
            current = superclassElement(current);
        }
        return pathParamNames;
    }

    // --- Annotation helpers ---

    /**
     * Returns the {@code value()} attribute of {@code @PathParam} on the given element, or
     * {@code null} if the annotation is not present.
     *
     * <p>The literal annotation value is returned as-is, including blank strings. This matches
     * runtime {@code ParameterExtractor} behaviour (composite/bean paths) and the direct-param
     * branch in {@code PathParamAlignmentValidator} (which reads {@code @PathParam.value()} without
     * a fallback). A blank value such as {@code @PathParam("")} will appear in the candidate set
     * as {@code ""}, causing the bidirectional alignment check to emit a
     * "missing placeholder" diagnostic — which is the correct signal for a malformed annotation.
     *
     * @param element the element to inspect (field, record component, or accessor method)
     * @return the literal {@code @PathParam} value, or {@code null} if the annotation is absent
     */
    private String resolvePathParamName(Element element) {
        return AnnotationMirrors.findByFqn(element, JaxRsAnnotations.PATH_PARAM)
                .flatMap(mirror -> ctx.annotations().attribute(mirror, "value", String.class))
                .orElse(null);
    }

    // --- Type helpers ---

    /**
     * Returns the superclass as a {@link TypeElement}, or {@code null} if the superclass is
     * {@code Object} or unavailable.
     *
     * @param type the type element whose superclass is needed
     * @return the superclass element, or {@code null}
     */
    private TypeElement superclassElement(TypeElement type) {
        return ctx.asTypeElement(type.getSuperclass())
                .filter(te -> !isObjectType(te))
                .orElse(null);
    }

    /**
     * Returns {@code true} when the type element represents {@code java.lang.Object}.
     *
     * @param type the type element to check
     * @return {@code true} if this is {@code Object}
     */
    private boolean isObjectType(TypeElement type) {
        return "java.lang.Object".equals(type.getQualifiedName().toString());
    }
}
