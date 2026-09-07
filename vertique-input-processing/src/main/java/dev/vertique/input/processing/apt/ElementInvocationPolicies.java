// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.apt;

import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.InvocationPolicyResolver;
import dev.vertique.input.processing.InvocationPolicySource;
import dev.vertique.input.processing.PolicyAxis;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Annotation-processing {@link InvocationPolicySource} adapter: resolves route- and parameter-level
 * canonicalization/sanitization chains from {@code javax.lang.model} elements, so a processor
 * derives exactly the chains the reflective runtime derives for the same declarations.
 *
 * <p>This is the compile-time counterpart of {@code ReflectiveInvocationPolicies}, and it mirrors
 * that adapter's structure: one {@link InvocationPolicySource} per axis and level, all precedence
 * and conflict decisions delegated to {@link InvocationPolicyResolver}. The merged view is built by
 * walking the same documented traversal order the runtime walks — the declaring element first, then
 * the same declaration in each superclass bottom-up (stopping before {@code java.lang.Object}),
 * then in each transitively reachable interface in breadth-first discovery order.
 *
 * <p>Each polarity is then resolved over that ordered site list in <strong>two passes</strong>,
 * exactly as {@code AnnotationResolver.findMetaAnnotation(List, Class)} resolves it over the
 * runtime's flattened merged annotation list: a <em>directly</em> declared annotation anywhere in
 * the merged view wins over a composed (meta-annotated) one anywhere in it, and within each pass
 * the nearest site wins. So an override carrying {@code @ComposedSanitize} (itself meta-annotated
 * {@code @Sanitize(B.class)}) over an interface method carrying a direct {@code @Sanitize(A.class)}
 * resolves to {@code [A.class]} at compile time exactly as it does at runtime. Composed annotations
 * are followed recursively with a visited set — skipping {@code java.lang.annotation.*}
 * meta-annotations, as the runtime does — so a self-referential annotation terminates the walk
 * instead of recursing forever.
 *
 * <p>Method sites are anchored at the method's own declaring type and matched with
 * {@link Elements#overrides}, so a genuine override of a generic supertype declaration is
 * recognised while an unrelated same-named method is not. Type sites are walked from the resource
 * type passed by the caller: a method inherited from a base class still resolves its class-level
 * policies against the type that publishes it, exactly as the runtime does.
 *
 * <p>The declaration-site and element-description strings this adapter produces are identical to
 * the reflective adapter's, so a conflict reported at compile time reads the same as the one
 * reported at startup. Provenance is the site the winning pass matched at — the site of the direct
 * declaration when one exists anywhere in the merged view, otherwise the nearest site carrying a
 * composed one — so the reported declaration site always names the site the resolved value came
 * from.
 *
 * <p>This type requires the JDK {@code java.compiler} module and is never loaded by runtime code.
 */
public final class ElementInvocationPolicies {

    // --- Annotation FQN constants ---

    private static final String CANONICALIZE_FQN = "dev.vertique.core.sanitization.Canonicalize";
    private static final String SANITIZE_FQN = "dev.vertique.core.sanitization.Sanitize";
    private static final String SKIP_CANONICALIZATION_FQN = "dev.vertique.core.sanitization.SkipCanonicalization";
    private static final String SKIP_SANITIZATION_FQN = "dev.vertique.core.sanitization.SkipSanitization";

    /** The traversal terminator: {@code java.lang.Object} carries no policy declarations. */
    private static final String OBJECT_FQN = "java.lang.Object";

    /** The {@code Class[]} attribute both additive annotations declare their chain in. */
    private static final String VALUE_ATTRIBUTE = "value";

    /** Package prefix of the JDK meta-annotations the composed-annotation walk never descends. */
    private static final String JDK_META_ANNOTATION_PREFIX = "java.lang.annotation.";

    private final Elements elements;
    private final Types types;

    /**
     * Creates an adapter bound to the processing environment's element and type utilities.
     *
     * @param elements the processing environment's {@link Elements} utility; must not be
     *     {@code null}
     * @param types    the processing environment's {@link Types} utility; must not be {@code null}
     */
    public ElementInvocationPolicies(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /**
     * Resolves the route-level policies for {@code method} published by {@code owner}.
     *
     * @param method the method whose route-level policies are resolved; must not be {@code null}
     * @param owner  the resource type the route belongs to — its class-level annotations, and those
     *     of its supertypes, are consulted; must not be {@code null}
     * @return the resolved route-level chains; never {@code null}
     * @throws InvocationPolicyConflictException when the method's or the type's merged view
     *     declares both the additive and the skip annotation of the same axis
     */
    public ElementPolicyChains resolveRoute(ExecutableElement method, TypeElement owner) {
        List<Site> methodSites = methodSites(method);
        List<Site> typeSites = typeSites(owner);
        String methodDescribe = "method " + owner.getSimpleName() + "." + method.getSimpleName();
        String typeDescribe = "type " + owner.getSimpleName();

        List<TypeMirror> canonicalizers = InvocationPolicyResolver.resolveRouteChain(
                source(methodSites, methodDescribe, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN),
                source(typeSites, typeDescribe, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN),
                PolicyAxis.CANONICALIZE);
        List<TypeMirror> sanitizers = InvocationPolicyResolver.resolveRouteChain(
                source(methodSites, methodDescribe, SANITIZE_FQN, SKIP_SANITIZATION_FQN),
                source(typeSites, typeDescribe, SANITIZE_FQN, SKIP_SANITIZATION_FQN),
                PolicyAxis.SANITIZE);

        return new ElementPolicyChains(canonicalizers, sanitizers);
    }

    /**
     * Resolves the policies for parameter {@code index} of {@code method}, over the already-resolved
     * route chains.
     *
     * <p>The parameter's merged view is the matching parameter of every method site in the merged
     * method view, so a parameter annotation declared only on an interface method's parameter is
     * honored by the implementing class's parameter.
     *
     * @param parameter the parameter element whose policies are resolved; must not be {@code null}
     * @param index     the zero-based parameter index
     * @param method    the method the parameter belongs to; must not be {@code null}
     * @param owner     the resource type the route belongs to, accepted for call-site symmetry with
     *     {@link #resolveRoute}; parameter provenance is anchored at the method's own declaring
     *     type, exactly as the reflective adapter anchors on the declaring class
     * @param route     the route-level chains resolved by {@link #resolveRoute}; must not be
     *     {@code null}
     * @return the resolved parameter-level chains; never {@code null}
     * @throws InvocationPolicyConflictException when the parameter's merged view declares both the
     *     additive and the skip annotation of the same axis
     */
    public ElementPolicyChains resolveParameter(
            VariableElement parameter,
            int index,
            ExecutableElement method,
            TypeElement owner,
            ElementPolicyChains route) {
        List<Site> parameterSites = parameterSites(parameter, index, methodSites(method));
        String describe = "parameter " + index + " of method " + methodSiteName(method);

        List<TypeMirror> canonicalizers = InvocationPolicyResolver.resolveParameterChain(
                source(parameterSites, describe, CANONICALIZE_FQN, SKIP_CANONICALIZATION_FQN),
                route.canonicalizers(),
                PolicyAxis.CANONICALIZE);
        List<TypeMirror> sanitizers = InvocationPolicyResolver.resolveParameterChain(
                source(parameterSites, describe, SANITIZE_FQN, SKIP_SANITIZATION_FQN),
                route.sanitizers(),
                PolicyAxis.SANITIZE);

        return new ElementPolicyChains(canonicalizers, sanitizers);
    }

    /**
     * The canonicalizer and sanitizer chains resolved for one element, as type mirrors in
     * declaration order.
     *
     * @param canonicalizers the resolved canonicalizer chain; empty when the axis resolved to none
     * @param sanitizers     the resolved sanitizer chain; empty when the axis resolved to none
     */
    public record ElementPolicyChains(List<TypeMirror> canonicalizers, List<TypeMirror> sanitizers) {

        /** Both axes resolved to an empty chain. */
        public static final ElementPolicyChains NONE = new ElementPolicyChains(List.of(), List.of());
    }

    // --- Source construction ---

    /**
     * Builds one axis's view of one element from its ordered declaration sites: the additive
     * annotation and the skip annotation are each resolved independently by {@link #findInSites},
     * exactly as the runtime's merged annotation lists resolve them.
     *
     * @param sites       the element's declaration sites in traversal order
     * @param describe    the human-readable element description for diagnostics
     * @param additiveFqn the FQN of the axis's additive annotation
     * @param skipFqn     the FQN of the axis's skip annotation
     * @return the passive source view for that axis
     */
    private InvocationPolicySource<TypeMirror> source(
            List<Site> sites, String describe, String additiveFqn, String skipFqn) {
        Hit additive = findInSites(sites, additiveFqn);
        Hit skip = findInSites(sites, skipFqn);

        return new SourceRecord<>(
                additive == null ? Optional.empty() : Optional.of(readClassArrayAsMirrors(additive.mirror())),
                additive == null ? Optional.empty() : Optional.of(additive.declaredAt()),
                skip != null,
                skip == null ? Optional.empty() : Optional.of(skip.declaredAt()),
                describe);
    }

    /**
     * Resolves one annotation type over the element's ordered declaration sites the way
     * {@code AnnotationResolver.findMetaAnnotation(List, Class)} resolves it over the runtime's
     * flattened merged annotation list: a first pass over <em>all</em> sites looking for a directly
     * declared annotation, then — only if none exists anywhere in the merged view — a second pass
     * over all sites looking for a composed (meta-annotated) one. Within each pass the nearest site
     * wins, and the reported declaration site is the site the winning pass matched at.
     *
     * @param sites         the element's declaration sites in traversal order
     * @param annotationFqn the FQN of the annotation to resolve
     * @return the winning mirror and its declaration site, or {@code null} when no site carries it
     */
    private Hit findInSites(List<Site> sites, String annotationFqn) {
        for (Site site : sites) {
            AnnotationMirror direct = findDirectAnnotation(site.element(), annotationFqn);
            if (direct != null) {
                return new Hit(direct, site.name());
            }
        }
        for (Site site : sites) {
            AnnotationMirror composed = findMetaAnnotation(site.element(), annotationFqn);
            if (composed != null) {
                return new Hit(composed, site.name());
            }
        }
        return null;
    }

    /**
     * Reads the {@code value()} {@code Class[]} attribute of an additive annotation mirror as an
     * ordered list of type mirrors.
     *
     * @param mirror the additive annotation mirror
     * @return the declared chain in declaration order; never {@code null}
     */
    private List<TypeMirror> readClassArrayAsMirrors(AnnotationMirror mirror) {
        List<TypeMirror> values = new ArrayList<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                elements.getElementValuesWithDefaults(mirror).entrySet()) {
            if (!entry.getKey().getSimpleName().contentEquals(VALUE_ATTRIBUTE)) {
                continue;
            }
            addTypeMirrors(entry.getValue().getValue(), values);
        }
        return List.copyOf(values);
    }

    /**
     * Appends the type mirror(s) carried by a raw annotation value — either a single class literal
     * or the list of class literals of an array attribute — to {@code target}.
     *
     * @param rawValue the raw value of an {@link AnnotationValue}
     * @param target   the list to append to
     */
    private static void addTypeMirrors(Object rawValue, List<TypeMirror> target) {
        if (rawValue instanceof TypeMirror mirror) {
            target.add(mirror);
            return;
        }
        if (rawValue instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof AnnotationValue value && value.getValue() instanceof TypeMirror mirror) {
                    target.add(mirror);
                }
            }
        }
    }

    // --- Meta-annotation resolution ---

    /**
     * Finds an annotation declared <em>directly</em> on {@code element}, without descending into
     * composed annotations.
     *
     * @param element       the element to inspect
     * @param annotationFqn the FQN of the annotation to find
     * @return the matching mirror, or {@code null} when the annotation is not directly present
     */
    private static AnnotationMirror findDirectAnnotation(Element element, String annotationFqn) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (annotationFqn.equals(annotationFqn(mirror))) {
                return mirror;
            }
        }
        return null;
    }

    /**
     * Finds an annotation on {@code element}, descending into composed annotations.
     *
     * @param element       the element to inspect
     * @param annotationFqn the FQN of the annotation to find
     * @return the matching mirror, or {@code null} when the annotation is not present
     */
    private static AnnotationMirror findMetaAnnotation(Element element, String annotationFqn) {
        return findMetaRecursive(element, annotationFqn, new HashSet<>());
    }

    /**
     * Recursive helper for {@link #findMetaAnnotation}: scans the element's own annotations, then
     * each annotation type's own annotations, guarding against annotation cycles with
     * {@code visited} — a self-referential annotation terminates the walk silently. JDK
     * meta-annotations ({@code java.lang.annotation.*}) are skipped, matching the runtime's own
     * meta-annotation walk: they can never carry a policy and every annotation type declares them.
     *
     * @param element       the element or annotation type to inspect
     * @param annotationFqn the FQN of the annotation to find
     * @param visited       the FQNs of annotation types already descended into
     * @return the matching mirror, or {@code null}
     */
    private static AnnotationMirror findMetaRecursive(Element element, String annotationFqn, Set<String> visited) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotationType = mirror.getAnnotationType().asElement();
            if (annotationType == null) {
                continue;
            }
            String thisFqn = annotationFqn(mirror);
            if (thisFqn == null || thisFqn.startsWith(JDK_META_ANNOTATION_PREFIX)) {
                continue;
            }
            if (annotationFqn.equals(thisFqn)) {
                return mirror;
            }
            if (visited.add(thisFqn)) {
                AnnotationMirror deeper = findMetaRecursive(annotationType, annotationFqn, visited);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /**
     * The fully qualified name of an annotation mirror's type.
     *
     * @param mirror the annotation mirror
     * @return the annotation type's qualified name, its simple name when it is not a
     *     {@link TypeElement}, or {@code null} when the type does not resolve to an element
     */
    private static String annotationFqn(AnnotationMirror mirror) {
        Element annotationType = mirror.getAnnotationType().asElement();
        if (annotationType == null) {
            return null;
        }
        return annotationType instanceof TypeElement type
                ? type.getQualifiedName().toString()
                : annotationType.getSimpleName().toString();
    }

    // --- Declaration-site walks ---

    /**
     * Builds the ordered method declaration sites of the merged view: the method itself, then the
     * method it overrides in each superclass bottom-up, then in each transitively reachable
     * interface in breadth-first order. The walk is anchored at the method's own declaring type.
     *
     * @param method the method whose sites are collected
     * @return the ordered sites, starting with {@code method} itself; never empty
     */
    private List<Site> methodSites(ExecutableElement method) {
        List<Site> sites = new ArrayList<>();
        sites.add(new Site(method, methodSiteName(method)));

        if (!(method.getEnclosingElement() instanceof TypeElement anchor)) {
            return List.copyOf(sites);
        }
        for (TypeElement current = superClassOf(anchor); current != null; current = superClassOf(current)) {
            addOverriddenMethod(sites, current, method, anchor);
        }
        for (TypeElement iface : allInterfaces(anchor)) {
            addOverriddenMethod(sites, iface, method, anchor);
        }
        return List.copyOf(sites);
    }

    /**
     * Appends the method {@code candidateType} declares that {@code method} overrides, if any. A
     * cheap name-and-arity pre-filter narrows the candidates; {@link Elements#overrides} then makes
     * the decision, so a genuine override of a generic supertype declaration is recognised.
     *
     * @param sites         the site list to append to
     * @param candidateType the supertype being inspected
     * @param method        the overriding method
     * @param anchor        the type in which the override relation is evaluated
     */
    private void addOverriddenMethod(
            List<Site> sites, TypeElement candidateType, ExecutableElement method, TypeElement anchor) {
        for (Element enclosed : candidateType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !(enclosed instanceof ExecutableElement candidate)) {
                continue;
            }
            if (!candidate.getSimpleName().contentEquals(method.getSimpleName())
                    || candidate.getParameters().size()
                            != method.getParameters().size()) {
                continue;
            }
            if (elements.overrides(method, candidate, anchor)) {
                sites.add(new Site(candidate, methodSiteName(candidate)));
                return;
            }
        }
    }

    /**
     * Builds the ordered type declaration sites of the merged view: the owner type, then its
     * superclasses bottom-up, then its transitively reachable interfaces in breadth-first order.
     *
     * @param owner the resource type the route belongs to
     * @return the ordered sites, starting with {@code owner} itself; never empty
     */
    private List<Site> typeSites(TypeElement owner) {
        List<Site> sites = new ArrayList<>();
        sites.add(new Site(owner, owner.getSimpleName().toString()));
        for (TypeElement current = superClassOf(owner); current != null; current = superClassOf(current)) {
            sites.add(new Site(current, current.getSimpleName().toString()));
        }
        for (TypeElement iface : allInterfaces(owner)) {
            sites.add(new Site(iface, iface.getSimpleName().toString()));
        }
        return List.copyOf(sites);
    }

    /**
     * Builds the ordered parameter declaration sites of the merged view: the parameter at
     * {@code index} of every method site that declares that many parameters. Each site is named
     * after its declaring method, so a conflict message points at the method that declares the
     * annotation.
     *
     * @param parameter   the parameter of the most-derived method site
     * @param index       the zero-based parameter index
     * @param methodSites the merged method view, in traversal order
     * @return the ordered parameter sites, starting with {@code parameter}; never empty
     */
    private static List<Site> parameterSites(VariableElement parameter, int index, List<Site> methodSites) {
        List<Site> sites = new ArrayList<>();
        sites.add(new Site(parameter, methodSites.get(0).name()));
        for (int i = 1; i < methodSites.size(); i++) {
            Site methodSite = methodSites.get(i);
            List<? extends VariableElement> parameters = ((ExecutableElement) methodSite.element()).getParameters();
            if (index < parameters.size()) {
                sites.add(new Site(parameters.get(index), methodSite.name()));
            }
        }
        return List.copyOf(sites);
    }

    /**
     * Returns all interfaces {@code type} transitively implements, in breadth-first discovery order
     * and deduplicated by qualified name. The queue is seeded from the type and its whole
     * superclass chain, mirroring the runtime interface walk.
     *
     * @param type the type whose interfaces are collected
     * @return the ordered, deduplicated interfaces; never {@code null}
     */
    private List<TypeElement> allInterfaces(TypeElement type) {
        List<TypeElement> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();

        for (TypeElement current = type; current != null; current = superClassOf(current)) {
            enqueueDirectInterfaces(current, queue, seen);
        }
        while (!queue.isEmpty()) {
            TypeElement iface = queue.poll();
            result.add(iface);
            enqueueDirectInterfaces(iface, queue, seen);
        }
        return List.copyOf(result);
    }

    /**
     * Enqueues the directly declared interfaces of {@code type} that have not been seen yet.
     *
     * @param type  the type whose direct interfaces are enqueued
     * @param queue the breadth-first queue
     * @param seen  the qualified names already enqueued
     */
    private void enqueueDirectInterfaces(TypeElement type, Deque<TypeElement> queue, Set<String> seen) {
        for (TypeMirror ifaceMirror : type.getInterfaces()) {
            if (types.asElement(ifaceMirror) instanceof TypeElement iface
                    && seen.add(iface.getQualifiedName().toString())) {
                queue.add(iface);
            }
        }
    }

    /**
     * Returns the superclass of {@code type}, or {@code null} when the walk must stop — no
     * superclass, an unresolvable one, or {@code java.lang.Object}.
     *
     * @param type the type whose superclass is looked up
     * @return the superclass element, or {@code null}
     */
    private TypeElement superClassOf(TypeElement type) {
        TypeMirror superMirror = type.getSuperclass();
        if (superMirror == null || superMirror.getKind() != TypeKind.DECLARED) {
            return null;
        }
        if (!(types.asElement(superMirror) instanceof TypeElement superElement)) {
            return null;
        }
        return OBJECT_FQN.contentEquals(superElement.getQualifiedName()) ? null : superElement;
    }

    /**
     * The declaration-site name of a method site, e.g. {@code "IFoo.bar"}.
     *
     * @param method the method site
     * @return the declaring type's simple name, a dot, and the method's simple name
     */
    private static String methodSiteName(ExecutableElement method) {
        return method.getEnclosingElement().getSimpleName() + "." + method.getSimpleName();
    }

    /** One declaration site: the element carrying the annotations, and its diagnostic name. */
    private record Site(Element element, String name) {}

    /**
     * One resolved annotation lookup: the winning mirror and the diagnostic name of the site it was
     * matched at.
     */
    private record Hit(AnnotationMirror mirror, String declaredAt) {}

    /** Passive {@link InvocationPolicySource} carrier built from a resolved annotation lookup. */
    private record SourceRecord<V>(
            Optional<List<V>> additive,
            Optional<String> additiveDeclaredAt,
            boolean skip,
            Optional<String> skipDeclaredAt,
            String describe)
            implements InvocationPolicySource<V> {}
}
