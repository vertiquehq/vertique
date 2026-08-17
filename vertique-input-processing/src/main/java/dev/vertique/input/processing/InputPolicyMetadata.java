// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Cached per-type annotation metadata for input processing.
 *
 * <p>Pre-computed from {@code @Canonicalize}, {@code @Sanitize}, and skip annotations on the
 * target type, its fields and record components. Used by {@link DefaultInputObjectProcessor}
 * to avoid per-request reflection.
 *
 * <p><strong>This record describes exactly one type.</strong> It never embeds the expanded
 * metadata of nested types: a field that holds a nested object records only that field's declared
 * {@linkplain FieldPolicyMetadata#fieldType() Java type}, and the walker resolves that type's own
 * metadata from {@link InputPolicyMetadataResolver}'s per-type cache when it descends into the
 * value. Traversal therefore terminates on the finite intermediate data rather than on a
 * type-graph budget, so a self-referential type resolves once and applies at every level, and
 * direct and mutual recursion behave identically.
 *
 * @param objectCanonicalizerChain canonicalizer chain declared on the type itself
 * @param objectSanitizerChain     sanitizer chain declared on the type itself
 * @param skipCanonicalization     whether the type is annotated with {@code @SkipCanonicalization}
 * @param skipSanitization         whether the type is annotated with {@code @SkipSanitization}
 * @param fields                   per-field metadata keyed by field name (matches JSON property name)
 */
record InputPolicyMetadata(
        List<Class<? extends Canonicalizer>> objectCanonicalizerChain,
        List<Class<? extends Sanitizer>> objectSanitizerChain,
        boolean skipCanonicalization,
        boolean skipSanitization,
        Map<String, FieldPolicyMetadata> fields) {

    /** Metadata with no annotations — skip all processing. */
    public static final InputPolicyMetadata EMPTY =
            new InputPolicyMetadata(List.of(), List.of(), false, false, Map.of());

    /**
     * Per-field annotation metadata for input processing.
     *
     * <p>The shape flags and {@code collectionElementType} classify the field so the walker knows
     * how to treat the intermediate value it finds there; {@code fieldType} is the declared type
     * whose own metadata the walker resolves at descent. No nested metadata is embedded here.
     *
     * @param canonicalizerChain    canonicalizer chain declared on this field
     * @param sanitizerChain        sanitizer chain declared on this field
     * @param skipCanonicalization  whether this field opts out of canonicalization
     * @param skipSanitization      whether this field opts out of sanitization
     * @param fieldType             the declared Java type of this field; the type whose metadata is
     *                              resolved when the walker descends into this field's value
     * @param isStringType          whether the field is a {@link String}
     * @param isCollectionOfStrings whether the field is a {@code Collection<String>}
     * @param collectionElementType the declared element type for a collection of nested objects;
     *                              {@code null} for string collections, scalar-element collections
     *                              and non-collection fields
     */
    record FieldPolicyMetadata(
            List<Class<? extends Canonicalizer>> canonicalizerChain,
            List<Class<? extends Sanitizer>> sanitizerChain,
            boolean skipCanonicalization,
            boolean skipSanitization,
            Class<?> fieldType,
            boolean isStringType,
            boolean isCollectionOfStrings,
            @Nullable Class<?> collectionElementType) {}
}
