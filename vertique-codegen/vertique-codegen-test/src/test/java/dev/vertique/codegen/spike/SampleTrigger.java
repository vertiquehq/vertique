// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.spike;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Sample multi-attribute trigger annotation used by the CODEGEN-013 Phase 0 slice 0.3
 * annotation-literal feasibility spike (OQ-2).
 *
 * <p>It deliberately covers the three attribute kinds a generated
 * {@code Xxx$<Namespace>Literal} must support to be a representative proof. The namespace is owned
 * by the emitting processor. The attributes are a {@code String} member, a primitive ({@code int}) member, and a
 * {@code Class<?>} member. The hand-written literal proven against this annotation establishes that
 * an emitter generating the identical shape in Phase 1/2 can satisfy the {@code
 * java.lang.annotation.Annotation} {@code equals}/{@code hashCode}/{@code annotationType} contract.
 *
 * <p>{@link RetentionPolicy#RUNTIME} retention is required so a reflectively-obtained instance exists
 * at test time to compare the literal against.
 */
@Retention(RetentionPolicy.RUNTIME)
@interface SampleTrigger {

    /**
     * A {@code String}-kinded member.
     *
     * @return the configured name, empty by default
     */
    String name() default "";

    /**
     * A primitive ({@code int})-kinded member.
     *
     * @return the configured order, {@code 0} by default
     */
    int order() default 0;

    /**
     * A {@code Class<?>}-kinded member.
     *
     * @return the configured type, {@code Object.class} by default
     */
    Class<?> type() default Object.class;
}
