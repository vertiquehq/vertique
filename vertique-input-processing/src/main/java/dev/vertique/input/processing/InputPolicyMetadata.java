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
     * Returns {@code true} if no object-level or field-level processors are configured and
     * no skip flags are set.
     *
     * @return {@code true} when this metadata has no active processing declarations
     */
    public boolean isEmpty() {
        return objectCanonicalizerChain.isEmpty()
                && objectSanitizerChain.isEmpty()
                && !skipCanonicalization
                && !skipSanitization
                && fields.isEmpty();
    }

    /**
     * Per-field annotation metadata for input processing.
     *
     * @param canonicalizerChain    canonicalizer chain declared on this field
     * @param sanitizerChain        sanitizer chain declared on this field
     * @param skipCanonicalization  whether this field opts out of canonicalization
     * @param skipSanitization      whether this field opts out of sanitization
     * @param fieldType             the Java type of this field (for recursive traversal)
     * @param nestedMetadata        metadata for nested object types; {@code null} for non-object fields
     * @param isStringType          whether the field is a {@link String}
     * @param isCollectionOfStrings whether the field is a {@code Collection<String>}
     * @param collectionElementType for collections of non-string objects, the element type;
     *                              {@code null} for string collections or non-collection fields
     */
    record FieldPolicyMetadata(
            List<Class<? extends Canonicalizer>> canonicalizerChain,
            List<Class<? extends Sanitizer>> sanitizerChain,
            boolean skipCanonicalization,
            boolean skipSanitization,
            Class<?> fieldType,
            @Nullable InputPolicyMetadata nestedMetadata,
            boolean isStringType,
            boolean isCollectionOfStrings,
            @Nullable Class<?> collectionElementType) {}
}
