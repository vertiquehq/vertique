// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * Reusable structured-input processing engine for canonicalization and sanitization.
 *
 * <p>Operates on intermediate tree/map structures (typically a {@code Map<String, Object>}
 * produced by JSON parsing) before final DTO materialization. Transports and custom decoders
 * can invoke this contract to reuse the framework's canonicalization and sanitization engine
 * without taking on Bean Validation responsibility.
 *
 * <p>This contract is transform-only — it does NOT invoke Bean Validation.
 *
 * <p>Example usage from a custom decoder:
 * <pre>{@code
 * Object intermediate = jsonObject.getMap();
 * Object processed = processor.processInput(
 *         intermediate, MyDto.class, policies, InputLocation.BODY, nameResolver);
 * MyDto dto = objectMapper.convertValue(processed, MyDto.class);
 * }</pre>
 *
 * @see EffectiveInputPolicies
 * @see InputFieldNameResolver
 */
public interface InputObjectProcessor {

    /**
     * Creates the default processing engine: a reflective walker with a generated-processor fast
     * path that delegates to {@code {DTO}_InputProcessor} classes when they are present on the
     * consuming type's classloader.
     *
     * <p>The returned engine owns the construction of its internal annotation-metadata resolver
     * and its per-type metadata cache, so callers hold only the resolver functions that produce
     * canonicalizer and sanitizer instances (typically backed by dependency injection).
     *
     * <p>The engine consults each resolver function at most once per processor class and reuses the
     * returned instance for every value it processes, so a resolver need not cache anything itself;
     * a resolution that fails is cached too and rethrown on every later use of that class. Both
     * caches are owned by the returned engine and die with it. A resolver must therefore return an
     * instance safe to share across requests and threads, and may be invoked more than once for the
     * same class when several threads race on a cold entry.
     *
     * @param canonicalizerResolver factory that produces canonicalizer instances by class;
     *                              must not be {@code null}
     * @param sanitizerResolver     factory that produces sanitizer instances by class;
     *                              must not be {@code null}
     * @return a new default {@code InputObjectProcessor}; never {@code null}
     */
    static InputObjectProcessor createDefault(
            Function<Class<? extends Canonicalizer>, Canonicalizer> canonicalizerResolver,
            Function<Class<? extends Sanitizer>, Sanitizer> sanitizerResolver) {
        return new DefaultInputObjectProcessor(
                new InputPolicyMetadataResolver(), canonicalizerResolver, sanitizerResolver);
    }

    /**
     * Returns whether {@code targetType}'s type graph declares any canonicalization or sanitization
     * policy, without requiring an engine instance.
     *
     * <p>Exists for the composition decision a transport makes at startup: the engine binding is
     * optional, so a transport that mounts a route whose body type declares {@code @Canonicalize} or
     * {@code @Sanitize} while no {@link InputObjectProcessor} is bound would serve requests with none
     * of the declared processing running. Answering that question needs the same annotation
     * traversal the engine performs, which is internal to this module — hence a static here rather
     * than a reimplementation in every transport.
     *
     * <p>The walk is breadth-first over declared types with a visited set, so it terminates on any
     * type graph including a self-referential or mutually recursive one. It reports only <em>declared
     * chains</em>: a {@code @SkipCanonicalization}/{@code @SkipSanitization} declares nothing to run
     * and is not a policy. A {@code targetType} that reduces to no class declares nothing detectable
     * and yields {@code false}.
     *
     * <p>This is a startup-time query. It builds its own metadata cache and discards it, so it never
     * retains a {@link Class} beyond the call.
     *
     * @param targetType the body or parameter type to inspect; must not be {@code null}
     * @return {@code true} if the type or any type reachable from it declares a canonicalizer or
     *         sanitizer chain, at type level or on a field
     */
    static boolean declaresPolicies(Type targetType) {
        return InputPolicyMetadataResolver.declaresPolicies(targetType);
    }

    /**
     * Processes a structured input intermediate (typically a {@code Map<String, Object>}
     * or {@code List<Object>}) by applying canonicalization and sanitization to string values
     * according to the target type's annotation metadata and the effective invocation-level
     * policies.
     *
     * <p>The intermediate is keyed by <strong>wire</strong> names while both execution paths key
     * their per-field metadata on <strong>Java</strong> property names, so {@code nameResolver}
     * projects one onto the other before every metadata lookup. The processed result keeps the wire
     * keys unchanged — the projection selects which declared policies apply, it never renames what
     * the codec will bind. Pass {@link InputFieldNameResolver#IDENTITY} when the intermediate's keys
     * are already Java property names, which includes every call that processes a bare
     * {@code String}: there is no object whose fields could be renamed.
     *
     * <p>{@code targetType} may be any type that reduces to a class: a class, a parameterized type,
     * a bounded wildcard or type variable, an {@code Optional} of any of those, or an array of them.
     * A type that reduces to no class at all — a {@code GenericArrayType} such as
     * {@code List<Inner>[]}, or a foreign {@link Type} implementation — cannot be processed: the
     * call fails when {@code policies} is non-empty, because the caller declared processing that
     * provably cannot run, and returns {@code input} unchanged when {@code policies} is empty.
     *
     * @param input        the intermediate input — a {@code Map<String, Object>} for objects,
     *                     a {@code List<Object>} for arrays, or a raw value; may be {@code null}
     * @param targetType   the target Java type to look up annotation metadata from
     * @param policies     the effective input policies for this invocation
     *                     (invocation-level canonicalizer and sanitizer chains)
     * @param location     where the input originated (e.g. {@link InputLocation#BODY},
     *                     {@link InputLocation#FORM})
     * @param nameResolver the wire → Java property-name projection to match declared policies with;
     *                     must not be {@code null}
     * @return the processed intermediate input — a new map/list with string values transformed,
     *         or {@code null} if {@code input} was {@code null}
     * @throws IllegalStateException if {@code policies} is non-empty and {@code targetType} reduces
     *                               to no class, so the declared processing cannot be applied
     */
    Object processInput(
            Object input,
            Type targetType,
            EffectiveInputPolicies policies,
            InputLocation location,
            InputFieldNameResolver nameResolver);
}
