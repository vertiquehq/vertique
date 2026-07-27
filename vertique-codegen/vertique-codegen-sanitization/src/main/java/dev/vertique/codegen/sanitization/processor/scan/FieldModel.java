// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.scan;

import java.util.List;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable per-field metadata record produced by {@link AnnotationCollector} for a DTO field or
 * record component, carrying all information needed by {@link
 * dev.vertique.codegen.sanitization.processor.emit.InputProcessorEmitter} to generate the
 * corresponding {@code switch} arm.
 *
 * <p>Classification is performed against the field's <em>unwrapped</em> type:
 * {@link AnnotationCollector} strips {@code java.util.Optional} layers first, so an
 * {@code Optional<String>} field is a {@link FieldKind#STRING} and a
 * {@code List<Optional<String>>} field is a {@link FieldKind#COLLECTION_OF_STRINGS}.
 *
 * @param name             the JSON/field name (record component name or field name)
 * @param kind             the field kind, determining the code generation strategy
 * @param canonChain       class FQNs of canonicalizers declared on this field, in order; never
 *                         {@code null}, may be empty
 * @param sanitChain       class FQNs of sanitizers declared on this field, in order; never
 *                         {@code null}, may be empty
 * @param skipCanon        whether this field declares {@code @SkipCanonicalization}
 * @param skipSanit        whether this field declares {@code @SkipSanitization}
 * @param nestedTypeMirror for {@link FieldKind#NESTED_DTO} and {@link FieldKind#COLLECTION_OF_DTO},
 *                         the type mirror of the nested DTO (element type for collections); {@code
 *                         null} for other kinds
 * @param declaredType     the field's declared type (the raw Java type as written in the source,
 *                         e.g. {@code Object} for {@code @Canonicalize Object misc}). Used by the
 *                         emitter to set {@code InputValueContext.ownerType} on annotated
 *                         {@link FieldKind#OTHER}-kind fields so generated code matches the
 *                         reflective walker, which routes annotated {@code Object} / unknown-type
 *                         fields through {@code dispatchNested(map, fieldMeta.fieldType(), ...)}
 *                         and propagates that type as {@code ownerType}. May be {@code null}
 *                         when the field's declared type is not relevant to the emitter. Where
 *                         populated with a type mirror that could otherwise be parameterized (the
 *                         nested-DTO, collection-of-DTO, and annotated collection-of-scalars
 *                         cases), {@link AnnotationCollector} erases it before construction, so
 *                         both this component and {@code nestedTypeMirror} are already raw types
 *                         safe for direct use as a {@code .class} literal target.
 */
public record FieldModel(
        String name,
        FieldKind kind,
        List<TypeMirror> canonChain,
        List<TypeMirror> sanitChain,
        boolean skipCanon,
        boolean skipSanit,
        TypeMirror nestedTypeMirror,
        TypeMirror declaredType) {

    /**
     * Classifies how the emitter should handle a DTO field.
     */
    public enum FieldKind {

        /** A {@code String}-typed scalar field (also {@code Optional<String>}). */
        STRING,

        /**
         * A field typed as {@code Collection<String>} (list/set of strings), including
         * {@code Collection<Optional<String>>}.
         */
        COLLECTION_OF_STRINGS,

        /**
         * A field typed as a nested DTO (non-scalar, non-collection), including
         * {@code Optional<NestedDto>}.
         */
        NESTED_DTO,

        /** A field typed as {@code Collection<NestedDto>} (list/set of nested DTOs). */
        COLLECTION_OF_DTO,

        /**
         * Any other type: primitives, boxed types, enums, {@code Map}, arrays,
         * {@code OptionalInt}/{@code OptionalLong}/{@code OptionalDouble}, and raw
         * {@code Optional}. The emitter passes these through unchanged (routing annotated ones
         * through {@code GeneratedSupport.applyDefault}).
         */
        OTHER
    }
}
