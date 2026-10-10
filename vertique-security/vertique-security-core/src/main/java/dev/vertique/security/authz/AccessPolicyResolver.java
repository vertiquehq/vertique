// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * INTERNAL cross-module utility. Resolves a typed {@link AccessPolicy} into the direct runtime
 * security annotations it declares. No policy instances and no decision cache.
 *
 * <p>An application uses {@link AccessPolicy} and {@link RequiresPolicy}. Framework modules call
 * this type. It is not an application extension point.
 */
public final class AccessPolicyResolver {

    private static final String SCHEME_PACKAGE = "io.swagger.v3.oas.annotations.security.";

    private AccessPolicyResolver() {}

    /**
     * Returns the direct runtime requirements declared on a valid policy.
     *
     * @param policy the policy interface
     * @return immutable direct requirements, never a {@link RequiresPolicy}
     * @throws IllegalArgumentException when the type is not a valid policy or its requirements are
     *     rejected
     */
    public static List<Annotation> resolve(Class<? extends AccessPolicy> policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        validateShape(policy);
        List<Annotation> direct = new ArrayList<>();
        for (Annotation annotation : policy.getDeclaredAnnotations()) {
            if (isProhibitedOnPolicy(annotation)
                    || isRuntimeVisibleComposition(annotation.annotationType(), new LinkedHashSet<>())) {
                throw new IllegalArgumentException("unsupported security composition on " + policy.getName() + ": "
                        + annotation.annotationType().getName());
            }
            if (isDirectRequirement(annotation)) {
                direct.add(annotation);
            }
        }
        if (direct.isEmpty()) {
            throw new IllegalArgumentException("policy declares no direct requirement: " + policy.getName());
        }
        validateRequirements(policy.getName(), direct);
        return List.copyOf(direct);
    }

    /**
     * Selects the single policy reference from already-collected annotation sets.
     *
     * <p>Both sets are validated. A method reference replaces the type reference. Identical
     * references coalesce. Distinct references, an invalid policy, or a mix of a policy reference
     * and an inline security annotation reject.
     *
     * @param methodAnnotations complete method-set annotations
     * @param typeAnnotations complete type-set annotations
     * @return the selected policy, or empty when neither set references one
     */
    public static Optional<Class<? extends AccessPolicy>> select(
            List<Annotation> methodAnnotations, List<Annotation> typeAnnotations) {
        List<Annotation> methods = methodAnnotations == null ? List.of() : methodAnnotations;
        List<Annotation> types = typeAnnotations == null ? List.of() : typeAnnotations;
        Set<Class<? extends AccessPolicy>> methodPolicies = policyReferences(methods);
        Set<Class<? extends AccessPolicy>> typePolicies = policyReferences(types);
        boolean inline = hasInlineSecurity(methods) || hasInlineSecurity(types);
        if (inline && (!methodPolicies.isEmpty() || !typePolicies.isEmpty())) {
            throw new IllegalArgumentException("RequiresPolicy is mixed with an inline security annotation");
        }
        if (methodPolicies.size() > 1) {
            throw new IllegalArgumentException("distinct RequiresPolicy references in the method set");
        }
        if (typePolicies.size() > 1) {
            throw new IllegalArgumentException("distinct RequiresPolicy references in the type set");
        }
        for (Class<? extends AccessPolicy> policy : methodPolicies) {
            resolve(policy);
        }
        for (Class<? extends AccessPolicy> policy : typePolicies) {
            resolve(policy);
        }
        if (!methodPolicies.isEmpty()) {
            return Optional.of(methodPolicies.iterator().next());
        }
        if (!typePolicies.isEmpty()) {
            return Optional.of(typePolicies.iterator().next());
        }
        return Optional.empty();
    }

    /**
     * Collects the consumer's corresponding method and type declarations.
     *
     * <p>When neither complete set contains {@link RequiresPolicy}, the supplied legacy list is
     * returned unchanged. Otherwise both sets are validated and the result is the legacy
     * non-security annotations followed by the selected set's {@link RequiresPolicy} annotations.
     *
     * @param consumerType the runtime resource type collection starts from
     * @param method a method on that consumer; bridges canonicalize to the non-bridge operation
     * @param legacyMethodAnnotations annotations the caller already resolved for the inline path
     * @return the legacy list, or legacy non-security annotations plus the selected policy annotations
     */
    public static List<Annotation> collectMethodAnnotations(
            Class<?> consumerType, Method method, List<Annotation> legacyMethodAnnotations) {
        if (consumerType == null || method == null) {
            throw new IllegalArgumentException("consumer and method are required");
        }
        List<Annotation> legacy = legacyMethodAnnotations == null ? List.of() : legacyMethodAnnotations;
        Method canonical = canonicalize(method);
        Map<TypeVariable<?>, Type> bindings = new LinkedHashMap<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        walkHierarchy(consumerType, hierarchy, bindings, new LinkedHashSet<>());

        List<Annotation> methodSet = new ArrayList<>();
        List<Annotation> typeSet = new ArrayList<>();
        // Declarations on methods that correspond but are excluded on purpose (static, private,
        // or package-private in a foreign package). Legacy erased lookup can still see some of
        // them; they are tolerated, never selected.
        List<Annotation> excluded = new ArrayList<>();
        for (Class<?> type : hierarchy) {
            for (Annotation annotation : type.getDeclaredAnnotations()) {
                if (isSecurityDeclaration(annotation)) {
                    typeSet.add(annotation);
                }
            }
            for (Method candidate : type.getDeclaredMethods()) {
                // The scanned method always corresponds to itself, whatever its modifiers: it is
                // the operation being secured, and dropping it would let a broader declaration
                // replace its own.
                boolean self = candidate.equals(canonical);
                if (!self && !corresponds(canonical, candidate, bindings)) {
                    continue;
                }
                if (!self && !includeMethod(candidate, consumerType)) {
                    for (Annotation annotation : candidate.getDeclaredAnnotations()) {
                        if (isSecurityDeclaration(annotation)) {
                            excluded.add(annotation);
                        }
                    }
                    continue;
                }
                for (Annotation annotation : candidate.getDeclaredAnnotations()) {
                    if (isSecurityDeclaration(annotation)) {
                        methodSet.add(annotation);
                    }
                }
            }
        }
        boolean methodHasPolicy = !policyReferences(methodSet).isEmpty();
        if (!methodHasPolicy && policyReferences(typeSet).isEmpty()) {
            return legacy;
        }
        for (Annotation annotation : legacy) {
            if (isSecurityDeclaration(annotation)
                    && !methodSet.contains(annotation)
                    && !typeSet.contains(annotation)
                    && !excluded.contains(annotation)) {
                throw new IllegalArgumentException("security annotation @"
                        + annotation.annotationType().getSimpleName()
                        + " on " + method + " is not reachable from " + consumerType.getName()
                        + "; refusing to replace it with a broader declaration");
            }
        }
        Optional<Class<? extends AccessPolicy>> selected = select(methodSet, typeSet);
        List<Annotation> security = new ArrayList<>();
        if (selected.isPresent()) {
            Class<? extends AccessPolicy> policy = selected.get();
            List<Annotation> winning = methodHasPolicy ? methodSet : typeSet;
            for (Annotation annotation : winning) {
                if (annotation.annotationType() == RequiresPolicy.class
                        && ((RequiresPolicy) annotation).value() == policy
                        && security.stream().noneMatch(existing -> samePolicy(existing, policy))) {
                    security.add(annotation);
                }
            }
        }
        List<Annotation> result = new ArrayList<>();
        for (Annotation annotation : legacy) {
            if (!isSecurityDeclaration(annotation)) {
                result.add(annotation);
            }
        }
        result.addAll(security);
        return List.copyOf(result);
    }

    private static void validateShape(Class<?> policy) {
        if (!policy.isInterface() || !Modifier.isPublic(policy.getModifiers()) || policy.isAnnotation()) {
            throw new IllegalArgumentException("policy must be a public interface: " + policy.getName());
        }
        if (policy.getTypeParameters().length != 0) {
            throw new IllegalArgumentException("policy must not be generic: " + policy.getName());
        }
        Class<?>[] supers = policy.getInterfaces();
        if (supers.length != 1 || supers[0] != AccessPolicy.class) {
            throw new IllegalArgumentException("policy must extend only AccessPolicy: " + policy.getName());
        }
        if (policy.getDeclaredMethods().length != 0
                || policy.getDeclaredFields().length != 0
                || policy.getDeclaredClasses().length != 0) {
            throw new IllegalArgumentException(
                    "policy must not declare fields, methods, or nested types: " + policy.getName());
        }
    }

    private static void validateRequirements(String policyName, List<Annotation> direct) {
        boolean permit = false;
        boolean deny = false;
        int others = 0;
        for (Annotation annotation : direct) {
            if (annotation.annotationType() == PermitAll.class) {
                permit = true;
            } else if (annotation.annotationType() == DenyAll.class) {
                deny = true;
            } else {
                others++;
                validateValue(policyName, annotation);
            }
        }
        if (permit && deny || (permit || deny) && others > 0) {
            throw new IllegalArgumentException("PermitAll and DenyAll are exclusive on " + policyName);
        }
    }

    private static void validateValue(String policyName, Annotation annotation) {
        if (annotation instanceof RolesAllowed roles) {
            String[] values = roles.value();
            if (values.length == 0) {
                throw new IllegalArgumentException("RolesAllowed is empty on " + policyName);
            }
            for (String role : values) {
                if (role == null || role.isBlank()) {
                    throw new IllegalArgumentException("RolesAllowed value is blank on " + policyName);
                }
            }
        } else if (annotation instanceof Authorized authorized) {
            for (String scope : authorized.scopes()) {
                if (scope == null || scope.isBlank()) {
                    throw new IllegalArgumentException("Authorized scope is blank on " + policyName);
                }
            }
        } else if (annotation instanceof RequiresAction action) {
            ActionRef.parse(action.value());
        }
    }

    private static boolean isDirectRequirement(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        return type == PermitAll.class
                || type == DenyAll.class
                || type == RolesAllowed.class
                || type == Authorized.class
                || type == RequiresAction.class;
    }

    private static boolean isSecurityDeclaration(Annotation annotation) {
        return annotation.annotationType() == RequiresPolicy.class || isDirectRequirement(annotation);
    }

    private static boolean isProhibitedOnPolicy(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        if (type == RequiresPolicy.class) {
            return true;
        }
        String name = type.getName();
        return name.startsWith(SCHEME_PACKAGE);
    }

    private static boolean isRuntimeVisibleComposition(Class<? extends Annotation> annotationType, Set<Class<?>> seen) {
        if (!seen.add(annotationType)) {
            return false;
        }
        if (annotationType.getPackageName().equals("java.lang.annotation")) {
            return false;
        }
        for (Annotation meta : annotationType.getDeclaredAnnotations()) {
            if (meta.annotationType().getPackageName().equals("java.lang.annotation")) {
                continue;
            }
            if (isDirectRequirement(meta) || isProhibitedOnPolicy(meta)) {
                return true;
            }
            if (isRuntimeVisibleComposition(meta.annotationType(), seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInlineSecurity(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (isDirectRequirement(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Class<? extends AccessPolicy>> policyReferences(List<Annotation> annotations) {
        Set<Class<? extends AccessPolicy>> policies = new LinkedHashSet<>();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == RequiresPolicy.class) {
                policies.add(((RequiresPolicy) annotation).value());
            }
        }
        return policies;
    }

    private static boolean samePolicy(Annotation annotation, Class<? extends AccessPolicy> policy) {
        return annotation.annotationType() == RequiresPolicy.class && ((RequiresPolicy) annotation).value() == policy;
    }

    /**
     * Resolves a bridge or synthetic method to the operation it forwards to; any other method
     * resolves to itself.
     *
     * <p>The exact parameter search runs first, up the whole superclass chain. It is the only match
     * that cannot be a different operation: a compiler visibility bridge, for example, shares its
     * parameter types with the inherited method it forwards to, while a narrower overload declared
     * on the bridge's class is a different operation that must never replace it. Only when no class
     * in the chain declares an exact match does the search accept the assignable instance method a
     * generic bridge erases to (see {@link #uniqueAssignable}). Both searches apply the same
     * eligibility rule (see {@link #canBeBridgeTarget}): static, private, bridge and synthetic
     * candidates never qualify.
     *
     * @param method the method being collected
     * @return the non-bridge operation
     * @throws IllegalArgumentException when no operation, or more than one, matches the bridge
     */
    private static Method canonicalize(Method method) {
        if (!method.isBridge() && !method.isSynthetic()) {
            return method;
        }
        // The compiler puts the bridge on the class that implements the interface. The concrete
        // method can be declared on a superclass, so the search continues up that chain.
        for (Class<?> type = method.getDeclaringClass();
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            Method exact = uniqueExact(type, method);
            if (exact != null) {
                return exact;
            }
        }
        return uniqueAssignable(method);
    }

    /**
     * Finds the single eligible method on {@code type} whose name and parameter types equal the
     * bridge's. A static, private, bridge or synthetic method is never a candidate.
     *
     * @param type the class whose declared methods are searched
     * @param bridge the bridge or synthetic method being resolved
     * @return the match, or {@code null} when the class declares none
     * @throws IllegalArgumentException when several methods match
     */
    private static Method uniqueExact(Class<?> type, Method bridge) {
        Method unique = null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (!canBeBridgeTarget(candidate)
                    || candidate == bridge
                    || !candidate.getName().equals(bridge.getName())
                    || !Arrays.equals(candidate.getParameterTypes(), bridge.getParameterTypes())) {
                continue;
            }
            if (unique != null) {
                throw new IllegalArgumentException("no unique non-bridge operation for " + bridge);
            }
            unique = candidate;
        }
        return unique;
    }

    /**
     * Finds the one operation a generic bridge forwards to when no class declares its exact
     * parameter types: a generic bridge erases to the bound, so the concrete method takes narrower
     * types. Candidates are collected across the whole superclass chain, declaring class first, and
     * an override repeats its overridden method's signature without adding a candidate.
     *
     * @param bridge the bridge or synthetic method being resolved
     * @return the first declaration of the single matching signature
     * @throws IllegalArgumentException when no signature, or more than one, matches
     */
    private static Method uniqueAssignable(Method bridge) {
        Method unique = null;
        for (Class<?> type = bridge.getDeclaringClass();
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (!forwardsTo(bridge, candidate)) {
                    continue;
                }
                if (unique == null) {
                    unique = candidate;
                } else if (!Arrays.equals(unique.getParameterTypes(), candidate.getParameterTypes())) {
                    throw new IllegalArgumentException("no unique non-bridge operation for " + bridge);
                }
            }
        }
        if (unique == null) {
            throw new IllegalArgumentException("no unique non-bridge operation for " + bridge);
        }
        return unique;
    }

    /**
     * Tells whether {@code candidate} can be the target of {@code bridge}: an eligible, same-named,
     * same-arity method that takes parameters assignable to the bridge's erased parameters and
     * returns a type assignable to the bridge's return.
     */
    private static boolean forwardsTo(Method bridge, Method candidate) {
        if (!canBeBridgeTarget(candidate) || !candidate.getName().equals(bridge.getName())) {
            return false;
        }
        Class<?>[] erased = bridge.getParameterTypes();
        Class<?>[] narrowed = candidate.getParameterTypes();
        if (erased.length != narrowed.length || !bridge.getReturnType().isAssignableFrom(candidate.getReturnType())) {
            return false;
        }
        for (int i = 0; i < erased.length; i++) {
            if (!erased[i].isAssignableFrom(narrowed[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The one eligibility rule for the operation a bridge forwards to, shared by the exact and the
     * assignable search: an instance method that is not private, bridge or synthetic.
     */
    private static boolean canBeBridgeTarget(Method candidate) {
        int modifiers = candidate.getModifiers();
        return !Modifier.isStatic(modifiers)
                && !Modifier.isPrivate(modifiers)
                && !candidate.isBridge()
                && !candidate.isSynthetic();
    }

    private static boolean includeMethod(Method candidate, Class<?> consumer) {
        int modifiers = candidate.getModifiers();
        if (Modifier.isStatic(modifiers)
                || Modifier.isPrivate(modifiers)
                || candidate.isBridge()
                || candidate.isSynthetic()) {
            return false;
        }
        if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
            return candidate.getDeclaringClass().getPackageName().equals(consumer.getPackageName());
        }
        return true;
    }

    private static boolean corresponds(Method canonical, Method candidate, Map<TypeVariable<?>, Type> bindings) {
        if (canonical.getName().equals(candidate.getName())
                && canonical.getParameterCount() == candidate.getParameterCount()) {
            Type[] generic = candidate.getGenericParameterTypes();
            Type[] wanted = canonical.getGenericParameterTypes();
            if (generic.length != wanted.length) {
                return false;
            }
            // A method type parameter corresponds by position, and only when both methods declare
            // the same number of them: a method with its own type parameter overrides nothing.
            TypeVariable<Method>[] own = canonical.getTypeParameters();
            TypeVariable<Method>[] other = candidate.getTypeParameters();
            if (own.length != other.length) {
                return false;
            }
            Map<TypeVariable<?>, Type> effective = bindings;
            if (other.length > 0) {
                effective = new LinkedHashMap<>(bindings);
                for (int i = 0; i < other.length; i++) {
                    effective.put(other[i], own[i]);
                }
            }
            for (int i = 0; i < generic.length; i++) {
                Class<?> resolved = resolveErasure(generic[i], effective, new IdentityHashMap<>());
                Class<?> expected = resolveErasure(wanted[i], effective, new IdentityHashMap<>());
                if (resolved != expected) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Class<?> resolveErasure(
            Type type, Map<TypeVariable<?>, Type> bindings, IdentityHashMap<Type, Boolean> seen) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (!seen.containsKey(type) && type instanceof TypeVariable<?> variable) {
            seen.put(type, Boolean.TRUE);
            Type bound = bindings.get(variable);
            if (bound != null) {
                return resolveErasure(bound, bindings, seen);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : resolveErasure(bounds[0], bindings, seen);
        }
        if (type instanceof ParameterizedType parameterized) {
            return resolveErasure(parameterized.getRawType(), bindings, seen);
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = resolveErasure(array.getGenericComponentType(), bindings, seen);
            return Array.newInstance(component, 0).getClass();
        }
        return Object.class;
    }

    private static void walkHierarchy(
            Class<?> type, List<Class<?>> hierarchy, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        if (type == null || type == Object.class || !seen.add(type)) {
            return;
        }
        hierarchy.add(type);
        Type superclass = type.getGenericSuperclass();
        if (superclass instanceof ParameterizedType parameterized) {
            bind(parameterized, bindings);
            walkHierarchy((Class<?>) parameterized.getRawType(), hierarchy, bindings, seen);
        } else if (superclass instanceof Class<?> raw) {
            walkHierarchy(raw, hierarchy, bindings, seen);
        }
        for (Type genericInterface : type.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType parameterized) {
                bind(parameterized, bindings);
                walkHierarchy((Class<?>) parameterized.getRawType(), hierarchy, bindings, seen);
            } else if (genericInterface instanceof Class<?> raw) {
                walkHierarchy(raw, hierarchy, bindings, seen);
            }
        }
    }

    private static void bind(ParameterizedType parameterized, Map<TypeVariable<?>, Type> bindings) {
        if (!(parameterized.getRawType() instanceof Class<?> raw)) {
            return;
        }
        TypeVariable<?>[] parameters = raw.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            bindings.putIfAbsent(parameters[i], arguments[i]);
        }
        // The argument of an owner type ({@code Outer<String>.Inner}) binds the inner class's
        // variables from the enclosing class.
        if (parameterized.getOwnerType() instanceof ParameterizedType owner) {
            bind(owner, bindings);
        }
    }
}
