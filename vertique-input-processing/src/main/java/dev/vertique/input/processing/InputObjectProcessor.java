// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
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
 *         intermediate, MyDto.class, policies, InputLocation.BODY);
 * MyDto dto = objectMapper.convertValue(processed, MyDto.class);
 * }</pre>
 *
 * @see EffectiveInputPolicies
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
     * Processes a structured input intermediate (typically a {@code Map<String, Object>}
     * or {@code List<Object>}) by applying canonicalization and sanitization to string values
     * according to the target type's annotation metadata and the effective invocation-level
     * policies.
     *
     * @param input      the intermediate input — a {@code Map<String, Object>} for objects,
     *                   a {@code List<Object>} for arrays, or a raw value; may be {@code null}
     * @param targetType the target Java type to look up annotation metadata from
     * @param policies   the effective input policies for this invocation
     *                   (invocation-level canonicalizer and sanitizer chains)
     * @param location   where the input originated (e.g. {@link InputLocation#BODY},
     *                   {@link InputLocation#FORM})
     * @return the processed intermediate input — a new map/list with string values transformed,
     *         or {@code null} if {@code input} was {@code null}
     */
    Object processInput(Object input, Type targetType, EffectiveInputPolicies policies, InputLocation location);
}
