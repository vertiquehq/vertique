// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Carries accumulated processing state through nested object traversal during structured-input
 * processing. Holds inherited ancestor canonicalizer/sanitizer chains and sticky skip flags.
 *
 * <p>Skip flags are sticky: once set by an <em>ancestor</em>, they suppress all descendants even if
 * a descendant type or field declares its own canonicalizer/sanitizer chain. An <em>object-level</em>
 * skip is narrower — it yields to the enclosing field's own declared chain, on every field kind.
 * Inherited chains accumulate as recursion descends: each level appends its declared chains
 * verbatim, with each <em>declaration site</em> contributing at most once per descent path.
 * That mirrors the run-time semantics of {@link DefaultInputObjectProcessor} and bounds the chain by
 * the declared type graph rather than by the input's depth (see {@link #compose}).
 *
 * <p>This is a behavior-bearing public API used by both the reflective walker (via
 * {@link #descend(InputPolicyMetadata, FieldPolicyMetadata)}) and codegen-emitted
 * {@link GeneratedInputProcessor} implementations (via the primitive
 * {@link #descend(Class, String, List, List, boolean, boolean, List, List, boolean, boolean) descend}
 * overload that takes the declaring type and field name plus raw chain lists and skip flags). Every
 * overload shares one implementation: the metadata-shape overload is a thin adapter that reads its
 * provenance keys from {@link InputPolicyMetadata#ownerType()} and
 * {@link FieldPolicyMetadata#fieldName()}, and the site-less primitive overload is the same call
 * with {@code null} keys.
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

    /**
     * The declaration sites whose chains this descent path has already folded into the inherited
     * chains. Small (bounded by the declared type graph) and per-traversal, so the {@link Class}
     * references it holds die with the request rather than being retained in a long-lived cache.
     */
    private final Set<DeclarationSite> contributedSites;

    private InputTraversalContext(
            List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain,
            List<Class<? extends Sanitizer>> inheritedSanitizerChain,
            boolean inheritedSkipCanonicalization,
            boolean inheritedSkipSanitization,
            InputFieldNameResolver nameResolver,
            Set<DeclarationSite> contributedSites) {
        this.inheritedCanonicalizerChain = inheritedCanonicalizerChain;
        this.inheritedSanitizerChain = inheritedSanitizerChain;
        this.inheritedSkipCanonicalization = inheritedSkipCanonicalization;
        this.inheritedSkipSanitization = inheritedSkipSanitization;
        this.nameResolver = nameResolver;
        this.contributedSites = contributedSites;
    }

    /**
     * One place in the application's source where a {@code @Canonicalize} / {@code @Sanitize} chain
     * is declared: {@link ObjectSite} for a type's own chain, {@link FieldSite} for a property's.
     *
     * <p>The two kinds are <strong>distinct record types</strong>, not one record with a nullable
     * name, so an object-level site and a field-level site can never be equal however they are
     * built. That is what keeps a caller that cannot name its field — {@link GeneratedSupport}'s
     * collection-of-strings arm, whose frozen signature carries no logical name — from producing a
     * key that collides with its owner type's object-level site. Such a caller produces
     * <em>no</em> site at all (see {@link InputTraversalContext#fieldSite}): an unnamed field
     * declaration is neither suppressed nor recorded, exactly as
     * {@link InputTraversalContext#contributed} documents.
     */
    private sealed interface DeclarationSite {

        /**
         * A type's own object-level chain.
         *
         * @param ownerType the type carrying the {@code @Canonicalize} / {@code @Sanitize}
         */
        record ObjectSite(Class<?> ownerType) implements DeclarationSite {}

        /**
         * One property's field-level chain. Two sites are the same site only when both components
         * match, so the same property name on two different DTOs is two sites.
         *
         * @param ownerType the type declaring the property
         * @param fieldName the declaring property's Java name; never {@code null} — an unnamed
         *                  field declaration has no site
         */
        record FieldSite(Class<?> ownerType, String fieldName) implements DeclarationSite {
            // Implicitly public: a record nested in an interface cannot narrow its canonical
            // constructor. The enclosing DeclarationSite is private, so neither record escapes.
            public FieldSite {
                Objects.requireNonNull(fieldName, "fieldName");
            }
        }
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
        return new InputTraversalContext(
                policies.canonicalizers(), policies.sanitizers(), false, false, nameResolver, Set.of());
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
     * <p>Both provenance keys come out of the carriers: {@link InputPolicyMetadata#ownerType()}
     * names the object-level chains' declaration site, and it paired with
     * {@link FieldPolicyMetadata#fieldName()} names the field-level one, so no separate site
     * argument is needed here.
     *
     * @param parentMeta annotation metadata for the current parent type; must not be {@code null}
     * @param fieldMeta  annotation metadata for the field that holds the nested object,
     *                   or {@code null} when descending from a list element
     * @return a new {@code InputTraversalContext} suitable for processing the child object
     */
    InputTraversalContext descend(InputPolicyMetadata parentMeta, @Nullable FieldPolicyMetadata fieldMeta) {
        return descend(
                parentMeta.ownerType(),
                fieldMeta != null ? fieldMeta.fieldName() : null,
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
     * Returns a child context using raw chain lists and skip flags, without naming the declaration
     * sites they come from. Semantics are the
     * {@link #descend(Class, String, List, List, boolean, boolean, List, List, boolean, boolean)
     * site-keyed} overload's with a {@code null} owner type and field name.
     *
     * <p><strong>Prefer the site-keyed overload — it is what codegen emits.</strong> With no site to
     * key on, this overload records no provenance for the chains it folds in, so a self-referential
     * DTO re-offering its own type-level or field-level chain contributes that chain once per level
     * of the intermediate rather than once per descent path. It is retained so that processors
     * emitted before the site-keyed overload existed keep linking.
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
        return descend(
                null,
                null,
                objectCanon,
                objectSanit,
                objectSkipCanon,
                objectSkipSanit,
                fieldCanon,
                fieldSanit,
                fieldSkipCanon,
                fieldSkipSanit);
    }

    /**
     * Returns a child context using raw chain lists and skip flags, keyed by the declaration sites
     * the chains come from. This is the overload codegen-emitted processors call: they hold
     * object-level and field-level chains as static class constants and never construct
     * {@link InputPolicyMetadata}/{@link FieldPolicyMetadata} carrier records.
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
     * <p>{@code ownerType} and {@code fieldName} are the provenance keys described on
     * {@link #compose}. {@code ownerType} names the declaration site of {@code objectCanon} /
     * {@code objectSanit} — the DTO class whose {@code @Canonicalize} / {@code @Sanitize} produced
     * them, <em>not</em> the nested type being descended into — and {@code ownerType} plus
     * {@code fieldName} names the site of {@code fieldCanon} / {@code fieldSanit}: the property on
     * that same DTO whose value is being descended into. A recursive DTO re-offering either chain is
     * recognized by them, so each site contributes once per descent path.
     *
     * @param ownerType         the type declaring {@code objectCanon} / {@code objectSanit}, or
     *                          {@code null} when the caller cannot name it (see the unkeyed overload)
     * @param fieldName         the Java property name declaring {@code fieldCanon} /
     *                          {@code fieldSanit}, or {@code null} when there is no enclosing field
     *                          or the caller cannot name it
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
            @Nullable Class<?> ownerType,
            @Nullable String fieldName,
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
        // field-level skips are not overridable. This reads the DECLARED chains, before any
        // provenance suppression: a field that declares a chain keeps overriding its owner type's
        // skip at every level, even at the levels where that chain is already in effect.
        boolean fieldDeclaresCanon = fieldCanon != null && !fieldCanon.isEmpty();
        boolean fieldDeclaresSanit = fieldSanit != null && !fieldSanit.isEmpty();

        boolean skipCanon = inheritedSkipCanonicalization || fieldSkipCanon || (objectSkipCanon && !fieldDeclaresCanon);
        boolean skipSanit = inheritedSkipSanitization || fieldSkipSanit || (objectSkipSanit && !fieldDeclaresSanit);

        List<Class<? extends Canonicalizer>> canon = skipCanon
                ? List.of()
                : compose(inheritedCanonicalizerChain, ownerType, fieldName, objectCanon, fieldCanon);
        List<Class<? extends Sanitizer>> sanit =
                skipSanit ? List.of() : compose(inheritedSanitizerChain, ownerType, fieldName, objectSanit, fieldSanit);

        // Record a site only when its chain actually reached one of the child's inherited chains. A
        // skipped kind contributed nothing — and its skip is sticky, so it can contribute nothing
        // further down this path either. Re-recording an already-recorded site is a no-op, so the
        // suppressed re-offer needs no special case.
        boolean objectContributed = (!skipCanon && !objectCanon.isEmpty()) || (!skipSanit && !objectSanit.isEmpty());
        boolean fieldContributed = (!skipCanon && fieldDeclaresCanon) || (!skipSanit && fieldDeclaresSanit);

        Set<DeclarationSite> sites = contributedSites;
        if (objectContributed) {
            sites = withSite(sites, objectSite(ownerType));
        }
        if (fieldContributed) {
            sites = withSite(sites, fieldSite(ownerType, fieldName));
        }

        return new InputTraversalContext(canon, sanit, skipCanon, skipSanit, nameResolver, sites);
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
     * Returns the effective chain composed of {@code inherited + object + field}, with both declared
     * chains appended <strong>verbatim — declared order, repeats included</strong>. Nothing is
     * filtered by processor class. The only thing ever dropped is a <em>whole</em> declared chain,
     * and only when the site that declares it already contributed it on this descent path — the
     * {@code object} chain when {@code ownerType} has, the {@code field} chain when
     * {@code (ownerType, fieldName)} has. The {@code inherited} reference is reused unchanged when
     * nothing is appended, which avoids per-{@code descend} allocations on the common case where the
     * nested DTO declares no chain.
     *
     * <p>The two kinds of site are keyed by <em>different</em> {@link DeclarationSite} record types,
     * so a caller with no {@code fieldName} to offer — the collection-of-strings arm, or a
     * list-element descent — never has its {@code field} chain suppressed by the owner type's
     * object-level site having already contributed. It keys on nothing, and a chain keyed on nothing
     * is appended.
     *
     * <p><strong>Why declared chains are never filtered by class.</strong> A repeat <em>within</em>
     * one {@code @Canonicalize}/{@code @Sanitize} declaration — or between an inherited level's
     * chain and a field's — is a deliberately ordered pipeline, not an accident of inheritance.
     * Idempotence, which both {@code Canonicalizer} and {@code Sanitizer} publish as a MUST, is not
     * commutativity: with {@code @Sanitize(StripHtml.class)} at the route and
     * {@code @Sanitize({DecodeEntities.class, StripHtml.class})} on a field, dropping the field's
     * trailing {@code StripHtml} because the route already named that class yields
     * {@code [StripHtml, DecodeEntities]} — which strips nothing (the markup is still
     * entity-encoded), then decodes, and hands {@code &lt;script} to the application. Appending
     * verbatim keeps {@code [StripHtml, DecodeEntities, StripHtml]}, which is what the declarations
     * say.
     *
     * <p><strong>Why a declared chain is deduplicated by declaration site.</strong> Policy metadata
     * is resolved per type, so every site on a type is offered afresh at <em>every</em> level of a
     * recursive graph. Left unchecked, a self-referential DTO carrying a chain would apply that
     * chain once per level — depth <em>N</em> means <em>N</em> applications per string leaf — which
     * an attacker controls purely by nesting the request body. That holds for a type-level chain on
     * the recursive type and, identically, for a field-level chain on the recursive link:
     * {@code class Node { @Sanitize(X) Node child; }} is a single declaration site re-offered once
     * per level. Suppressing the offer when the site already contributed on this descent path
     * removes the amplification at its source: the amplifier is one site re-offering itself, never
     * two sites naming the same processor class. {@code Node → Node → Node} contributes each of
     * {@code Node}'s sites once; distinct sites each contribute once; both the number of sites and
     * the length of each declared chain are fixed by the application's code, never by the request.
     * The composed chain is therefore bounded by the declared type graph and independent of the
     * intermediate's depth, while every declared chain keeps its contents and order intact at every
     * level — a site contributes all of its entries, in declared order, or none of them.
     *
     * <p>Package-private because it is the single chain-composition implementation shared by
     * {@link DefaultInputObjectProcessor} and {@link GeneratedSupport}; callers that always have a
     * field chain simply pass a non-{@code null} list. It is an instance method because the
     * provenance it consults — the declaration sites this path has already folded in — is traversal
     * state.
     *
     * @param inherited accumulated chain from ancestors; never {@code null}
     * @param ownerType the type declaring {@code object}, and the owning half of {@code field}'s
     *                  site, or {@code null} when the caller cannot name it, in which case both
     *                  chains are appended unconditionally
     * @param fieldName the property name declaring {@code field}, or {@code null} when there is no
     *                  enclosing field or the caller cannot name it, in which case {@code field} is
     *                  appended unconditionally
     * @param object    object-level chain from the parent type; never {@code null}
     * @param field     field-level chain from the enclosing field, or {@code null} when descending
     *                  from a list element
     * @param <T>       the chain class element type
     * @return {@code inherited} followed by {@code object} and then {@code field} — each verbatim,
     *         and each omitted when its own site already contributed on this path — or
     *         {@code inherited} unchanged when nothing is appended
     */
    <T> List<T> compose(
            List<T> inherited,
            @Nullable Class<?> ownerType,
            @Nullable String fieldName,
            List<T> object,
            @Nullable List<T> field) {

        List<T> effectiveObject = contributed(objectSite(ownerType)) ? List.of() : object;
        boolean fieldEmpty = field == null || field.isEmpty() || contributed(fieldSite(ownerType, fieldName));
        if (effectiveObject.isEmpty() && fieldEmpty) {
            return inherited;
        }
        List<T> composed = new ArrayList<>(inherited.size() + effectiveObject.size() + (fieldEmpty ? 0 : field.size()));
        composed.addAll(inherited);
        composed.addAll(effectiveObject);
        if (!fieldEmpty) {
            composed.addAll(field);
        }
        return List.copyOf(composed);
    }

    /**
     * Returns the object-level site of {@code ownerType}, or {@code null} when the caller could not
     * name the declaring type.
     *
     * @param ownerType the declaring type, or {@code null} when the caller cannot name it
     * @return the site, or {@code null} when there is nothing to key on
     */
    @Nullable
    private static DeclarationSite objectSite(@Nullable Class<?> ownerType) {
        return ownerType == null ? null : new DeclarationSite.ObjectSite(ownerType);
    }

    /**
     * Returns the field-level site of {@code fieldName} on {@code ownerType}, or {@code null} when
     * either half is missing — the caller could not name the declaring type, there is no enclosing
     * field (a list-element descent), or the caller holds no logical name for the field it is
     * offering. A missing half yields <em>no site</em> rather than a half-keyed one, which is what
     * keeps an unnamed field declaration from being read as its owner type's object-level site.
     *
     * @param ownerType the declaring type, or {@code null} when the caller cannot name it
     * @param fieldName the declaring property, or {@code null} when the caller cannot name it
     * @return the site, or {@code null} when there is nothing to key on
     */
    @Nullable
    private static DeclarationSite fieldSite(@Nullable Class<?> ownerType, @Nullable String fieldName) {
        return ownerType == null || fieldName == null ? null : new DeclarationSite.FieldSite(ownerType, fieldName);
    }

    /**
     * Returns {@code true} when {@code site}'s declared chain is already folded into this context's
     * inherited chains. An unidentifiable site — a {@code null} one, from {@link #objectSite} or
     * {@link #fieldSite} — is never suppressed: the caller could not identify it, so it cannot be
     * recognized as a re-offer either.
     *
     * @param site the site to test, or {@code null} when the caller could not name one
     * @return whether the site has already contributed on this descent path
     */
    private boolean contributed(@Nullable DeclarationSite site) {
        return site != null && contributedSites.contains(site);
    }

    /**
     * Returns {@code sites} with {@code site} added, reusing the input set when the addition would
     * change nothing — an unidentifiable ({@code null}) site, or one already recorded.
     *
     * @param sites the sites recorded so far; must not be {@code null}
     * @param site  the site to record, or {@code null} when the caller could not name one
     * @return an immutable set containing the site, or {@code sites} unchanged
     */
    private static Set<DeclarationSite> withSite(Set<DeclarationSite> sites, @Nullable DeclarationSite site) {
        if (site == null || sites.contains(site)) {
            return sites;
        }
        Set<DeclarationSite> next = new HashSet<>(sites);
        next.add(site);
        return Set.copyOf(next);
    }
}
