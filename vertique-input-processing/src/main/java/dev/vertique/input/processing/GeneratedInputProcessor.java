// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Per-type generated walker over a JSON intermediate ({@code Map<String, Object>} or
 * {@code List<Object>}) for canonicalization and sanitization. Replaces the reflective
 * {@link DefaultInputObjectProcessor} traversal for hot DTOs at the cost of one generated class
 * per participating type.
 *
 * <p>Generated implementations are emitted by the {@code vertique-codegen-sanitization}
 * annotation processor and live in the same package as the source DTO with the suffix
 * {@code _InputProcessor}. They are looked up at runtime by
 * {@link GeneratedInputProcessorDispatcher} via {@link Class#forName} on the consuming
 * type's classloader.
 *
 * <p><strong>Calling contract.</strong> Implementations are stateless and reentrant; one shared
 * instance is reused across all requests. {@link #process} must operate on the intermediate
 * structure without mutating it and must produce output byte-equal with the reflective walker
 * for the same input.
 *
 * @param <T> the target DTO class this processor handles
 */
public interface GeneratedInputProcessor<T> {

    /**
     * Returns the target DTO class this processor handles.
     *
     * @return the target class; never {@code null}
     */
    Class<T> targetType();

    /**
     * Walks the intermediate structure, applying canonicalizer and sanitizer chains to string
     * values according to compile-time-resolved per-field metadata, and recursively dispatches
     * nested DTOs through the dispatcher.
     *
     * @param intermediate the intermediate input — typically a {@code Map<String, Object>} for
     *                     this processor's target type, or any other shape (in which case the
     *                     processor returns it unchanged, matching reflective semantics)
     * @param policies     invocation-level effective policies; threaded through nested calls
     * @param location     where the input originated (e.g. {@link InputLocation#BODY})
     * @param resolver     applies canonicalizer and sanitizer chains to string values
     * @param dispatcher   looks up generated processors for nested DTO types and falls back
     *                     reflectively when none exist for an external-jar nested type
     * @param parent       the accumulated traversal context from the caller, which also carries the
     *                     traversal's {@link InputFieldNameResolver} — consult it through
     *                     {@link InputTraversalContext#logicalFieldName(Class, String)} before
     *                     matching a wire key against a Java field name. It is {@code null} only
     *                     when a caller has no context at all, in which case the processor seeds
     *                     with
     *                     {@link InputTraversalContext#fromPolicies(EffectiveInputPolicies, InputFieldNameResolver)}
     *                     and {@link InputFieldNameResolver#IDENTITY}; the engine's own entry points
     *                     always pass the real context, because re-seeding identity naming would
     *                     silently drop a renamed field's declared policies
     * @param parentPath   the dot-separated path prefix of the field this DTO is nested under,
     *                     or an empty string when invoked at the top level; used to compose
     *                     correct {@code path} values in {@link dev.vertique.core.sanitization.InputValueContext}
     *                     for every field in this DTO
     * @return the processed intermediate — a new map/list with string values transformed
     *         (copy-on-write: implementations MUST allocate a fresh
     *         {@link java.util.LinkedHashMap} or {@link java.util.ArrayList} rather than
     *         mutate the input). The input may be returned unchanged only when its runtime
     *         shape does not match this processor's target type (e.g. a non-{@code Map} value
     *         passed where the target is an object DTO) — in that case there is nothing to
     *         transform and the original reference is safe to return.
     */
    Object process(
            Object intermediate,
            EffectiveInputPolicies policies,
            InputLocation location,
            ChainResolver resolver,
            GeneratedInputProcessorDispatcher dispatcher,
            @Nullable InputTraversalContext parent,
            String parentPath);

    /**
     * Returns every class this processor may pass to
     * {@link InputTraversalContext#logicalFieldName(Class, String)} or dispatch into — its own
     * {@link #targetType()} and each nested DTO and collection element type it dispatches. Flat, not
     * transitive: the engine closes the graph and bounds the recursion.
     *
     * <p>A <strong>schema-free</strong> field contributes nothing, annotated or not. Its arm hands the
     * erased declared type to the reflective continuation as {@code InputValueContext} provenance only —
     * that type declares no fields a projected key could match, so it is never projected against and
     * needs no prepared projection.
     *
     * <p><strong>An override MUST include its own {@link #targetType()}.</strong> An empty return is
     * read as "does not declare an owner set" and makes the engine fall back to its reflective walk for
     * this type; the fallback is logged at debug so a stale generated class is diagnosable rather than
     * silent. The empty default therefore keeps a hand-written or previously-generated processor
     * working.
     *
     * <p><strong>Duplicates are expected and must be eliminated by the implementation.</strong> A
     * self-referential DTO, or two fields targeting the same nested DTO, naturally repeats a class.
     *
     * @return the owner types; never {@code null}, possibly empty, free of duplicates
     */
    default Set<Class<?>> fieldNameOwnerTypes() {
        return Set.of();
    }
}
