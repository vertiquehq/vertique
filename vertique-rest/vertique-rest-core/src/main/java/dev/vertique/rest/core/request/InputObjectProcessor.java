// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.InputLocation;
import java.lang.reflect.Type;

/**
 * Reusable structured-body processing engine for canonicalization and sanitization.
 *
 * <p>Operates on intermediate tree/map structures (typically a {@code Map<String, Object>}
 * produced by JSON parsing) before final DTO materialization. Custom request body decoders
 * can invoke this contract to reuse the framework's canonicalization and sanitization engine
 * without taking on Bean Validation responsibility.
 *
 * <p>This contract is transform-only — it does NOT invoke Bean Validation.
 *
 * <p>Example usage from a custom decoder:
 * <pre>{@code
 * Object intermediate = jsonObject.getMap();
 * Object processed = processor.processStructuredBody(
 *         intermediate, MyDto.class, policies, InputLocation.BODY);
 * MyDto dto = objectMapper.convertValue(processed, MyDto.class);
 * }</pre>
 *
 * @see DefaultInputObjectProcessor
 * @see EffectiveInputPolicies
 */
public interface InputObjectProcessor {

    /**
     * Processes a structured body intermediate (typically a {@code Map<String, Object>}
     * or {@code List<Object>}) by applying canonicalization and sanitization to string
     * values according to the target type's annotation metadata and effective route policies.
     *
     * @param intermediateBody the intermediate body — a {@code Map<String, Object>} for objects,
     *                         a {@code List<Object>} for arrays, or a raw value; may be {@code null}
     * @param targetType       the target Java type to look up annotation metadata from
     * @param policies         the effective input policies for this invocation
     *                         (route-level canonicalizer and sanitizer chains)
     * @param location         where the body originated (e.g. {@link InputLocation#BODY},
     *                         {@link InputLocation#FORM})
     * @return the processed intermediate body — a new map/list with string values transformed,
     *         or {@code null} if {@code intermediateBody} was {@code null}
     */
    Object processStructuredBody(
            Object intermediateBody, Type targetType, EffectiveInputPolicies policies, InputLocation location);
}
