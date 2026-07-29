// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.context.RestContextMessages;
import dev.vertique.rest.core.context.RestContextTypes;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import io.vertx.ext.web.FileUpload;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;

/**
 * Validates route registration constraints at startup.
 *
 * <p>Checks include: body parameter count, form/body conflicts, unsupported native multipart
 * collection shapes, non-{@link Comparable} elements in a {@code SortedSet}/{@code NavigableSet}
 * shape, same-name parameters declaring incompatible multiplicities, duplicate operationIds,
 * unmatched operationIds, and security annotations present without an auth module installed.
 * All methods are static; this class is not intended to be instantiated.
 */
class RouteValidator {

    private RouteValidator() {}

    /**
     * Validates method params: at most one body param, form and body params are mutually exclusive.
     *
     * @param meta the resource method metadata to validate
     * @return list of violations found; empty if the method params are valid
     */
    static List<RouteRegistrationViolation> validateMethodParams(ResourceMethodMeta meta) {
        List<RouteRegistrationViolation> violations = new ArrayList<>();
        long bodyCount = meta.params().stream()
                .filter(p -> p.source() == ResourceMethodMeta.ParamSource.BODY)
                .count();
        if (bodyCount > 1) {
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.MULTIPLE_BODY_PARAMS,
                    String.format(
                            "Method %s.%s() has %d body parameters; at most one is allowed",
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            bodyCount)));
        }
        boolean hasFormParams = meta.params().stream()
                .anyMatch(p -> p.source() == ResourceMethodMeta.ParamSource.FORM
                        || p.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS
                        || p.source() == ResourceMethodMeta.ParamSource.ENTITY_PARTS);
        if (hasFormParams && bodyCount > 0) {
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.FORM_AND_BODY_CONFLICT,
                    String.format(
                            "Method %s.%s() mixes @FormParam/file upload parameters with a body parameter; use one or the other",
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName())));
        }
        addContextParamViolations(meta, violations);
        addFilePartViolations(meta, violations);
        addMultipartCollectionShapeViolations(meta, violations);
        addSortedSetElementViolations(meta, violations);
        addDuplicateParamMultiplicityViolations(meta, violations);
        return violations;
    }

    /**
     * Rejects two parameters that bind the <em>same name</em> from the same request source but declare
     * <em>incompatible multiplicities</em> — one collection-shaped ({@code componentType() != null}), the
     * other scalar. Such a declaration has no correct binding: {@code DefaultBoundRequest.findDescriptor}
     * resolves a name to the <em>first</em> matching descriptor, and that single descriptor decides the
     * multiplicity of the bound value for both parameters, so exactly one of them is always mis-bound —
     * the collection parameter degrades to a one-element collection (dropping every repeated value), or
     * the scalar parameter receives a {@code JsonArray} its declared type has no converter for. Startup
     * therefore fails fast, exactly as it does for the other unbindable shapes (see
     * {@link #addMultipartCollectionShapeViolations}, {@link #addSortedSetElementViolations}).
     *
     * <p><b>This guard is scoped to multiplicity conflicts and nothing else.</b> Two declarations of one
     * name with the <em>same</em> multiplicity are outside its scope — they are not validated here, and
     * that is <em>not</em> a claim that they bind correctly. They do share one descriptor, so the
     * multiplicity of the bound value fits both; but the shared descriptor is the <em>first</em>
     * declaration's ({@code DefaultBoundRequest.findDescriptor} is first-match), and
     * {@code DefaultBoundRequest.wrapScalar} eagerly coerces the raw value with it while
     * {@code ParameterExtractor.coerce} passes an already-converted value through unchanged. So a
     * same-multiplicity pair still mis-binds whenever the two declarations differ in a way the single
     * descriptor decides:
     *
     * <ul>
     *   <li><b>different declared types</b> — {@code @QueryParam("id") Integer} plus
     *       {@code @QueryParam("id") UUID} mounts, then fails in {@code Method.invoke} on every request
     *       carrying {@code id}, because the value was converted once, to the first declared type;
     *   <li><b>different conversion-affecting annotations</b> on the same declared type — silent, not a
     *       failure: {@code ConversionContexts.forDescriptor} builds the context from the first
     *       declaration's annotations, so the second parameter receives a value converted under the
     *       first's semantics.
     * </ul>
     *
     * <p>Two different collection shapes of one name ({@code List<String>} plus {@code Set<String>}) are
     * likewise unreported — multiplicity, not the concrete collection type, is what the single descriptor
     * decides. Widening the guard to same-multiplicity mis-binding would reject declarations that mount
     * today, so it is a separate, consumer-visible decision rather than an omission of this one.
     *
     * <p><b>Scoped to the sources {@code findDescriptor} is consulted for</b> — see
     * {@link #isDescriptorMatchedSource}, and {@link #bindsSameName} for the per-location name-matching
     * rule it mirrors. {@code FORM} is excluded because
     * {@code ParameterExtractor.extractFormParam} reads {@code formAttributes()} per parameter instead of
     * going through {@code findDescriptor}: a scalar {@code @FormParam} takes the first submitted value
     * while a collection-shaped one of the same name takes all of them, so both bind correctly.
     *
     * <p>At most <em>one</em> violation is emitted per colliding name, against its first declaration and
     * the first conflicting partner: a name declared three times is one defect with one fix, not two.
     * Running from {@link #validateMethodParams} places the check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest of
     * the operation as soon as method-param validation reports anything), so the shape-specific
     * diagnostic wins over a converter one. The check is independent of the other shape guards, so a
     * conflicting pair that is <em>also</em> an unsupported shape reports both violations — both are real
     * and each has its own fix.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any multiplicity-conflict violations are appended
     */
    private static void addDuplicateParamMultiplicityViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        List<ResourceMethodMeta.ParamMeta> params = meta.params();
        List<ResourceMethodMeta.ParamMeta> reported = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            ResourceMethodMeta.ParamMeta first = params.get(i);
            if (!isDescriptorMatchedSource(first.source()) || first.name() == null) {
                continue;
            }
            if (reported.stream().anyMatch(already -> bindsSameName(already, first))) {
                continue;
            }
            for (int j = i + 1; j < params.size(); j++) {
                ResourceMethodMeta.ParamMeta second = params.get(j);
                if (!bindsSameName(first, second) || !multiplicityConflicts(first, second)) {
                    continue;
                }
                reported.add(first);
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT,
                        String.format(
                                "%s parameters '%s' (%s) and '%s' (%s) of %s.%s() bind the same name%s but declare "
                                        + "incompatible multiplicities — one collection-shaped, one scalar. Binding "
                                        + "resolves a request name to a single declared parameter (first match wins), "
                                        + "so exactly one of the two would always be mis-bound: give them distinct "
                                        + "names, or declare both with the same multiplicity.",
                                first.source(),
                                first.name(),
                                describeShape(first),
                                second.name(),
                                describeShape(second),
                                meta.method().getDeclaringClass().getSimpleName(),
                                meta.method().getName(),
                                matchesNameCaseInsensitively(first.source()) ? " (matched case-insensitively)" : "")));
                break;
            }
        }
    }

    /**
     * Returns whether a parameter source's multiplicity is decided by
     * {@code DefaultBoundRequest.findDescriptor}, i.e. whether two same-name declarations of that source
     * necessarily share one descriptor.
     *
     * <p>{@code FORM} is absent because {@code ParameterExtractor.extractFormParam} reads
     * {@code formAttributes()} per parameter and never consults {@code findDescriptor}. {@code PATH} is
     * present even though no {@code @PathParam} carries a component type today — so a conflict is
     * currently unreachable there — because {@code findDescriptor} <em>is</em> consulted for it
     * ({@code DefaultBoundRequest.bindPath}); keeping it in scope means adding {@code @PathParam}
     * collection support cannot silently escape the guard.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code PATH}, {@code QUERY}, {@code HEADER}, and {@code COOKIE}
     */
    private static boolean isDescriptorMatchedSource(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE -> true;
            case FORM, BODY, CONTEXT, PRECONDITIONS, FILE_UPLOADS, ENTITY_PARTS, BEAN_PARAM -> false;
        };
    }

    /**
     * Returns whether two parameters resolve to the <em>same</em> declared descriptor at bind time, i.e.
     * whether they share a source and a name under that source's matching rule.
     *
     * <p>The name comparison mirrors {@code DefaultBoundRequest.findDescriptor} exactly, including its use
     * of {@link String#equalsIgnoreCase(String)} rather than a lower-cased key: {@code HEADER} and
     * {@code COOKIE} match case-insensitively (both bound maps are keyed by lower-cased name, and HTTP/2
     * transmits header names in lower case per RFC 9113 §8.2.1), while {@code PATH} and {@code QUERY}
     * match verbatim. Diverging from that rule would misjudge which declarations actually collide.
     *
     * @param first  the earlier declaration; its source is descriptor-matched and its name non-{@code null}
     * @param second the later declaration
     * @return {@code true} when both bind the same request name from the same source
     */
    private static boolean bindsSameName(ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        if (first.source() != second.source() || second.name() == null) {
            return false;
        }
        return matchesNameCaseInsensitively(first.source())
                ? first.name().equalsIgnoreCase(second.name())
                : first.name().equals(second.name());
    }

    /**
     * Returns whether the given source's declared parameter names are matched case-insensitively by
     * {@code DefaultBoundRequest.findDescriptor} and {@code ParameterExtractor.lookup}.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code HEADER} and {@code COOKIE}
     */
    private static boolean matchesNameCaseInsensitively(ResourceMethodMeta.ParamSource source) {
        return source == ResourceMethodMeta.ParamSource.HEADER || source == ResourceMethodMeta.ParamSource.COOKIE;
    }

    /**
     * Returns whether two declarations of one name disagree about <em>multiplicity</em> — exactly one of
     * them carries a component type. Two collection shapes agree on multiplicity (both bind all values),
     * as do two scalars.
     *
     * <p>Agreeing on multiplicity is not the same as binding correctly: a same-multiplicity pair whose
     * declared types or conversion-affecting annotations differ is still mis-bound, and is deliberately
     * outside this guard's scope (see {@link #addDuplicateParamMultiplicityViolations}).
     *
     * @param first  the earlier declaration
     * @param second the later declaration
     * @return {@code true} when one is collection-shaped and the other is scalar
     */
    private static boolean multiplicityConflicts(
            ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        return (first.componentType() == null) != (second.componentType() == null);
    }

    /**
     * Renders a parameter's declared shape for a diagnostic: {@code String}, {@code List<String>}, or
     * {@code String[]}.
     *
     * @param pm the parameter metadata
     * @return the declared type's simple name, parameterized with the element type for a collection shape
     */
    private static String describeShape(ResourceMethodMeta.ParamMeta pm) {
        if (pm.componentType() == null || pm.type().isArray()) {
            return pm.type().getSimpleName();
        }
        return pm.type().getSimpleName() + "<" + pm.componentType().getSimpleName() + ">";
    }

    /**
     * Rejects a parameter declared as {@code SortedSet<T>} / {@code NavigableSet<T>} whose element type
     * is not comparable to itself (see {@link #isSelfComparable} — which covers an element type that
     * does not implement {@link Comparable} at all, one whose effective {@code compareTo} accepts a type
     * the element type is not assignable to, and one that only <em>declares</em> such a
     * {@code Comparable} without implementing it, as an interface or abstract class does).
     * {@code ParameterExtractor.materializeCollection} builds both shapes with
     * {@code new TreeSet<>(elements)}, which orders elements by their natural ordering, so such a
     * parameter has no valid materialization: every request supplying a value would throw
     * {@code ClassCastException} (a 500), and a JAX-RS declaration cannot supply a
     * {@link java.util.Comparator}. Startup therefore fails fast, exactly as it does for an
     * unsupported native multipart shape (see {@link #addMultipartCollectionShapeViolations}).
     *
     * <p><b>Scoped to the sources that can carry a non-{@code null} {@code componentType} and are
     * materialized element-wise</b> — {@code QUERY}, {@code HEADER}, {@code COOKIE}, and {@code FORM}
     * (see {@link #isElementWiseMaterializedSource}).
     *
     * <p>{@code PATH} shares the very same code block: the {@code componentType != null} branch of
     * {@code ParameterExtractor.extractScalarValue} is reached from the same {@code switch} that maps
     * {@code PATH} to {@code boundRequest.pathParameters()}, so nothing in the extractor exempts it.
     * PATH is safe here only because <em>no</em> PATH parameter ever carries a component type: both
     * {@code ResourceScanner.resolveComponentType} and
     * {@code EffectiveJaxRsContractResolver.resolvesComponentType} gate collection resolution to the
     * other bindable sources, because {@code @PathParam} collection support is not implemented.
     * <b>Implementing it would silently drop PATH out of this guard's coverage</b> — the same change
     * must add {@code PATH} to {@link #isElementWiseMaterializedSource}.
     *
     * <p>A {@code BODY} parameter is excluded because <b>body validity belongs to the selected
     * {@code RequestBodyDecoder}, so the route validator does not adjudicate it</b>:
     * {@code extractParamValue} dispatches BODY to {@code deserializeBody}, so
     * {@code ParameterExtractor.materializeCollection} never sees it. That is a statement about
     * <em>ownership</em>, not a safety claim — {@code JsonRequestBodyDecoder.decodeArray} calls
     * {@code TypeFactory.constructCollectionType(SortedSet.class, elementClass)}, and Jackson's default
     * concrete type for {@code SortedSet}/{@code NavigableSet} <em>is</em> {@code TreeSet}, so under
     * that decoder a non-self-comparable body element does fail per request. A custom decoder may
     * instead return a comparator-backed set, which is exactly why the decision is the decoder's and
     * not this validator's. The scoping is also load-bearing for runtime/codegen parity: the generated
     * dispatch path <em>does</em> resolve a {@code componentType} for BODY
     * ({@code EffectiveJaxRsContractResolver.resolvesComponentType}, a deliberately deferred
     * divergence), so without it a codegen'd resource with a {@code SortedSet<Pojo>} body would fail
     * startup while its reflective twin — whose scanner hard-codes {@code componentType = null} for
     * BODY — mounts fine.
     *
     * <p>{@code FILE_UPLOADS} / {@code ENTITY_PARTS} are likewise excluded: both are always declared
     * {@code List<T>} and are materialized natively, never through a {@code TreeSet}.
     *
     * <p>Running from {@link #validateMethodParams} places this check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest
     * of the operation as soon as method-param validation reports anything), so a non-self-comparable
     * element type that also lacks a converter is reported once, with the shape-specific diagnostic.
     *
     * <p>Native multipart element types ({@link FileUpload} / {@link EntityPart}) are skipped: they are
     * not {@link Comparable} either, but their accurate diagnostic is
     * {@code UNSUPPORTED_MULTIPART_COLLECTION_SHAPE} on {@code FORM} (reported by
     * {@link #addMultipartCollectionShapeViolations}) and {@code UNRESOLVABLE_PARAM_CONVERTER} on any
     * other source. Only collection-shaped
     * parameters are inspected, so array shapes and bean-param fields — neither of which carries a
     * component type here — never reach the check.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any non-self-comparable sorted-element violations are
     *                   appended
     */
    private static void addSortedSetElementViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (!isElementWiseMaterializedSource(pm.source())) {
                continue;
            }
            if (pm.componentType() == null || !SortedSet.class.isAssignableFrom(pm.type())) {
                continue;
            }
            if (isSelfComparable(pm.componentType()) || isNativeMultipartCollection(pm)) {
                continue;
            }
            String shape = pm.type().getSimpleName();
            String element = pm.componentType().getSimpleName();
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.NON_COMPARABLE_SORTED_SET_ELEMENT,
                    String.format(
                            "Parameter '%s' of %s.%s() declares %s<%s>, but %s is not comparable to itself; "
                                    + "a %s is materialized as a TreeSet, so every request carrying a value would "
                                    + "fail — declare it as Set<%s>, List<%s>, or Collection<%s>, or make %s "
                                    + "implement Comparable<%s>.",
                            pm.name(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            shape,
                            element,
                            element,
                            shape,
                            element,
                            element,
                            element,
                            element,
                            element)));
        }
    }

    /**
     * Returns whether a parameter source's values are materialized <em>element-wise</em> into the
     * declared collection type by {@code ParameterExtractor.materializeCollection} — the only path that
     * builds a {@code TreeSet} and therefore the only one the sorted-shape guard applies to.
     *
     * <p>{@code PATH} is absent only because no PATH parameter can carry a component type today, not
     * because the extractor treats it differently; see {@link #addSortedSetElementViolations} before
     * adding {@code @PathParam} collection support.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code QUERY}, {@code HEADER}, {@code COOKIE}, and {@code FORM}
     */
    private static boolean isElementWiseMaterializedSource(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case QUERY, HEADER, COOKIE, FORM -> true;
            case PATH, BODY, CONTEXT, PRECONDITIONS, FILE_UPLOADS, ENTITY_PARTS, BEAN_PARAM -> false;
        };
    }

    /**
     * Returns whether {@code elementType} is comparable to <em>itself</em>, i.e. whether
     * {@code new TreeSet<>(elements)} can order instances of it without a
     * {@link ClassCastException}.
     *
     * <p>Raw assignability to {@link Comparable} is <em>not</em> sufficient: a
     * {@code class Money implements Comparable<BigDecimal>} is assignable to {@link Comparable}, but
     * {@code TreeSet} invokes the compiler-synthesized {@code compareTo(Object)} bridge, which casts its
     * argument to {@code BigDecimal} and throws. What decides is therefore <b>the type that bridge casts
     * to</b>, and the verdict is whether that type is assignable <em>from</em> {@code elementType} — i.e.
     * whether an element can be passed to its own comparison method.
     *
     * <p>Two independent pieces of reflective evidence name that cast target, and <b>neither is
     * sufficient alone</b> — the rule is their conjunction ({@link #comparableCastTarget} plus
     * {@link #declaredCompareToTargets}, combined by {@link #resolveCastTarget}):
     *
     * <ul>
     *   <li><b>The declaration site</b> — the erasure of {@link Comparable}'s type argument where the
     *       hierarchy instantiates it. This is the only evidence for a <em>declaration-only</em> element
     *       type (an interface, a sealed interface, or an abstract class that leaves {@code compareTo}
     *       abstract): such a type declares no concrete {@code compareTo} at all, so the method scan sees
     *       nothing but the erased {@code Comparable.compareTo(Object)} and would accept it
     *       unconditionally. It is also what distinguishes the real {@link Comparable} implementation from
     *       an unrelated {@code compareTo} overload. It yields nothing when the argument is a type
     *       variable bound further down the hierarchy.
     *   <li><b>The effective {@code compareTo}</b> — the parameter type of the non-{@linkplain
     *       java.lang.reflect.Method#isBridge() bridge}, non-{@linkplain
     *       java.lang.reflect.Method#isSynthetic() synthetic} {@code compareTo} the type actually
     *       inherits. This is the only evidence for a <em>forwarded type variable</em>, where the compiler
     *       has already applied erasure for us: the declaration site sees only {@code T}, while the
     *       emitted signature names the leftmost bound the bridge really casts to.
     * </ul>
     *
     * <p>The rule resolves every shape without carve-outs:
     *
     * <ul>
     *   <li>not {@link Comparable} at all &rarr; <b>rejected</b>;
     *   <li>{@code Comparable<Self>}, {@code Comparable<Supertype>}, or
     *       {@code Comparable<ParameterizedSupertype<?>>} — e.g. {@code LocalDateTime}'s
     *       {@code Comparable<ChronoLocalDateTime<?>>} &rarr; <b>accepted</b>;
     *   <li>{@code Comparable<Unrelated>} &rarr; <b>rejected</b>, whether the argument is declared on the
     *       element type, on an ancestor, on an <em>interface</em>, on a <em>sealed interface</em>, or on
     *       an <em>abstract class</em> that never declares {@code compareTo};
     *   <li>a <em>raw</em> {@code implements Comparable} &rarr; <b>accepted</b>: the effective method is
     *       {@code compareTo(Object)};
     *   <li>an {@code enum} &rarr; <b>accepted</b> without a special case: {@code Enum<E extends
     *       Enum<E>>} erases {@code compareTo(E)} to {@code compareTo(Enum)}, which every constant
     *       satisfies;
     *   <li>a type variable forwarded through an interface or superclass &rarr; decided by that
     *       variable's <b>leftmost bound</b>, which is exactly what the compiler erased it to. So
     *       {@code class X implements Ord<X>} (with {@code interface Ord<T> extends Comparable<T>}),
     *       {@code class Node<T extends Node<T>> implements Comparable<T>}, and
     *       {@code class Sub extends Base<String>} over an <em>unbounded</em>
     *       {@code Base<T> implements Comparable<T>} are all <b>accepted</b> — a real {@code TreeSet}
     *       orders all three — while {@code class Bad implements Ord<String>} and a subclass of a
     *       <em>bounded</em> {@code Base<T extends CharSequence> implements Comparable<T>} are
     *       <b>rejected</b>, because their effective {@code compareTo} takes {@code String} /
     *       {@code CharSequence} and the bridge cast throws;
     *   <li>a forwarded type variable that reaches <em>no</em> concrete {@code compareTo} either — e.g.
     *       {@code abstract class C implements Ord<String>} — &rarr; <b>rejected</b>, the fail-closed
     *       answer for a shape whose safety no available evidence proves.
     * </ul>
     *
     * @param elementType the declared element type of a {@code SortedSet}/{@code NavigableSet} shape
     * @return {@code true} when a {@code TreeSet} of {@code elementType} can order its own elements
     */
    private static boolean isSelfComparable(Class<?> elementType) {
        if (!Comparable.class.isAssignableFrom(elementType)) {
            return false;
        }
        Class<?> castTarget =
                resolveCastTarget(comparableCastTarget(elementType), declaredCompareToTargets(elementType));
        return castTarget != null && castTarget.isAssignableFrom(elementType);
    }

    /**
     * Combines the two pieces of cast-target evidence {@link #isSelfComparable} collects into the single
     * type the {@code compareTo(Object)} bridge casts to. The result is <b>independent of
     * {@link Class#getMethods()} iteration order</b>, which the JDK explicitly leaves unspecified.
     *
     * <p>The precedence, in order:
     *
     * <ol>
     *   <li><b>No concrete {@code compareTo} below {@link Comparable}</b> &rarr; the declaration site is
     *       the only evidence, so it decides (possibly {@code null}). This is the declaration-only case:
     *       an interface or abstract element type whose implementors emit the bridge.
     *   <li><b>The declaration site names one of the declared parameter types</b> &rarr; that type. This
     *       is the deterministic answer whenever several {@code compareTo} overloads are visible: the
     *       {@link Comparable} implementation is the one whose parameter matches the declared argument,
     *       and an unrelated overload — which no {@code TreeSet} ever calls — cannot displace it.
     *   <li><b>Otherwise</b> &rarr; the most specific declared parameter type, i.e. the unique candidate
     *       every other candidate is assignable from (see {@link #mostSpecific}). This resolves the
     *       forwarded-type-variable shapes, whose declaration site yields nothing. When no unique most
     *       specific candidate exists the declaration site is used as a last resort, so an unresolvable
     *       ambiguity ends as {@code null} — rejected — rather than as an order-dependent coin flip.
     * </ol>
     *
     * @param declaredAtSite  the erasure of {@link Comparable}'s type argument at its declaration site,
     *                        or {@code null} when the argument is a type variable or another non-erasable
     *                        shape
     * @param declaredTargets the parameter types of every effective {@code compareTo} declared below
     *                        {@link Comparable} itself; possibly empty
     * @return the bridge's cast target, or {@code null} when no evidence names one
     */
    private static Class<?> resolveCastTarget(Class<?> declaredAtSite, Set<Class<?>> declaredTargets) {
        if (declaredTargets.isEmpty()) {
            return declaredAtSite;
        }
        if (declaredAtSite != null && declaredTargets.contains(declaredAtSite)) {
            return declaredAtSite;
        }
        Class<?> specific = mostSpecific(declaredTargets);
        return specific != null ? specific : declaredAtSite;
    }

    /**
     * Collects the parameter type of every effective {@code compareTo} that {@code elementType} inherits
     * from a declaration <em>below</em> {@link Comparable} itself.
     *
     * <p>{@link Class#getMethods()} exposes inherited public methods, so this sees a {@code compareTo}
     * declared on any ancestor. {@linkplain java.lang.reflect.Method#isBridge() Bridge} and
     * {@linkplain java.lang.reflect.Method#isSynthetic() synthetic} methods are skipped — the bridge is
     * what we are trying to characterize, not evidence about itself.
     *
     * <p><b>{@link Comparable}'s own erased {@code compareTo(Object)} is excluded</b>, and that exclusion
     * is the whole point: for an element type that declares no concrete {@code compareTo} — an interface,
     * a sealed interface, or an abstract class leaving it abstract — {@code getMethods()} still reports
     * the interface's abstract {@code compareTo(Object)}. Counting it would make {@link Object} the cast
     * target and accept every such declaration unconditionally, including
     * {@code interface Bad extends Comparable<String>}, whose implementors' bridges cast to
     * {@link String}. Excluding it makes the empty result an honest signal — "no concrete implementation
     * is visible, defer to the declaration site" — rather than a false accept.
     *
     * @param elementType the element type, already known to be assignable to {@link Comparable}
     * @return the distinct declared parameter types, in {@code getMethods()} order; empty when no
     *         concrete {@code compareTo} exists below {@link Comparable}
     */
    private static Set<Class<?>> declaredCompareToTargets(Class<?> elementType) {
        Set<Class<?>> targets = new LinkedHashSet<>();
        for (Method candidate : elementType.getMethods()) {
            if (!"compareTo".equals(candidate.getName()) || candidate.getParameterCount() != 1) {
                continue;
            }
            if (candidate.isBridge() || candidate.isSynthetic() || candidate.getDeclaringClass() == Comparable.class) {
                continue;
            }
            targets.add(candidate.getParameterTypes()[0]);
        }
        return targets;
    }

    /**
     * Returns the unique most specific type in {@code candidates}, i.e. the one every other candidate is
     * assignable from.
     *
     * @param candidates the candidate types; never empty
     * @return the most specific candidate, or {@code null} when two candidates are mutually unassignable
     *         and no single one subsumes the rest
     */
    private static Class<?> mostSpecific(Set<Class<?>> candidates) {
        for (Class<?> candidate : candidates) {
            if (candidates.stream().allMatch(other -> other.isAssignableFrom(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Resolves the erasure of {@link Comparable}'s type argument at the site where {@code elementType}'s
     * hierarchy instantiates it — the type a bridge generated for that declaration casts its argument to.
     *
     * <p>The declaration is looked up over the element type's own class chain (most-derived first, so the
     * closest declaration wins) and then over every transitively implemented interface
     * ({@link TypeResolver#getAllInterfaces(Class)}). A compiling type can instantiate {@link Comparable}
     * at most once, so the first declaration found is the only one. An interface element type has no
     * superclass chain, so its own {@code extends} clause is inspected first and its superinterfaces
     * after.
     *
     * @param elementType the element type, already known to be assignable to {@link Comparable}
     * @return {@link Object} for a raw {@code implements Comparable} (no cast is generated), the erasure
     *         of the declared type argument, or {@code null} when the argument is a type variable (or
     *         another non-erasable shape) whose concrete binding lives elsewhere in the hierarchy
     */
    private static Class<?> comparableCastTarget(Class<?> elementType) {
        List<Class<?>> declarationSites = new ArrayList<>();
        for (Class<?> current = elementType; current != null && current != Object.class; ) {
            declarationSites.add(current);
            current = current.getSuperclass();
        }
        declarationSites.addAll(TypeResolver.getAllInterfaces(elementType));
        for (Class<?> site : declarationSites) {
            for (Type declared : site.getGenericInterfaces()) {
                if (declared == Comparable.class) {
                    return Object.class;
                }
                if (declared instanceof ParameterizedType parameterized
                        && parameterized.getRawType() == Comparable.class) {
                    return erasure(parameterized.getActualTypeArguments()[0]);
                }
            }
        }
        return null;
    }

    /**
     * Returns the erasure of a declared type argument, i.e. the class the compiler casts to when it
     * synthesizes a bridge for it.
     *
     * @param type the declared type argument
     * @return the argument's own class, the raw type of a parameterized argument, or {@code null} for a
     *         type variable, wildcard, or generic array — none of which names a concrete cast target
     *         without substituting the surrounding declaration
     */
    private static Class<?> erasure(Type type) {
        if (type instanceof Class<?> concrete) {
            return concrete;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    /**
     * Rejects a {@code @FormParam} whose element type is a native multipart target
     * ({@link FileUpload} / {@link EntityPart}) but whose declared type is a collection shape other
     * than {@link List} — {@code Set}, {@code SortedSet}, {@code NavigableSet}, {@code Collection}.
     * {@code ParameterExtractor.extractFormParam} materializes only a scalar target and
     * {@code List<T>} natively (ADR-0191 decision 6); any other shape would fall through to string
     * conversion and fail per-request, so startup fails fast instead.
     *
     * <p>Running from {@link #validateMethodParams} places this check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest
     * of the operation as soon as method-param validation reports anything), so the offending
     * parameter is reported once, with the shape-specific diagnostic rather than a misleading
     * "no ParamConverter for FileUpload" one.
     *
     * <p>Only FORM-sourced parameters are inspected. The {@code FILE_UPLOADS}/{@code ENTITY_PARTS}
     * aggregates are always declared {@code List<T>} by the scanner, and a non-FORM parameter
     * carrying a native element type (e.g. {@code @QueryParam List<FileUpload>}) is not a multipart
     * declaration at all — it is correctly reported as an unresolvable converter.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any unsupported-shape violations are appended
     */
    private static void addMultipartCollectionShapeViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (pm.source() != ResourceMethodMeta.ParamSource.FORM || pm.componentType() == null) {
                continue;
            }
            if (!isNativeMultipartCollection(pm) || isNativelyMaterializedFormCollection(pm)) {
                continue;
            }
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.UNSUPPORTED_MULTIPART_COLLECTION_SHAPE,
                    String.format(
                            "@FormParam '%s' of %s.%s() declares %s<%s>; only a scalar %s and "
                                    + "List<%s> are materialized natively — declare it as List<%s>.",
                            pm.name(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            pm.type().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName())));
        }
    }

    /**
     * Returns whether a collection-shaped parameter's element type is a native multipart target.
     * Reuses {@link #isEntityPartTarget} for the {@link EntityPart} half so the two checks share one
     * notion of "native {@code EntityPart} target".
     *
     * @param pm the parameter metadata; its {@code componentType()} is non-{@code null}
     * @return {@code true} when the element type is {@link FileUpload} or {@link EntityPart}
     */
    private static boolean isNativeMultipartCollection(ResourceMethodMeta.ParamMeta pm) {
        return pm.componentType() == FileUpload.class || isEntityPartTarget(pm);
    }

    /**
     * Returns whether a FORM parameter carrying a native multipart element type is one of the shapes
     * {@code ParameterExtractor.extractFormParam} materializes natively.
     *
     * <p>For {@link FileUpload} targets this delegates to {@link #isSupportedFileUploadTarget} — the
     * same predicate {@code @FilePart} validation uses — so the supported-shape policy lives in one
     * place. {@link EntityPart} has no {@code @FilePart}-facing counterpart ({@code @FilePart} is
     * invalid on an {@code EntityPart} parameter), so the identical {@code List}-only rule is applied
     * directly.
     *
     * @param pm the FORM parameter metadata; its {@code componentType()} is a native multipart type
     * @return {@code true} when the declared shape has a native materialization
     */
    private static boolean isNativelyMaterializedFormCollection(ResourceMethodMeta.ParamMeta pm) {
        if (pm.componentType() == FileUpload.class) {
            return isSupportedFileUploadTarget(pm);
        }
        return pm.type() == List.class;
    }

    /**
     * Validates every {@link FilePart} declaration before operation-descriptor construction. The
     * public {@link FilePartDescriptor} constructor remains the single authority for size and media
     * type grammar; constructor failures are translated into typed startup diagnostics rather than
     * leaking raw {@link IllegalArgumentException}s.
     *
     * <p>Only constrained descriptors participate in overlap checks. For each later declaration,
     * at most one violation is emitted when it overlaps a prior constrained declaration: equal
     * named parts overlap, and an aggregate overlaps every named or aggregate declaration.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which file-part declaration violations are appended
     */
    private static void addFilePartViolations(ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        List<FilePartDescriptor> constrainedDescriptors = new ArrayList<>();

        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            Optional<FilePart> declaration = pm.findAnnotation(FilePart.class);
            if (declaration.isEmpty()) {
                continue;
            }

            String parameterName = filePartParameterName(pm);
            if (isEntityPartTarget(pm)) {
                addFilePartViolation(
                        meta, parameterName, "EntityPart parameters cannot declare file-part constraints", violations);
                continue;
            }
            if (!isSupportedFileUploadTarget(pm)) {
                addFilePartViolation(
                        meta,
                        parameterName,
                        "only @FormParam FileUpload/List<FileUpload> and aggregate "
                                + "List<FileUpload> parameters are supported",
                        violations);
                continue;
            }

            FilePart filePart = declaration.get();
            FilePartDescriptor descriptor;
            try {
                descriptor = new FilePartDescriptor(
                        pm.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS ? null : pm.name(),
                        List.of(filePart.allowedTypes()),
                        filePart.maxSizeBytes());
            } catch (IllegalArgumentException exception) {
                addFilePartViolation(meta, parameterName, exception.getMessage(), violations);
                continue;
            }

            if (!descriptor.constrained()) {
                continue;
            }
            if (constrainedDescriptors.stream()
                    .anyMatch(previous -> filePartDescriptorsOverlap(previous, descriptor))) {
                addFilePartViolation(
                        meta,
                        parameterName,
                        "constraints overlap another constrained file-part declaration",
                        violations);
                continue;
            }
            constrainedDescriptors.add(descriptor);
        }
    }

    private static boolean isEntityPartTarget(ResourceMethodMeta.ParamMeta pm) {
        return pm.type() == EntityPart.class || pm.componentType() == EntityPart.class;
    }

    private static boolean isSupportedFileUploadTarget(ResourceMethodMeta.ParamMeta pm) {
        boolean namedScalar = pm.source() == ResourceMethodMeta.ParamSource.FORM
                && pm.type() == FileUpload.class
                && pm.componentType() == null;
        boolean fileUploadList = pm.type() == List.class && pm.componentType() == FileUpload.class;
        boolean namedList = pm.source() == ResourceMethodMeta.ParamSource.FORM && fileUploadList;
        boolean aggregateList = pm.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS && fileUploadList;
        return namedScalar || namedList || aggregateList;
    }

    private static boolean filePartDescriptorsOverlap(FilePartDescriptor first, FilePartDescriptor second) {
        return first.partName() == null
                || second.partName() == null
                || first.partName().equals(second.partName());
    }

    private static String filePartParameterName(ResourceMethodMeta.ParamMeta pm) {
        return pm.name() != null ? pm.name() : "<aggregate>";
    }

    private static void addFilePartViolation(
            ResourceMethodMeta meta, String parameterName, String reason, List<RouteRegistrationViolation> violations) {
        violations.add(new RouteRegistrationViolation(
                meta.operationId(),
                RouteRegistrationViolation.ViolationType.INVALID_FILE_PART_DECLARATION,
                "@FilePart on parameter '" + parameterName + "' of " + meta.operationId() + ": " + reason));
    }

    /**
     * Validates all {@code CONTEXT}-sourced parameters in the given method metadata and appends any
     * violations found to the supplied list. For each context parameter, at most one violation is
     * emitted, in the following priority order:
     *
     * <ol>
     *   <li>{@link RouteRegistrationViolation.ViolationType#CONTEXT_PARAM_CONFLICT} — the parameter
     *       carries both {@code @Context} and a JAX-RS value-binding annotation
     *       ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, {@code @CookieParam},
     *       {@code @FormParam}, {@code @BeanParam}).</li>
     *   <li>{@link RouteRegistrationViolation.ViolationType#UNSUPPORTED_JAXRS_CONTEXT_TYPE} — the
     *       parameter's type is in
     *       {@link RestContextTypes#RESERVED_UNSUPPORTED_JAXRS_FQNS}.</li>
     *   <li>{@link RouteRegistrationViolation.ViolationType#NON_INJECTABLE_CONTEXT_TYPE} — the
     *       parameter's type is not injectable per
     *       {@link RestContextTypes#isInjectable(Class)}.</li>
     * </ol>
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any context-param violations are appended
     */
    private static void addContextParamViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        String className = meta.method().getDeclaringClass().getSimpleName();
        String methodName = meta.method().getName();

        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (pm.source() != ResourceMethodMeta.ParamSource.CONTEXT) {
                continue;
            }

            // 1. @Context + value-binding annotation conflict
            if (hasBindingAnnotation(pm.annotationsLazy().get())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.CONTEXT_PARAM_CONFLICT,
                        RestContextMessages.contextParamConflict(
                                className, methodName, pm.type().getSimpleName())));
                continue;
            }

            // 2. Reserved JAX-RS type that is unsupported in this version
            if (RestContextTypes.RESERVED_UNSUPPORTED_JAXRS_FQNS.contains(
                    pm.type().getName())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.UNSUPPORTED_JAXRS_CONTEXT_TYPE,
                        RestContextMessages.unsupportedJaxRsContextType(
                                className, methodName, pm.type().getSimpleName())));
                continue;
            }

            // 3. Not an injectable type at all
            if (!RestContextTypes.isInjectable(pm.type())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE,
                        RestContextMessages.nonInjectableContextType(
                                className, methodName, pm.type().getSimpleName())));
            }
        }
    }

    /**
     * Returns {@code true} if the given annotation array contains any JAX-RS value-binding
     * annotation ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     * {@code @CookieParam}, {@code @FormParam}, {@code @BeanParam}).
     *
     * @param annotations the parameter annotation array; may be {@code null}
     * @return {@code true} if a binding annotation is present
     */
    private static boolean hasBindingAnnotation(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Annotation ann : annotations) {
            Class<? extends Annotation> type = ann.annotationType();
            if (type == PathParam.class
                    || type == QueryParam.class
                    || type == HeaderParam.class
                    || type == CookieParam.class
                    || type == FormParam.class
                    || type == BeanParam.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks for a duplicate operationId against already-registered methods.
     *
     * @param meta       the resource method metadata to check
     * @param registered map of already-registered operationIds to their method metadata
     * @return a violation if {@code meta.operationId()} is already registered; empty otherwise
     */
    static Optional<RouteRegistrationViolation> checkDuplicateOperationId(
            ResourceMethodMeta meta, Map<String, ResourceMethodMeta> registered) {
        ResourceMethodMeta existing = registered.get(meta.operationId());
        if (existing != null) {
            return Optional.of(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.DUPLICATE_OPERATION_ID,
                    String.format(
                            "Duplicate operationId '%s': %s.%s() and %s.%s()",
                            meta.operationId(),
                            existing.method().getDeclaringClass().getSimpleName(),
                            existing.method().getName(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName())));
        }
        return Optional.empty();
    }

    /**
     * Checks for restrictive security policies declared when the auth module is not installed.
     *
     * <p>The check evaluates each operation's <strong>effective</strong> security policy
     * ({@code descriptor.effectiveSecurityPolicy()}), not the raw {@code meta.securityPolicy()}. This
     * is essential because a scoped {@code @SecurityRequirement(name=…, scopes={…})} with no
     * {@code @Authorized}/{@code @RolesAllowed} leaves the <em>raw</em> policy non-restrictive (e.g.
     * {@link SecurityPolicy.None}) while folding its scopes into a restrictive
     * {@link SecurityPolicy.Constrained} <em>effective</em> policy (finding C2 / SH-4). The folded
     * scopes are enforced only by the authorization contributor, which is wired solely when the
     * auth-enforcement capability is present; with auth absent, evaluating the raw policy would let
     * such a route mount with its scopes silently unenforced (an authorization fail-open). Evaluating
     * the effective policy closes that gap — mirroring the action-only handling in
     * {@link JaxRsRouteRegistrar#resolveRequiredAction}, where {@link SecurityPolicy.None#isRestrictive()}
     * being {@code false} hides {@code @RequiresAction}-only routes from this check.
     *
     * <p>Action-only routes are <em>not</em> double-reported here: their effective policy stays
     * {@link SecurityPolicy.None} (no scopes fold), so they remain invisible to this check and are
     * caught solely by {@code resolveRequiredAction}. Routes already restrictive in the raw policy
     * ({@code @Authorized}/{@code @RolesAllowed}) are reported exactly once.
     *
     * @param effectivePolicies map of all registered operationIds to their effective security policy
     *                          (raw policy with any single-scheme {@code @SecurityRequirement} scopes
     *                          folded in)
     * @param authEnabled       whether the auth module is installed
     * @return list of violations found; empty if auth is enabled or no restrictive policies exist
     */
    static List<RouteRegistrationViolation> checkSecurityWithoutAuth(
            Map<String, SecurityPolicy> effectivePolicies, boolean authEnabled) {
        List<RouteRegistrationViolation> violations = new ArrayList<>();
        if (!authEnabled) {
            for (Map.Entry<String, SecurityPolicy> entry : effectivePolicies.entrySet()) {
                if (entry.getValue().isRestrictive()) {
                    String operationId = entry.getKey();
                    violations.add(new RouteRegistrationViolation(
                            operationId,
                            RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                            String.format(
                                    "Operation '%s' has restrictive security annotations (or a scoped "
                                            + "@SecurityRequirement) but AuthModule is not installed. "
                                            + "Include AuthModule in your Dagger component to enable security features.",
                                    operationId)));
                }
            }
        }
        return violations;
    }
}
