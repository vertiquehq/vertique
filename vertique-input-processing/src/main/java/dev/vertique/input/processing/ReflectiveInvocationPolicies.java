// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.util.TypeResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reflective {@link InvocationPolicySource} adapter: resolves route- and parameter-level
 * canonicalization/sanitization chains from real annotated methods and classes, through
 * {@link AnnotationResolver} only.
 *
 * <p>Both {@link #resolveRoute} and {@link #resolveParameter} resolve each {@link PolicyAxis}
 * independently and combine the two into a single {@link EffectiveInputPolicies}. Chain values and
 * skip presence are resolved from {@link AnnotationResolver}'s hierarchy-merged annotation lists
 * (matching the reflective reference semantics in {@code ResourceScanner}); declaration-site
 * provenance — which {@link AnnotationResolver}'s merged lists do not carry — is recovered by
 * re-walking the same documented traversal order (declaring element, then superclasses bottom-up,
 * then interfaces) one site at a time until the first site carrying that polarity's annotation is
 * found.
 */
public final class ReflectiveInvocationPolicies {

    private ReflectiveInvocationPolicies() {}

    /**
     * Resolves the route-level policies for {@code method} declared on {@code owner}.
     *
     * @param method the method whose route-level policies are resolved
     * @param owner  the class the route belongs to (its type-level annotations are consulted)
     * @return the resolved route-level policies
     * @throws InvocationPolicyConflictException when the method or the type declares both additive
     *     and skip on the same axis
     */
    public static EffectiveInputPolicies resolveRoute(Method method, Class<?> owner) {
        InvocationPolicySource<Class<? extends Canonicalizer>> methodCanon =
                methodSource(method, owner, Canonicalize.class, Canonicalize::value, SkipCanonicalization.class);
        InvocationPolicySource<Class<? extends Canonicalizer>> typeCanon =
                typeSource(owner, Canonicalize.class, Canonicalize::value, SkipCanonicalization.class);
        List<Class<? extends Canonicalizer>> canonicalizers =
                InvocationPolicyResolver.resolveRouteChain(methodCanon, typeCanon, PolicyAxis.CANONICALIZE);

        InvocationPolicySource<Class<? extends Sanitizer>> methodSani =
                methodSource(method, owner, Sanitize.class, Sanitize::value, SkipSanitization.class);
        InvocationPolicySource<Class<? extends Sanitizer>> typeSani =
                typeSource(owner, Sanitize.class, Sanitize::value, SkipSanitization.class);
        List<Class<? extends Sanitizer>> sanitizers =
                InvocationPolicyResolver.resolveRouteChain(methodSani, typeSani, PolicyAxis.SANITIZE);

        return new EffectiveInputPolicies(canonicalizers, sanitizers);
    }

    /**
     * Resolves the parameter-level policies for parameter {@code index} of {@code method}, over the
     * already-resolved route policies.
     *
     * @param method the method the parameter belongs to
     * @param index  the zero-based parameter index
     * @param route  the route-level policies resolved by {@link #resolveRoute}
     * @return the resolved parameter-level policies
     * @throws InvocationPolicyConflictException when the parameter declares both additive and skip
     *     on the same axis
     */
    public static EffectiveInputPolicies resolveParameter(Method method, int index, EffectiveInputPolicies route) {
        InvocationPolicySource<Class<? extends Canonicalizer>> paramCanon =
                paramSource(method, index, Canonicalize.class, Canonicalize::value, SkipCanonicalization.class);
        List<Class<? extends Canonicalizer>> canonicalizers = InvocationPolicyResolver.resolveParameterChain(
                paramCanon, route.canonicalizers(), PolicyAxis.CANONICALIZE);

        InvocationPolicySource<Class<? extends Sanitizer>> paramSani =
                paramSource(method, index, Sanitize.class, Sanitize::value, SkipSanitization.class);
        List<Class<? extends Sanitizer>> sanitizers =
                InvocationPolicyResolver.resolveParameterChain(paramSani, route.sanitizers(), PolicyAxis.SANITIZE);

        return new EffectiveInputPolicies(canonicalizers, sanitizers);
    }

    // --- Source construction ---

    private static <A extends Annotation, S extends Annotation, V> InvocationPolicySource<V> methodSource(
            Method method, Class<?> owner, Class<A> additiveType, Function<A, V[]> valueOf, Class<S> skipType) {
        List<Annotation> merged = AnnotationResolver.resolveMethodAnnotations(method);
        A additiveAnn = AnnotationResolver.findMetaAnnotation(merged, additiveType);
        S skipAnn = AnnotationResolver.findMetaAnnotation(merged, skipType);

        List<Method> sites = methodSites(method);
        String describe = "method " + owner.getSimpleName() + "." + method.getName();

        Optional<List<V>> additive =
                additiveAnn == null ? Optional.empty() : Optional.of(List.of(valueOf.apply(additiveAnn)));
        Optional<String> additiveAt =
                additiveAnn == null ? Optional.empty() : Optional.of(methodSiteName(sites, additiveType));
        boolean skip = skipAnn != null;
        Optional<String> skipAt = skip ? Optional.of(methodSiteName(sites, skipType)) : Optional.empty();

        return new SourceRecord<>(additive, additiveAt, skip, skipAt, describe);
    }

    private static <A extends Annotation, S extends Annotation, V> InvocationPolicySource<V> typeSource(
            Class<?> owner, Class<A> additiveType, Function<A, V[]> valueOf, Class<S> skipType) {
        List<Annotation> merged = AnnotationResolver.resolveClassAnnotations(owner);
        A additiveAnn = AnnotationResolver.findMetaAnnotation(merged, additiveType);
        S skipAnn = AnnotationResolver.findMetaAnnotation(merged, skipType);

        List<Class<?>> sites = typeSites(owner);
        String describe = "type " + owner.getSimpleName();

        Optional<List<V>> additive =
                additiveAnn == null ? Optional.empty() : Optional.of(List.of(valueOf.apply(additiveAnn)));
        Optional<String> additiveAt =
                additiveAnn == null ? Optional.empty() : Optional.of(typeSiteName(sites, additiveType));
        boolean skip = skipAnn != null;
        Optional<String> skipAt = skip ? Optional.of(typeSiteName(sites, skipType)) : Optional.empty();

        return new SourceRecord<>(additive, additiveAt, skip, skipAt, describe);
    }

    private static <A extends Annotation, S extends Annotation, V> InvocationPolicySource<V> paramSource(
            Method method, int index, Class<A> additiveType, Function<A, V[]> valueOf, Class<S> skipType) {
        List<Annotation> merged = List.of(AnnotationResolver.resolveParameterAnnotations(method, index));
        A additiveAnn = AnnotationResolver.findMetaAnnotation(merged, additiveType);
        S skipAnn = AnnotationResolver.findMetaAnnotation(merged, skipType);

        List<Method> methodSites = methodSites(method);
        List<Parameter> paramSites = paramSites(methodSites, index);
        String describe = "parameter " + index + " of method "
                + method.getDeclaringClass().getSimpleName() + "." + method.getName();

        Optional<List<V>> additive =
                additiveAnn == null ? Optional.empty() : Optional.of(List.of(valueOf.apply(additiveAnn)));
        Optional<String> additiveAt = additiveAnn == null
                ? Optional.empty()
                : Optional.of(paramSiteName(methodSites, paramSites, additiveType));
        boolean skip = skipAnn != null;
        Optional<String> skipAt =
                skip ? Optional.of(paramSiteName(methodSites, paramSites, skipType)) : Optional.empty();

        return new SourceRecord<>(additive, additiveAt, skip, skipAt, describe);
    }

    // --- Declaration-site provenance walk ---
    // Mirrors AnnotationResolver's documented traversal order (declaring element, superclasses
    // bottom-up, interfaces) one site at a time, since the merged annotation lists it returns carry
    // no origin.

    private static List<Method> methodSites(Method method) {
        List<Method> sites = new ArrayList<>();
        sites.add(method);
        Class<?> current = method.getDeclaringClass().getSuperclass();
        while (current != null && current != Object.class) {
            addMatchingMethod(sites, current, method);
            current = current.getSuperclass();
        }
        for (Class<?> iface : TypeResolver.getAllInterfaces(method.getDeclaringClass())) {
            addMatchingMethod(sites, iface, method);
        }
        return sites;
    }

    private static void addMatchingMethod(List<Method> sites, Class<?> candidate, Method method) {
        try {
            sites.add(candidate.getMethod(method.getName(), method.getParameterTypes()));
        } catch (NoSuchMethodException ignored) {
            // Method not declared on this level — continue the walk.
        }
    }

    private static List<Class<?>> typeSites(Class<?> owner) {
        List<Class<?>> sites = new ArrayList<>();
        sites.add(owner);
        Class<?> current = owner.getSuperclass();
        while (current != null && current != Object.class) {
            sites.add(current);
            current = current.getSuperclass();
        }
        sites.addAll(TypeResolver.getAllInterfaces(owner));
        return sites;
    }

    private static List<Parameter> paramSites(List<Method> methodSites, int index) {
        List<Parameter> sites = new ArrayList<>(methodSites.size());
        for (Method site : methodSites) {
            sites.add(site.getParameters()[index]);
        }
        return sites;
    }

    private static String methodSiteName(Method site) {
        return site.getDeclaringClass().getSimpleName() + "." + site.getName();
    }

    /**
     * Index of the site the value was resolved from, in the same order the value lookup uses:
     * {@link AnnotationResolver#findMetaAnnotation(List, Class)} takes a directly declared annotation
     * anywhere in the merged view before descending into composed annotations, so the declaration
     * site must follow the same two passes — nearest direct declaration first, nearest composed
     * declaration only when no site declares the annotation directly.
     */
    private static <A extends Annotation> int siteIndex(List<? extends AnnotatedElement> sites, Class<A> type) {
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).getAnnotation(type) != null) {
                return i;
            }
        }
        for (int i = 0; i < sites.size(); i++) {
            if (AnnotationResolver.findMetaAnnotation(sites.get(i), type) != null) {
                return i;
            }
        }
        throw new IllegalStateException("no declaration site found for " + type.getSimpleName());
    }

    private static <A extends Annotation> String methodSiteName(List<Method> sites, Class<A> type) {
        return methodSiteName(sites.get(siteIndex(sites, type)));
    }

    private static <A extends Annotation> String typeSiteName(List<Class<?>> sites, Class<A> type) {
        return sites.get(siteIndex(sites, type)).getSimpleName();
    }

    private static <A extends Annotation> String paramSiteName(
            List<Method> methodSites, List<Parameter> paramSites, Class<A> type) {
        return methodSiteName(methodSites.get(siteIndex(paramSites, type)));
    }

    /** Passive {@link InvocationPolicySource} carrier built from a resolved annotation lookup. */
    private record SourceRecord<V>(
            Optional<List<V>> additive,
            Optional<String> additiveDeclaredAt,
            boolean skip,
            Optional<String> skipDeclaredAt,
            String describe)
            implements InvocationPolicySource<V> {}
}
