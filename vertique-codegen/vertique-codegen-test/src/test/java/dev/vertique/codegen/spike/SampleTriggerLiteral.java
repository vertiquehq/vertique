// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.spike;

/**
 * Hand-written, contract-correct annotation literal for {@link SampleTrigger}, the GREEN deliverable
 * of the CODEGEN-013 Phase 0 slice 0.3 annotation-literal feasibility spike (OQ-2).
 *
 * <p>This class is the <strong>spike proof for OQ-2 branch (a)</strong>: it demonstrates that a
 * generated {@code Xxx$Literal implements Xxx} built from compile-time constants — with no {@code
 * getAnnotation} reflection at call time — can satisfy the {@link java.lang.annotation.Annotation}
 * contract well enough to equal a reflectively-obtained instance (the JDK's dynamic proxy) and serve
 * as a {@code Set}/{@code Map} key interchangeably with it.
 *
 * <p>The Phase-1/2 {@code AnnotationLiteralEmitter} MUST generate exactly this shape for every
 * materialized trigger annotation:
 *
 * <ul>
 *   <li>{@link #annotationType()} returns the annotation type's {@code Class} literal;
 *   <li>one accessor method per annotation member returning the stored compile-time constant;
 *   <li>{@link #equals(Object)} and {@link #hashCode()} implemented to the {@code
 *       java.lang.annotation.Annotation} specification, so a generated literal is byte-for-byte
 *       interchangeable with the reflective proxy in hash-based collections.
 * </ul>
 *
 * <p><strong>equals</strong> is defined against the {@link SampleTrigger} interface (not against
 * {@code SampleTriggerLiteral}) so the literal equals the JDK proxy, preserving symmetry with the
 * proxy's own {@code AnnotationInvocationHandler.equals}.
 *
 * <p><strong>hashCode</strong> follows the {@link java.lang.annotation.Annotation#hashCode()}
 * contract: the sum, over every member, of {@code (127 * memberName.hashCode()) ^ memberValueHash}.
 *
 * @see SampleTrigger
 * @see SampleTriggerHolder
 * @see AnnotationLiteralSpikeTest
 */
final class SampleTriggerLiteral implements SampleTrigger {

    // --- Stored compile-time constants (one per annotation member) ---

    private final String name;
    private final int order;
    private final Class<?> type;

    /**
     * Constructs a literal carrying the concrete values an emitter would bake in as compile-time
     * constants from the annotation's declared member values.
     *
     * @param name the {@code name} member value
     * @param order the {@code order} member value
     * @param type the {@code type} member value
     */
    SampleTriggerLiteral(String name, int order, Class<?> type) {
        this.name = name;
        this.order = order;
        this.type = type;
    }

    // --- Member accessors (one per annotation member) ---

    @Override
    public String name() {
        return name;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Class<?> type() {
        return type;
    }

    // --- Annotation contract: annotationType / equals / hashCode ---

    @Override
    public Class<? extends java.lang.annotation.Annotation> annotationType() {
        return SampleTrigger.class;
    }

    /**
     * Implements {@link java.lang.annotation.Annotation#equals(Object)}: equal to any {@link
     * SampleTrigger} (including the JDK reflective proxy) whose members are all equal.
     *
     * @param o the object to compare against
     * @return {@code true} iff {@code o} is a {@code SampleTrigger} with equal members
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SampleTrigger other)) {
            return false;
        }
        return name.equals(other.name()) && order == other.order() && type.equals(other.type());
    }

    /**
     * Implements {@link java.lang.annotation.Annotation#hashCode()}: the sum, over every member, of
     * {@code (127 * memberName.hashCode()) ^ memberValueHashCode}. The {@code int} member's value
     * hash is the hash of its boxed {@link Integer}, per the spec.
     *
     * @return the Annotation-spec hash code, equal to the reflective proxy's hash code
     */
    @Override
    public int hashCode() {
        return ((127 * "name".hashCode()) ^ name.hashCode())
                + ((127 * "order".hashCode()) ^ Integer.valueOf(order).hashCode())
                + ((127 * "type".hashCode()) ^ type.hashCode());
    }
}
