// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Carries accumulated processing state through nested object traversal during structured-input
 * processing. Holds inherited ancestor canonicalizer/sanitizer chains and sticky skip flags.
 *
 * <p>Skip flags are sticky: once set by an <em>ancestor</em>, they suppress all descendants even if
 * a descendant type or field declares its own canonicalizer/sanitizer chain. An <em>object-level</em>
 * skip is narrower — it yields to the enclosing field's own declared chain, on every field kind.
 * Inherited chains accumulate as recursion descends, holding each distinct processor class once, and
 * mirror the run-time semantics of {@link DefaultInputObjectProcessor}.
 *
 * <p>This is a behavior-bearing public API used by both the reflective walker (via
 * {@link #descend(InputPolicyMetadata, FieldPolicyMetadata)}) and codegen-emitted
 * {@link GeneratedInputProcessor} implementations (via the primitive
 * {@link #descend(List, List, boolean, boolean, List, List, boolean, boolean) descend} overload
 * that takes raw chain lists and skip flags). The two overloads share a common implementation;
 * the metadata-shape overload is a thin adapter over the primitive one.
 *
 * <p>The context also carries the traversal's {@link InputFieldNameResolver}. That is what lets a
 * generated processor consult the wire → Java projection through
 * {@link #logicalFieldName(Class, String)} without any change to the
 * {@link GeneratedInputProcessor#process} signature, and it is why the resolver survives every
 * {@link #descend} and every crossing of the codegen↔reflection boundary — a context that lost it
 * would silently fall back to identity naming and stop matching renamed fields.
 *
 * <p>Instances are immutable. {@link #descend} always returns a new context.
 */
public final class InputTraversalContext {

    private final List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain;
    private final List<Class<? extends Sanitizer>> inheritedSanitizerChain;
    private final boolean inheritedSkipCanonicalization;
    private final boolean inheritedSkipSanitization;
    private final InputFieldNameResolver nameResolver;

    private InputTraversalContext(
            List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain,
            List<Class<? extends Sanitizer>> inheritedSanitizerChain,
            boolean inheritedSkipCanonicalization,
            boolean inheritedSkipSanitization,
            InputFieldNameResolver nameResolver) {
        this.inheritedCanonicalizerChain = inheritedCanonicalizerChain;
        this.inheritedSanitizerChain = inheritedSanitizerChain;
        this.inheritedSkipCanonicalization = inheritedSkipCanonicalization;
        this.inheritedSkipSanitization = inheritedSkipSanitization;
        this.nameResolver = nameResolver;
    }

    /**
     * Creates a context seeded from the invocation-level policies and the traversal's wire-name
     * projection, as the root of a traversal.
     *
     * <p>There is deliberately no one-argument form: a factory that defaulted the resolver would let
     * any caller re-seed {@link InputFieldNameResolver#IDENTITY} by omission and silently reinstate
     * identity naming below that point. Pass {@link InputFieldNameResolver#IDENTITY} explicitly when
     * the intermediate's keys are already Java property names.
     *
     * @param policies     the effective invocation-level policies; must not be {@code null}
     * @param nameResolver the wire → Java property-name projection for this traversal;
     *                     must not be {@code null}
     * @return a context whose inherited chains are the invocation-level chains, whose skip flags
     *         are both {@code false}, and which carries {@code nameResolver}
     */
    public static InputTraversalContext fromPolicies(
            EffectiveInputPolicies policies, InputFieldNameResolver nameResolver) {
        return new InputTraversalContext(policies.canonicalizers(), policies.sanitizers(), false, false, nameResolver);
    }

    /**
     * Projects a wire property name onto the Java property name whose declared policies apply to it,
     * using the {@link InputFieldNameResolver} this traversal was seeded with.
     *
     * <p>Called by the reflective walker before every per-field metadata lookup and by generated
     * processors before their field-name {@code switch}. The returned name selects <em>metadata</em>
     * only — the emitted intermediate keeps the wire key, which is what the codec will bind.
     *
     * <p>Resolution is skipped entirely when the traversal carries
     * {@link InputFieldNameResolver#IDENTITY}: the projection is known to be the identity map, so a
     * per-field call could only return the wire name it was given.
     *
     * @param ownerType the type declaring the property set this fragment is keyed against;
     *                  must not be {@code null}
     * @param wireName  the key as it appeared in the intermediate; must not be {@code null}
     * @return the Java property name, or {@code wireName} when the projection does not recognize it
     * @throws IllegalStateException if the resolver breaks its totality contract by returning
     *                               {@code null}, which would otherwise silently drop the field's
     *                               declared policies
     */
    public String logicalFieldName(Class<?> ownerType, String wireName) {
        if (nameResolver == InputFieldNameResolver.IDENTITY) {
            return wireName;
        }
        String logicalName = nameResolver.logicalName(ownerType, wireName);
        if (logicalName == null) {
            throw new IllegalStateException(
                    "InputFieldNameResolver " + nameResolver.getClass().getName()
                            + " returned null for wire name '" + wireName + "' on " + ownerType.getName()
                            + ". The projection is total: an unrecognized wire name must be returned unchanged.");
        }
        return logicalName;
    }

    /**
     * Returns a child context for descending into a nested object, accumulating the parent
     * type's object-level chains and the enclosing field's field-level chains and skip flags.
     *
     * <p>Used by the reflective walker inside this module; the metadata carrier records are
     * internal, so this overload is not part of the module's public surface. Skip precedence is the
     * primitive overload's: an inherited or field-level skip empties the corresponding chain on the
     * child context, while a parent object-level skip does so only when {@code fieldMeta} declares
     * no chain of that kind.
     *
     * @param parentMeta annotation metadata for the current parent type; must not be {@code null}
     * @param fieldMeta  annotation metadata for the field that holds the nested object,
     *                   or {@code null} when descending from a list element
     * @return a new {@code InputTraversalContext} suitable for processing the child object
     */
    InputTraversalContext descend(InputPolicyMetadata parentMeta, @Nullable FieldPolicyMetadata fieldMeta) {
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
     * <p>Skip-flag precedence is identical to the metadata overload, and to
     * {@code DefaultInputObjectProcessor}'s per-value chain composition: an inherited skip from any
     * ancestor wins outright, a field-level skip wins next, and an object-level skip applies only
     * when the enclosing field declares no chain of its own. A field that <em>does</em> declare its
     * own chain therefore overrides its owner type's {@code @SkipCanonicalization} /
     * {@code @SkipSanitization} — on a nested-object or collection field exactly as on a direct
     * {@code String} field. A sticky skip empties the chains, never the name projection — a skipped
     * subtree still has to resolve field names to select the right per-field metadata.
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

        // An object-level skip is overridden by the enclosing field's own chain, matching
        // DefaultInputObjectProcessor.buildCanonicalizerChain / buildSanitizerChain. Inherited and
        // field-level skips are not overridable.
        boolean fieldDeclaresCanon = fieldCanon != null && !fieldCanon.isEmpty();
        boolean fieldDeclaresSanit = fieldSanit != null && !fieldSanit.isEmpty();

        boolean skipCanon = inheritedSkipCanonicalization || fieldSkipCanon || (objectSkipCanon && !fieldDeclaresCanon);
        boolean skipSanit = inheritedSkipSanitization || fieldSkipSanit || (objectSkipSanit && !fieldDeclaresSanit);

        List<Class<? extends Canonicalizer>> canon =
                skipCanon ? List.of() : compose(inheritedCanonicalizerChain, objectCanon, fieldCanon);
        List<Class<? extends Sanitizer>> sanit =
                skipSanit ? List.of() : compose(inheritedSanitizerChain, objectSanit, fieldSanit);

        return new InputTraversalContext(canon, sanit, skipCanon, skipSanit, nameResolver);
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
     * Returns the effective chain composed of {@code inherited + object + field}, <strong>with each
     * distinct processor class kept exactly once, in encounter order</strong>. The
     * {@code inherited} reference is reused unchanged when {@code object} and {@code field}
     * contribute no new entries, which avoids per-{@code descend} allocations on the common case
     * where the nested DTO has no object- or field-level chain.
     *
     * <p><strong>Why deduplicate.</strong> Policy metadata is resolved per type, so a type's own
     * chain is offered afresh at <em>every</em> level of a recursive graph. Without deduplication a
     * self-referential DTO carrying a type-level chain would apply that chain once per level —
     * depth <em>N</em> means <em>N</em> applications per string leaf — which an attacker controls
     * purely by nesting the request body. Bounding the chain by distinct processor class removes
     * that amplification outright rather than capping it.
     *
     * <p><strong>What deduplication costs.</strong> {@code Canonicalizer} and {@code Sanitizer} both
     * publish idempotence as a MUST, so {@code S(S(x)) == S(x)}: for a single-element chain,
     * collapsing a repeat is exactly neutral, and it is neutral for every processor family the
     * framework ships. It is <em>not</em> a general proof for an arbitrary interleaved multi-element
     * chain — {@code A B A} collapses to {@code A B}, and those agree only when the processors also
     * commute or {@code B} preserves {@code A}'s fixed point, which the contracts do not require.
     * The bound is chosen deliberately in that knowledge: a repeated class in a composed chain
     * indicates the same policy inherited twice, not a deliberately re-ordered pipeline.
     *
     * <p>Package-private because it is the single chain-composition implementation shared by
     * {@link DefaultInputObjectProcessor} and {@link GeneratedSupport}; callers that always have a
     * field chain simply pass a non-{@code null} list.
     *
     * @param inherited accumulated chain from ancestors; never {@code null}
     * @param object    object-level chain from the parent type; never {@code null}
     * @param field     field-level chain from the enclosing field, or {@code null} when descending
     *                  from a list element
     * @param <T>       the chain class element type
     * @return a composed list holding each distinct entry once in encounter order, or
     *         {@code inherited} unchanged when it is already distinct and nothing new is added
     */
    static <T> List<T> compose(List<T> inherited, List<T> object, @Nullable List<T> field) {
        boolean fieldEmpty = field == null || field.isEmpty();
        if (object.isEmpty() && fieldEmpty) {
            return inherited;
        }
        LinkedHashSet<T> distinct = new LinkedHashSet<>(inherited);
        boolean grew = distinct.addAll(object);
        if (field != null) {
            grew |= distinct.addAll(field);
        }
        // Reuse the caller's list only when nothing was added AND it held no duplicates itself;
        // otherwise its size would not match the distinct set it produced.
        if (!grew && distinct.size() == inherited.size()) {
            return inherited;
        }
        return List.copyOf(distinct);
    }
}
