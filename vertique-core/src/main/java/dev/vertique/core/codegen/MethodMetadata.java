// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * Neutral, runtime metadata view of a single method, implemented and consumed by codegen-generated
 * code.
 *
 * <p>The constant-only accessors ({@link #name()}, {@link #declaringType()}, {@link #returnType()},
 * {@link #parameterTypes()}, {@link #parameters()}, {@link #findAnnotation(Class)},
 * {@link #hasAnnotation(Class)}) form the <em>reflection-free core</em>: a generated implementation
 * returns compile-time-captured constants and never reflects at call time. Method-level annotation
 * lookups ({@link #findAnnotation(Class)}/{@link #hasAnnotation(Class)}) are backed by generated
 * annotation-literals: for each {@code @Retention(RUNTIME)} method annotation the emitter materializes
 * a processor-owned {@code <Ann>$<Namespace>Literal} constant and resolves
 * {@link #findAnnotation(Class)} by matching the
 * requested {@code Class} against each literal's {@link java.lang.annotation.Annotation#annotationType()
 * annotationType()} — never {@link Method#getAnnotation(Class)}. {@code SOURCE}/{@code CLASS}-retained
 * annotations are not part of this surface (they are invisible at runtime).
 *
 * <p>{@link #asMethod()} remains an opt-in reflective accessor. Generated metadata emits
 * {@link #genericReturnType()} as a reflection-free {@link Type} graph, so serializers and generated
 * interceptors can inspect declared payload types without a reflective method lookup. The accessor
 * remains separate from the constant-only core because its result can be a composite runtime
 * {@code Type} rather than a {@link Class} literal.
 *
 * <p>This SPI references no AOP or event type — it is a neutral runtime home shared across those
 * concerns.
 */
public interface MethodMetadata {

    // --- constant-only reflection-free core ---

    /**
     * Returns the simple name of the method.
     *
     * @return the method name
     */
    String name();

    /**
     * Returns the type that declares the method.
     *
     * @return the declaring type
     */
    Class<?> declaringType();

    /**
     * Returns the erased return type of the method.
     *
     * @return the erased return type
     */
    Class<?> returnType();

    /**
     * Returns the erased parameter types of the method, in declaration order.
     *
     * @return the erased parameter types
     */
    Class<?>[] parameterTypes();

    /**
     * Returns the ordered list of parameter metadata for the method.
     *
     * @return the parameter metadata, in declaration order
     */
    List<ParameterMetadata> parameters();

    /**
     * Looks up an annotation of the given type declared on the method.
     *
     * <p>Reflection-free and backed by generated annotation-literals: the generated implementation
     * matches {@code type} against each materialized {@code @Retention(RUNTIME)} method annotation's
     * {@link java.lang.annotation.Annotation#annotationType() annotationType()} and returns the
     * matching literal — it performs no reflective annotation read ({@link Method#getAnnotation(Class)}
     * is never called). Only runtime-retained method annotations are visible; a {@code SOURCE}- or
     * {@code CLASS}-retained annotation is never matched.
     *
     * @param type the annotation type to look up
     * @param <A> the annotation type
     * @return the annotation if present and runtime-retained, otherwise {@link Optional#empty()}
     */
    <A extends Annotation> Optional<A> findAnnotation(Class<A> type);

    /**
     * Reports whether an annotation of the given type is declared on the method.
     *
     * <p>Reflection-free and backed by generated annotation-literals: equivalent to
     * {@link #findAnnotation(Class)}{@code .isPresent()} over the method's runtime-retained
     * annotations, performing no reflective annotation read.
     *
     * @param type the annotation type to test for
     * @return {@code true} if the annotation is present and runtime-retained, {@code false} otherwise
     */
    boolean hasAnnotation(Class<? extends Annotation> type);

    // --- generic type / reflective method accessors ---

    /**
     * Returns the generic return type of the method.
     *
     * <p>Generated implementations return a reflection-free {@link Type} graph for declared
     * parameterized return types. Implementations that do not have compile-time metadata may still
     * derive the value reflectively.
     *
     * @return the generic return type
     */
    Type genericReturnType();

    /**
     * Returns the reflective {@link Method} token backing this metadata.
     *
     * <p>Part of the opt-in reflective-accessor group: this is not part of the reflection-free
     * guarantee and is never called by generated proxy code. Callers that invoke it accept the
     * reflection it entails.
     *
     * @return the reflective {@link Method}
     */
    Method asMethod();
}
