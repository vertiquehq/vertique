// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Neutral, runtime metadata view of a single method parameter, implemented and consumed by
 * codegen-generated code.
 *
 * <p>The constant accessors ({@link #index()}, {@link #name()}, and {@link #type()}) return
 * compile-time-captured values in generated implementations. The parameter name is captured from
 * the {@code VariableElement} at compile time, so it is available without the {@code -parameters}
 * javac flag. Parameter-level annotation lookups ({@link #findAnnotation(Class)}/
 * {@link #hasAnnotation(Class)}) are literal-first: an emitter can bake a processor-owned
 * {@code <Ann>$<Namespace>Literal} constant for each renderable
 * {@code @Retention(RUNTIME)} parameter annotation and resolve the lookup by
 * {@code annotationType()} match, with no reflective read — mirroring the method-level lookup on
 * {@link MethodMetadata}. A consumer may explicitly provide a reflective fallback for annotation
 * shapes the emitter cannot render; the JAX-RS codegen path does this lazily, whereas AOP rejects
 * unsupported shapes at compile time.
 *
 * <p>{@link #genericType()} and {@link #annotationsLazy()} form the opt-in
 * <em>reflective-accessor group</em>. They are <strong>not</strong> part of the reflection-free
 * guarantee and are never called by generated proxy code; a consumer that needs the generic
 * parameter type or the parameter's full annotation array must opt into them explicitly, accepting
 * the reflection they entail.
 *
 * <p>This SPI references no AOP or event type — it is a neutral runtime home shared across those
 * concerns.
 */
public interface ParameterMetadata {

    // --- constant-only reflection-free core ---

    /**
     * Returns the zero-based position of the parameter in its method's parameter list.
     *
     * @return the parameter index
     */
    int index();

    /**
     * Returns the compile-time-captured name of the parameter.
     *
     * @return the parameter name
     */
    String name();

    /**
     * Returns the erased type of the parameter.
     *
     * @return the erased parameter type
     */
    Class<?> type();

    /**
     * Looks up an annotation of the given type declared on the parameter.
     *
     * <p>Backed by generated annotation-literals: the emitter bakes a processor-owned
     * {@code <Ann>$<Namespace>Literal} constant per renderable
     * {@code @Retention(RUNTIME)} parameter annotation and resolves the lookup by
     * {@code annotationType()} match. Consumers may supply an explicit fallback for annotations
     * that cannot be literal-backed; see the interface-level contract.
     *
     * @param type the annotation type to look up
     * @param <A> the annotation type
     * @return the annotation if present, otherwise {@link Optional#empty()}
     */
    <A extends Annotation> Optional<A> findAnnotation(Class<A> type);

    /**
     * Reports whether an annotation of the given type is declared on the parameter.
     *
     * <p>Uses the same literal-first policy as {@link #findAnnotation(Class)}, including any
     * consumer-specific fallback documented by the implementation.
     *
     * @param type the annotation type to test for
     * @return {@code true} if the annotation is present, {@code false} otherwise
     */
    boolean hasAnnotation(Class<? extends Annotation> type);

    // --- reflective-accessor group (opt-in; NOT part of the reflection-free guarantee) ---

    /**
     * Returns the generic type of the parameter.
     *
     * <p>Part of the opt-in reflective-accessor group: this is not part of the reflection-free
     * guarantee and is never called by generated proxy code.
     *
     * @return the generic parameter type
     */
    Type genericType();

    /**
     * Returns a supplier of the parameter's full declared annotation array.
     *
     * <p>Part of the opt-in reflective-accessor group: this is not part of the reflection-free
     * guarantee and is never called by generated proxy code. It exists for the JAX-RS bridge, which
     * consumes the full annotation array (wired in Phase 3); the default returns an empty array so
     * existing implementations keep compiling without overriding it.
     *
     * @return a supplier of the parameter's annotations (an empty array by default)
     */
    default Supplier<Annotation[]> annotationsLazy() {
        return () -> new Annotation[0];
    }
}
