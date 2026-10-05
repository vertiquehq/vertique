// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Composes the effective per-property policy chains that {@link DefaultInputObjectProcessor}
 * applies to a string field of a bean type — the same inheritance, object-level, field-level, and
 * skip rules as {@link DefaultInputObjectProcessor}'s {@code processStringValue} path.
 *
 * <p>Transports that extract flat properties before conversion (for example JAX-RS
 * {@code @BeanParam} fields) call {@link #resolve(Class, String, EffectiveInputPolicies)} so they
 * apply those chains once, rather than replaying a second metadata walk over already-processed
 * values.
 */
final class PropertyPolicyComposer {

    private PropertyPolicyComposer() {}

    /**
     * Resolves the effective policies for {@code propertyName} on {@code beanType} under the given
     * invocation baseline.
     *
     * @param beanType     the bean / DTO class that declares the property
     * @param propertyName the Java property name (BeanParam field / JSON property name)
     * @param baseline     invocation-level policies (route plus parameter-level annotations)
     * @return the composed canonicalizer and sanitizer chains; never {@code null}
     */
    static EffectiveInputPolicies resolve(Class<?> beanType, String propertyName, EffectiveInputPolicies baseline) {
        Objects.requireNonNull(beanType, "beanType");
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(baseline, "baseline");

        InputTraversalContext ctx = InputTraversalContext.fromPolicies(baseline, InputFieldNameResolver.IDENTITY);
        InputPolicyMetadata typeMeta = new InputPolicyMetadataResolver().resolve(beanType);
        FieldPolicyMetadata fieldMeta = typeMeta.fields().get(propertyName);
        return new EffectiveInputPolicies(
                buildCanonicalizerChain(ctx, typeMeta, fieldMeta), buildSanitizerChain(ctx, typeMeta, fieldMeta));
    }

    /**
     * Same skip/composition rules as {@link DefaultInputObjectProcessor}'s private
     * {@code buildCanonicalizerChain}.
     */
    static List<Class<? extends Canonicalizer>> buildCanonicalizerChain(
            InputTraversalContext ctx, InputPolicyMetadata typeMeta, @Nullable FieldPolicyMetadata fieldMeta) {

        if (ctx.inheritedSkipCanonicalization()) {
            return List.of();
        }
        if (fieldMeta != null && fieldMeta.skipCanonicalization()) {
            return List.of();
        }
        boolean fieldHasOwnChain =
                fieldMeta != null && !fieldMeta.canonicalizerChain().isEmpty();
        if (typeMeta.skipCanonicalization() && !fieldHasOwnChain) {
            return List.of();
        }
        return ctx.compose(
                ctx.inheritedCanonicalizerChain(),
                typeMeta.ownerType(),
                fieldMeta != null ? fieldMeta.fieldName() : null,
                typeMeta.objectCanonicalizerChain(),
                fieldMeta != null ? fieldMeta.canonicalizerChain() : List.of());
    }

    /**
     * Same skip/composition rules as {@link DefaultInputObjectProcessor}'s private
     * {@code buildSanitizerChain}.
     */
    static List<Class<? extends Sanitizer>> buildSanitizerChain(
            InputTraversalContext ctx, InputPolicyMetadata typeMeta, @Nullable FieldPolicyMetadata fieldMeta) {

        if (ctx.inheritedSkipSanitization()) {
            return List.of();
        }
        if (fieldMeta != null && fieldMeta.skipSanitization()) {
            return List.of();
        }
        boolean fieldHasOwnChain =
                fieldMeta != null && !fieldMeta.sanitizerChain().isEmpty();
        if (typeMeta.skipSanitization() && !fieldHasOwnChain) {
            return List.of();
        }
        return ctx.compose(
                ctx.inheritedSanitizerChain(),
                typeMeta.ownerType(),
                fieldMeta != null ? fieldMeta.fieldName() : null,
                typeMeta.objectSanitizerChain(),
                fieldMeta != null ? fieldMeta.sanitizerChain() : List.of());
    }
}
