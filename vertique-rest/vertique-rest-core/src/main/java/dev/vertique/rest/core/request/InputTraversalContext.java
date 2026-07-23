// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.rest.core.request.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries accumulated processing state through nested object traversal during structured-body
 * processing. Holds inherited ancestor canonicalizer/sanitizer chains and sticky skip flags.
 *
 * <p>Skip flags are sticky: once set by any ancestor, they suppress all descendants even if a
 * descendant type or field declares its own canonicalizer/sanitizer chain. Inherited chains
 * accumulate as recursion descends, mirroring the run-time semantics of
 * {@link DefaultInputObjectProcessor}.
 *
 * <p>This is a behavior-bearing public API used by both the reflective walker (via
 * {@link #descend(InputPolicyMetadata, FieldPolicyMetadata)}) and codegen-emitted
 * {@link GeneratedInputProcessor} implementations (via the primitive
 * {@link #descend(List, List, boolean, boolean, List, List, boolean, boolean) descend} overload
 * that takes raw chain lists and skip flags). The two overloads share a common implementation;
 * the metadata-shape overload is a thin adapter over the primitive one.
 *
 * <p>Instances are immutable. {@link #descend} always returns a new context.
 */
public final class InputTraversalContext {

    private final List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain;
    private final List<Class<? extends Sanitizer>> inheritedSanitizerChain;
    private final boolean inheritedSkipCanonicalization;
    private final boolean inheritedSkipSanitization;

    private InputTraversalContext(
            List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain,
            List<Class<? extends Sanitizer>> inheritedSanitizerChain,
            boolean inheritedSkipCanonicalization,
            boolean inheritedSkipSanitization) {
        this.inheritedCanonicalizerChain = inheritedCanonicalizerChain;
        this.inheritedSanitizerChain = inheritedSanitizerChain;
        this.inheritedSkipCanonicalization = inheritedSkipCanonicalization;
        this.inheritedSkipSanitization = inheritedSkipSanitization;
    }

    /**
     * Creates a context seeded from route-level policies as the root of a traversal.
     *
     * @param policies the route-level effective policies; must not be {@code null}
     * @return a context whose inherited chains are the route-level chains and whose skip flags
     *         are both {@code false}
     */
    public static InputTraversalContext fromRoute(EffectiveInputPolicies policies) {
        return new InputTraversalContext(policies.routeCanonicalizers(), policies.routeSanitizers(), false, false);
    }

    /**
     * Returns a child context for descending into a nested object, accumulating the parent
     * type's object-level chains and the enclosing field's field-level chains and skip flags.
     *
     * <p>Used by the reflective walker. Skip flags are sticky: if any inherited or parent or
     * field-level skip flag is set, the corresponding chain on the child context is empty.
     *
     * @param parentMeta annotation metadata for the current parent type; must not be {@code null}
     * @param fieldMeta  annotation metadata for the field that holds the nested object,
     *                   or {@code null} when descending from a list element
     * @return a new {@code InputTraversalContext} suitable for processing the child object
     */
    public InputTraversalContext descend(InputPolicyMetadata parentMeta, @Nullable FieldPolicyMetadata fieldMeta) {
        return descend(
                parentMeta.objectCanonicalizerChain(),
                parentMeta.objectSanitizerChain(),
                parentMeta.skipCanonicalization(),
                parentMeta.skipSanitization(),
                fieldMeta != null ? fieldMeta.canonicalizerChain() : null,
                fieldMeta != null ? fieldMeta.sanitizerChain() : null,
                fieldMeta != null && fieldMeta.skipCanonicalization(),
                fieldMeta != null && fieldMeta.skipSanitization());
    }

    /**
     * Returns a child context using raw chain lists and skip flags. This overload is intended for
     * codegen-emitted callers that hold object-level and field-level chains as static class
     * constants and never construct {@link InputPolicyMetadata}/{@link FieldPolicyMetadata}
     * carrier records.
     *
     * <p>Skip-flag precedence is identical to the metadata overload: any inherited or parent or
     * field-level skip flag short-circuits the corresponding chain to empty.
     *
     * @param objectCanon       object-level canonicalizer chain on the parent type; must not be {@code null}
     * @param objectSanit       object-level sanitizer chain on the parent type; must not be {@code null}
     * @param objectSkipCanon   whether the parent type declares {@code @SkipCanonicalization}
     * @param objectSkipSanit   whether the parent type declares {@code @SkipSanitization}
     * @param fieldCanon        field-level canonicalizer chain, or {@code null} when descending
     *                          from a list element with no enclosing field
     * @param fieldSanit        field-level sanitizer chain, or {@code null} (same)
     * @param fieldSkipCanon    whether the field declares {@code @SkipCanonicalization}
     * @param fieldSkipSanit    whether the field declares {@code @SkipSanitization}
     * @return a new {@code InputTraversalContext} suitable for processing the child object
     */
    public InputTraversalContext descend(
            List<Class<? extends Canonicalizer>> objectCanon,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipCanon,
            boolean objectSkipSanit,
            @Nullable List<Class<? extends Canonicalizer>> fieldCanon,
            @Nullable List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipCanon,
            boolean fieldSkipSanit) {

        boolean skipCanon = inheritedSkipCanonicalization || objectSkipCanon || fieldSkipCanon;
        boolean skipSanit = inheritedSkipSanitization || objectSkipSanit || fieldSkipSanit;

        List<Class<? extends Canonicalizer>> canon =
                skipCanon ? List.of() : compose(inheritedCanonicalizerChain, objectCanon, fieldCanon);
        List<Class<? extends Sanitizer>> sanit =
                skipSanit ? List.of() : compose(inheritedSanitizerChain, objectSanit, fieldSanit);

        return new InputTraversalContext(canon, sanit, skipCanon, skipSanit);
    }

    /**
     * Returns the accumulated canonicalizer chain inherited from all ancestors.
     *
     * @return the inherited canonicalizer chain; never {@code null}, possibly empty
     */
    public List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain() {
        return inheritedCanonicalizerChain;
    }

    /**
     * Returns the accumulated sanitizer chain inherited from all ancestors.
     *
     * @return the inherited sanitizer chain; never {@code null}, possibly empty
     */
    public List<Class<? extends Sanitizer>> inheritedSanitizerChain() {
        return inheritedSanitizerChain;
    }

    /**
     * Returns {@code true} if any ancestor declared {@code @SkipCanonicalization}.
     *
     * @return the sticky inherited skip-canonicalization flag
     */
    public boolean inheritedSkipCanonicalization() {
        return inheritedSkipCanonicalization;
    }

    /**
     * Returns {@code true} if any ancestor declared {@code @SkipSanitization}.
     *
     * @return the sticky inherited skip-sanitization flag
     */
    public boolean inheritedSkipSanitization() {
        return inheritedSkipSanitization;
    }

    /**
     * Returns the effective chain composed of {@code inherited + object + field}, but reuses the
     * {@code inherited} reference unchanged when {@code object} and {@code field} contribute no
     * new entries. This avoids per-{@code descend} allocations on the common case where the
     * nested DTO has no object- or field-level chain — every nested call inherits the parent's
     * chain unmutated.
     *
     * @param inherited accumulated chain from ancestors; never {@code null}
     * @param object    object-level chain from the parent type; never {@code null}
     * @param field     field-level chain from the enclosing field, or {@code null} when descending
     *                  from a list element
     * @param <T>       the chain class element type
     * @return a composed list, or {@code inherited} unchanged when no new entries are added
     */
    private static <T> List<T> compose(List<T> inherited, List<T> object, @Nullable List<T> field) {
        boolean fieldEmpty = field == null || field.isEmpty();
        if (object.isEmpty() && fieldEmpty) {
            return inherited;
        }
        List<T> result = new ArrayList<>(inherited.size() + object.size() + (field == null ? 0 : field.size()));
        result.addAll(inherited);
        result.addAll(object);
        if (field != null) {
            result.addAll(field);
        }
        return result;
    }
}
