// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.github.victools.jsonschema.generator.CustomPropertyDefinition;
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Fails generation when a property carries a non-default {@code @Schema(implementation = ...)} while
 * its <em>declared</em> type graph contains a class the active profile overrides.
 *
 * <p>Why this must fail rather than compose: at the pinned Victools version,
 * {@code Swagger2Module.resolveTargetTypeOverrides(MemberScope)} redirects the member's resolved
 * target type <em>before</em> general-type definition lookup runs. The profile's custom definition
 * for the declared class is therefore never consulted for that member, and the fragment — the wire
 * contract the profile's mapper actually produces — is dropped with no trace in the document. A
 * silently wrong schema is the one outcome this package will not emit, so the combination is
 * rejected. Turning the failure into a real conjunction later is a compatible evolution.
 *
 * <h2>Why a member-scope hook</h2>
 *
 * <p>The check is implemented as a Victools {@link CustomPropertyDefinitionProvider} rather than a
 * reflective pre-pass over the requested root type, for two reasons:
 *
 * <ul>
 *   <li><strong>Reach.</strong> Victools visits the members of every type it generates, so a
 *       member-scope hook covers nested DTOs, collection element types, and inherited members for
 *       free — for every type Victools actually generates. A member whose type is redirected away is
 *       never generated, so its own members are never visited. A root-only reflective walk would
 *       silently miss all of them.
 *   <li><strong>Timing.</strong> This provider is consulted at exactly the moment the member's schema
 *       is about to be produced — the moment the fragment would otherwise be dropped — and it is the
 *       one member-scope hook that could legitimately <em>supply</em> the conjoined definition should
 *       the deferred true-conjunction behavior ever be implemented.
 * </ul>
 *
 * <p>The provider never returns a definition: it either throws or returns {@code null} to leave the
 * member entirely to Victools.
 *
 * <h2>What "declared type graph" means</h2>
 *
 * <p>The graph is read from {@link MemberScope#getDeclaredType()} — a Classmate {@link ResolvedType}
 * — for two reasons. First, it is the member's <em>pre-redirect</em> type: at the pinned Victools
 * version the target-type redirect lives in the separate {@link MemberScope#getOverriddenType()}
 * accessor, so the declared type is exactly as immune to the redirect as raw reflection would be.
 * Second, unlike {@link Field#getGenericType()} / {@link Method#getGenericReturnType()}, it resolves
 * type variables against the declaring context, so a member inherited from a generic supertype —
 * {@code class Base<T> { T amount; }} bound by {@code class Derived extends Base<BigDecimal>} —
 * yields {@code BigDecimal} rather than an unreadable type variable. Raw reflection would report no
 * override for exactly the member Victools then redirects, which is the silent drop this guard
 * exists to prevent.
 *
 * <p>The graph comprises the declared type itself plus, recursively, its array element type and its
 * child types in the sense of {@link #declaredTypeGraphChildren(ResolvedType, Class)}: the type's
 * <em>self-declared</em> resolved parameters <em>and</em> the payload types it merely
 * <em>inherits</em> from a supertype binding. Both are required, because
 * {@link ResolvedType#getTypeParameters()} reports only a type's own declared parameters. A container
 * subclass that binds its element in the {@code extends} clause —
 * {@code class Amounts extends ArrayList<BigDecimal>} — declares none, so a self-parameters-only walk
 * sees an empty graph and reports "no override found" for a type Victools nonetheless publishes as
 * {@code {"type":"array","items":<element schema>}}. The element is a real position the profile
 * fragment applies to, so missing it is the exact silent drop this guard exists to prevent.
 *
 * <p><strong>The walk is deliberately broader than Victools' own container notion, and deliberately
 * position-unaware.</strong> It is <em>not</em> a mirror of {@code TypeContext.getContainerItemType},
 * which answers {@code null} unless {@code isContainerType} holds (an array or a {@link
 * java.util.Collection}, whose item binding is then resolved via {@link Iterable}) —
 * and it must not be narrowed to match it. This guard decides only whether an override <em>could</em>
 * be reachable under a redirect; being fail-closed, it prefers a false rejection, which a developer
 * sees and can resolve by declaring the wire shape once, over a silent drop, which nobody sees. That
 * posture also keeps the walk internally consistent: under the pinned option set an inherited
 * {@link Map} value is not a distinct schema position either (the generator does not enable
 * {@code Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES}), so narrowing one inherited descent while
 * keeping another would trade a coherent over-approximation for an arbitrary one.
 *
 * <p><strong>Closed enumeration of a declared-type-graph child</strong>, at the pinned Victools
 * version — a future reader can check completeness against this list by inspection rather than
 * rediscovering it from a defect:
 *
 * <ol>
 *   <li>the type's self-declared resolved type parameters;
 *   <li>the array element type;
 *   <li>the inherited {@link Iterable} binding, index 0;
 *   <li>the inherited {@link Map} binding, <em>value</em> position only (index 1);
 *   <li>the {@link Optional} payload — always reached via item 1, because {@link Optional} is
 *       {@code final};
 *   <li>the inherited {@link Supplier} binding, index 0.
 * </ol>
 *
 * <p>Items 5 and 6 are exhaustive for the <em>wrapper</em> axis because {@code Option} builds exactly
 * two flattening wrapper modules — {@code FlattenedOptionalModule extends
 * FlattenedWrapperModule<Optional>} and {@code new FlattenedWrapperModule<>(Supplier.class)} — and
 * both match implementors, resolving the payload through the <em>inherited</em> binding. Item 5 needs
 * no dedicated read: {@link Optional} is {@code final}, so no subtype can hide the binding from
 * item 1, and {@code Optional<BigDecimal>} already reports {@code BigDecimal} as a self-declared
 * parameter. {@link Supplier} is an interface, so item 6 is the only way to reach the payload of
 * {@code class DecimalSupplier implements Supplier<BigDecimal>}. This enumeration is pinned to
 * Victools 4.38.0; a version bump must re-verify it against the wrapper modules {@code Option} builds
 * and the option set {@code OptionPreset.PLAIN_JSON} enables.
 *
 * <p>The enumeration is closed over the <em>type</em> graph only. Members are deliberately not
 * resolved: a nested DTO's fields are reached through member resolution, not through
 * {@code ResolvedType}, and are outside the contract this guard enforces. A redirect over a
 * DTO-typed property whose fields carry overridden types is accepted — the profile made no statement
 * about the DTO, so no fragment is contradicted; the developer replaced the whole subtree explicitly.
 *
 * <p><strong>Map key positions are excluded</strong> in both descents: for a {@link Map} with two
 * type parameters the key parameter is skipped, and only index 1 of an inherited
 * {@code typeParametersFor(Map.class)} binding is walked, matching the override contract, under which
 * a map key stays mapper-owned. The walk is bounded by {@link TypeGrammar#MAX_DEPTH}, and exceeding
 * that bound <em>fails</em> rather than resolving to "no override found" — a depth bound inside a
 * fail-closed guard must never fail open.
 *
 * <h2>How the {@code @Schema} is resolved</h2>
 *
 * <p>The resolution mirrors {@code Swagger2Module.getSchemaAnnotationValue(MemberScope)} exactly,
 * because that is the method deciding whether the redirect happens. It reads, in order:
 *
 * <ol>
 *   <li>in a fake container item scope, {@code @ArraySchema.schema()} — and nothing else;
 *   <li>otherwise a direct {@code @Schema}; and
 *   <li>otherwise {@code @ArraySchema.arraySchema()}.
 * </ol>
 *
 * <p>Both annotations are resolved through
 * {@link MemberScope#getAnnotationConsideringFieldAndGetter(Class)}, so an annotation on either a
 * field or its accessor counts, exactly as the Swagger module resolves it. In all three branches the
 * sentinel default {@code Void.class} means "no redirect declared". Covering only the direct
 * {@code @Schema} would leave both {@code @ArraySchema} forms silently dropping the fragment.
 *
 * @param <M> the member scope kind this instance is registered for
 */
final class SchemaImplementationGuard<M extends MemberScope<?, ?>> implements CustomPropertyDefinitionProvider<M> {

    /** Maximum length, in UTF-16 code units, of the member identity rendered in a failure message. */
    private static final int MAX_MEMBER_NAME_LENGTH = 128;

    /** The validated, direction-filtered override declarations the guard tests the graph against. */
    private final ValidatedProfile profile;

    /**
     * Creates a guard over one direction's validated declarations.
     *
     * @param profile the validated, direction-filtered profile view
     */
    SchemaImplementationGuard(ValidatedProfile profile) {
        this.profile = profile;
    }

    /**
     * Inspects a member and fails generation when it combines an implementation redirect with an
     * overridden declared type; never supplies a definition of its own.
     *
     * @param member  the member Victools is about to generate a schema for
     * @param context the active generation context, unused by this guard
     * @return always {@code null}, leaving the member to Victools
     * @throws JsonSchemaGenerationException if the member combines a non-default
     *     {@code implementation = ...} redirect with an overridden declared type, or if its declared
     *     type graph nests deeper than the guard can walk
     */
    @Override
    public CustomPropertyDefinition provideCustomSchemaDefinition(M member, SchemaGenerationContext context) {
        if (member == null) {
            return null;
        }
        Schema declaredSchema = redirectingSchemaAnnotation(member);
        if (declaredSchema == null) {
            return null;
        }
        // Class.class-typed annotation members are never null (the compiler rejects a null default
        // or literal), so only the sentinel default Void.class needs to be checked here.
        Class<?> implementation = declaredSchema.implementation();
        if (implementation == Void.class) {
            return null;
        }

        Member rawMember = member.getRawMember();
        String boundedName = Diagnostics.truncate(memberName(member, rawMember), MAX_MEMBER_NAME_LENGTH);
        Class<?> overridden = firstOverriddenClass(member.getDeclaredType(), 0, boundedName);
        if (overridden == null) {
            return null;
        }
        throw Diagnostics.failure(
                "cannot generate a JSON Schema: property '" + boundedName
                        + "' declares a @Schema implementation redirect while its declared type graph carries an"
                        + " effective schema override for " + Diagnostics.typeIdentity(overridden),
                null);
    }

    /**
     * Resolves the {@code @Schema} whose {@code implementation} member the Swagger module would read
     * for this member, mirroring {@code Swagger2Module.getSchemaAnnotationValue(MemberScope)}: an
     * {@code @ArraySchema.schema()} in a fake container item scope, otherwise a direct
     * {@code @Schema}, otherwise an {@code @ArraySchema.arraySchema()}.
     *
     * @param member the member scope being inspected
     * @return the annotation the redirect would be read from, or {@code null} when none applies
     */
    private static Schema redirectingSchemaAnnotation(MemberScope<?, ?> member) {
        if (member.isFakeContainerItemScope()) {
            // In a fake container item scope the Swagger module reads the element metadata only, so a
            // direct @Schema on the container member is deliberately not consulted here.
            ArraySchema arraySchema = member.getAnnotationConsideringFieldAndGetter(ArraySchema.class);
            return arraySchema == null ? null : arraySchema.schema();
        }
        Schema declaredSchema = member.getAnnotationConsideringFieldAndGetter(Schema.class);
        if (declaredSchema != null) {
            return declaredSchema;
        }
        ArraySchema arraySchema = member.getAnnotationConsideringFieldAndGetter(ArraySchema.class);
        return arraySchema == null ? null : arraySchema.arraySchema();
    }

    /**
     * Renders the bounded identity of the offending member.
     *
     * @param member    the member scope
     * @param rawMember the underlying reflective member, possibly {@code null}
     * @return the qualified member name
     */
    private static String memberName(MemberScope<?, ?> member, Member rawMember) {
        if (rawMember == null) {
            return String.valueOf(member.getName());
        }
        return rawMember.getDeclaringClass().getSimpleName() + "." + rawMember.getName();
    }

    /**
     * Finds the first class in a resolved declared type graph that carries an effective override.
     *
     * <p>The walk descends into the node's array element type, its self-declared type parameters, and
     * the container or wrapper payload types it inherits from a supertype binding — see
     * {@link #declaredTypeGraphChildren(ResolvedType, Class)} for why the inherited half is not
     * optional. Every child is visited at {@code depth + 1} regardless of which half it came from, so
     * widening the walk does not change the depth accounting for any graph the narrower walk already
     * covered.
     *
     * <p>Exceeding {@link TypeGrammar#MAX_DEPTH} throws instead of returning {@code null}: past the
     * bound the guard has not proven the absence of an override, and reporting "none found" would
     * turn the bound into a silent bypass of the very drop this guard refuses to emit.
     *
     * @param type       the graph node to inspect, possibly {@code null}
     * @param depth      the current nesting depth
     * @param memberName the bounded member identity to name in a depth failure
     * @return the overridden class, or {@code null} when the graph carries none
     * @throws JsonSchemaGenerationException if the graph nests past {@link TypeGrammar#MAX_DEPTH}
     */
    private Class<?> firstOverriddenClass(ResolvedType type, int depth, String memberName) {
        if (type == null) {
            return null;
        }
        if (depth > TypeGrammar.MAX_DEPTH) {
            throw Diagnostics.failure(
                    "cannot generate a JSON Schema: property '" + memberName
                            + "' declares a @Schema implementation redirect and its declared type nesting exceeds the"
                            + " supported depth of " + TypeGrammar.MAX_DEPTH
                            + ", so an effective schema override cannot be ruled out",
                    null);
        }
        Class<?> erasedType = type.getErasedType();
        if (profile.fragmentFor(erasedType) != null) {
            return erasedType;
        }
        if (type.isArray()) {
            return firstOverriddenClass(type.getArrayElementType(), depth + 1, memberName);
        }
        for (ResolvedType child : declaredTypeGraphChildren(type, erasedType)) {
            Class<?> hit = firstOverriddenClass(child, depth + 1, memberName);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Collects the child nodes of one graph node: the type's self-declared resolved parameters plus
     * the payload types it inherits from a container or wrapper supertype binding, with map key
     * positions excluded from both. The class javadoc's closed enumeration lists every position that
     * counts as a child here and why that list is complete at the pinned Victools version.
     *
     * <p>{@link ResolvedType#getTypeParameters()} answers only for parameters the type itself
     * <em>declares</em>. A container subclass such as {@code class Amounts extends ArrayList<BigDecimal>}
     * declares none, so that accessor alone reports an empty graph — while
     * {@code typeParametersFor(Iterable.class)} still resolves the element to {@code BigDecimal} and
     * Victools publishes the type as an array carrying that element's schema. Reading only the
     * self-declared half would therefore let an implementation redirect drop the element's profile
     * fragment with no trace, which is precisely the outcome the guard refuses.
     *
     * <p>The same reasoning forces the {@link Supplier} binding. Under the pinned option preset
     * Victools flattens a supplier to its payload, matching <em>implementors</em> and resolving the
     * payload through the inherited binding, so {@code class DecimalSupplier implements
     * Supplier<BigDecimal>} publishes {@code BigDecimal}'s schema while declaring no parameters of its
     * own and being neither an {@link Iterable} nor a {@link Map}. {@link Optional} needs no separate
     * read: it is {@code final}, so its binding is always a self-declared parameter of the node
     * itself.
     *
     * <p>The map binding is read the same way but at index 1 only: an inherited key position stays
     * mapper-owned exactly like a directly declared one, so widening the walk must not turn a
     * key-only override into a false positive.
     *
     * <p>Children are de-duplicated by <em>value</em>, not by instance: {@link List#contains(Object)}
     * uses {@link ResolvedType#equals(Object)}, which compares the erased type and its bindings, so
     * two equal-but-distinct resolutions of the same element collapse to one child. For a directly
     * parameterized container the halves resolve to the same element — {@code List<BigDecimal>}
     * reports {@code BigDecimal} through both — so de-duplication keeps such a graph identical to the
     * self-parameters-only walk and prevents the branching factor from growing at every level of a
     * deeply nested container.
     *
     * @param type       the graph node whose children are wanted
     * @param erasedType the node's erased class, already resolved by the caller
     * @return the child nodes to descend into, in declaration-then-inheritance order
     */
    private static List<ResolvedType> declaredTypeGraphChildren(ResolvedType type, Class<?> erasedType) {
        List<ResolvedType> children = new ArrayList<>();
        List<ResolvedType> declared = type.getTypeParameters();
        boolean mapKeyExcluded = Map.class.isAssignableFrom(erasedType) && declared.size() == 2;
        for (int index = 0; index < declared.size(); index++) {
            if (mapKeyExcluded && index == 0) {
                continue;
            }
            addDistinct(children, declared.get(index));
        }
        List<ResolvedType> inheritedElement = type.typeParametersFor(Iterable.class);
        if (inheritedElement != null && !inheritedElement.isEmpty()) {
            addDistinct(children, inheritedElement.get(0));
        }
        List<ResolvedType> inheritedMapping = type.typeParametersFor(Map.class);
        if (inheritedMapping != null && inheritedMapping.size() == 2) {
            addDistinct(children, inheritedMapping.get(1));
        }
        List<ResolvedType> inheritedSupplied = type.typeParametersFor(Supplier.class);
        if (inheritedSupplied != null && !inheritedSupplied.isEmpty()) {
            addDistinct(children, inheritedSupplied.get(0));
        }
        return children;
    }

    /**
     * Appends a child node unless an equal one was already collected.
     *
     * @param children the accumulating child list
     * @param child    the candidate child, possibly {@code null}
     */
    private static void addDistinct(List<ResolvedType> children, ResolvedType child) {
        if (child != null && !children.contains(child)) {
            children.add(child);
        }
    }
}
