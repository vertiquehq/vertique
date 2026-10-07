// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.security;

import dev.vertique.codegen.JaxRsAnnotations;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Compiler-mirror view of typed access-policy resolution.
 *
 * <p>INTERNAL codegen utility. Names are string FQNs so this module does not depend on
 * security-core. CLASS-retained composition stays visible here; SOURCE-retained composition that
 * was erased before this compilation does not.
 */
public final class AccessPolicyAnnotationResolver {

    static final String ACCESS_POLICY = "dev.vertique.security.authz.AccessPolicy";
    static final String REQUIRES_POLICY = "dev.vertique.security.authz.RequiresPolicy";
    private static final String SCHEME_PACKAGE = "io.swagger.v3.oas.annotations.security.";
    /** Mirrors {@code ActionRef}: three dot-separated segments of {@code [a-z][a-z0-9]*}. */
    private static final Pattern ACTION_SEGMENTS = Pattern.compile("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*\\.[a-z][a-z0-9]*");

    private final Types types;
    private final Elements elements;

    public AccessPolicyAnnotationResolver(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /**
     * Direct requirement mirrors declared on a valid policy.
     *
     * @param policy the policy type
     * @return immutable direct requirements
     * @throws IllegalArgumentException when the type or its visible requirements are rejected
     */
    public List<AnnotationMirror> resolve(TypeElement policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        validateShape(policy);
        List<AnnotationMirror> direct = new ArrayList<>();
        for (AnnotationMirror mirror : policy.getAnnotationMirrors()) {
            String name = qualifiedName(mirror);
            if (isProhibited(name) || isVisibleComposition(mirror, new LinkedHashSet<>())) {
                throw new IllegalArgumentException(
                        "unsupported security composition on " + policy.getQualifiedName() + ": " + name);
            }
            if (isDirect(name)) {
                validateValue(policy.getQualifiedName().toString(), mirror, name);
                direct.add(mirror);
            }
        }
        if (direct.isEmpty()) {
            throw new IllegalArgumentException("policy declares no direct requirement: " + policy.getQualifiedName());
        }
        validateExclusive(policy.getQualifiedName().toString(), direct);
        return List.copyOf(direct);
    }

    /**
     * Selects one policy FQN from complete method and type mirror lists.
     *
     * @param methodAnnotations complete method-set mirrors
     * @param typeAnnotations complete type-set mirrors
     * @return the selected policy name, or empty when neither set references one
     */
    public Optional<String> select(
            List<? extends AnnotationMirror> methodAnnotations, List<? extends AnnotationMirror> typeAnnotations) {
        List<? extends AnnotationMirror> methods = methodAnnotations == null ? List.of() : methodAnnotations;
        List<? extends AnnotationMirror> typeMirrors = typeAnnotations == null ? List.of() : typeAnnotations;
        Set<String> methodPolicies = policyNames(methods);
        Set<String> typePolicies = policyNames(typeMirrors);
        boolean inline = hasDirect(methods) || hasDirect(typeMirrors);
        if (inline && (!methodPolicies.isEmpty() || !typePolicies.isEmpty())) {
            throw new IllegalArgumentException("RequiresPolicy is mixed with an inline security annotation");
        }
        if (methodPolicies.size() > 1) {
            throw new IllegalArgumentException("distinct RequiresPolicy references in the method set");
        }
        if (typePolicies.size() > 1) {
            throw new IllegalArgumentException("distinct RequiresPolicy references in the type set");
        }
        for (String policy : methodPolicies) {
            resolveNamed(policy);
        }
        for (String policy : typePolicies) {
            resolveNamed(policy);
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
     * Collects corresponding declarations starting at the consumer type.
     *
     * @param consumerType resource type collection starts from
     * @param method operation to match across the consumer hierarchy
     * @param legacyMethodAnnotations caller-supplied inline annotations
     * @return the legacy list when no policy is present; otherwise legacy mirrors plus the selected
     *     {@code RequiresPolicy} mirrors
     */
    public List<AnnotationMirror> collectMethodAnnotations(
            TypeElement consumerType,
            ExecutableElement method,
            List<? extends AnnotationMirror> legacyMethodAnnotations) {
        if (consumerType == null || method == null) {
            throw new IllegalArgumentException("consumer and method are required");
        }
        List<? extends AnnotationMirror> legacy = legacyMethodAnnotations == null ? List.of() : legacyMethodAnnotations;
        List<TypeElement> hierarchy = new ArrayList<>();
        walk(consumerType, hierarchy, new LinkedHashSet<>());

        List<AnnotationMirror> methodSet = new ArrayList<>();
        List<AnnotationMirror> typeSet = new ArrayList<>();
        for (TypeElement type : hierarchy) {
            for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
                String name = qualifiedName(mirror);
                if (name.equals(REQUIRES_POLICY) || isDirect(name)) {
                    typeSet.add(mirror);
                }
            }
            for (ExecutableElement candidate : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (!corresponds(consumerType, method, candidate)) {
                    continue;
                }
                for (AnnotationMirror mirror : candidate.getAnnotationMirrors()) {
                    String name = qualifiedName(mirror);
                    if (name.equals(REQUIRES_POLICY) || isDirect(name)) {
                        methodSet.add(mirror);
                    }
                }
            }
        }
        Set<String> methodPolicies = policyNames(methodSet);
        if (methodPolicies.isEmpty() && policyNames(typeSet).isEmpty()) {
            return List.copyOf(legacy);
        }
        Optional<String> selected = select(methodSet, typeSet);
        List<AnnotationMirror> security = new ArrayList<>();
        if (selected.isPresent()) {
            List<AnnotationMirror> winning = methodPolicies.isEmpty() ? typeSet : methodSet;
            for (AnnotationMirror mirror : winning) {
                if (qualifiedName(mirror).equals(REQUIRES_POLICY)
                        && selected.get().equals(policyName(mirror))
                        && security.stream()
                                .noneMatch(existing -> selected.get().equals(policyName(existing)))) {
                    security.add(mirror);
                }
            }
        }
        List<AnnotationMirror> result = new ArrayList<>();
        for (AnnotationMirror mirror : legacy) {
            String name = qualifiedName(mirror);
            if (!isDirect(name) && !name.equals(REQUIRES_POLICY)) {
                result.add(mirror);
            }
        }
        result.addAll(security);
        return List.copyOf(result);
    }

    private void validateShape(TypeElement policy) {
        if (policy.getKind() != ElementKind.INTERFACE || !policy.getModifiers().contains(Modifier.PUBLIC)) {
            throw new IllegalArgumentException("policy must be a public interface: " + policy.getQualifiedName());
        }
        if (!policy.getTypeParameters().isEmpty()) {
            throw new IllegalArgumentException("policy must not be generic: " + policy.getQualifiedName());
        }
        List<? extends TypeMirror> interfaces = policy.getInterfaces();
        if (interfaces.size() != 1 || !qualifiedName(interfaces.get(0)).equals(ACCESS_POLICY)) {
            throw new IllegalArgumentException("policy must extend only AccessPolicy: " + policy.getQualifiedName());
        }
        if (!ElementFilter.methodsIn(policy.getEnclosedElements()).isEmpty()
                || !ElementFilter.fieldsIn(policy.getEnclosedElements()).isEmpty()
                || !ElementFilter.typesIn(policy.getEnclosedElements()).isEmpty()) {
            throw new IllegalArgumentException(
                    "policy must not declare fields, methods, or nested types: " + policy.getQualifiedName());
        }
    }

    private void validateExclusive(String policyName, List<AnnotationMirror> direct) {
        boolean permit = false;
        boolean deny = false;
        int others = 0;
        for (AnnotationMirror mirror : direct) {
            String name = qualifiedName(mirror);
            if (name.equals(JaxRsAnnotations.PERMIT_ALL)) {
                permit = true;
            } else if (name.equals(JaxRsAnnotations.DENY_ALL)) {
                deny = true;
            } else {
                others++;
            }
        }
        if (permit && deny || (permit || deny) && others > 0) {
            throw new IllegalArgumentException("PermitAll and DenyAll are exclusive on " + policyName);
        }
    }

    private void validateValue(String policyName, AnnotationMirror mirror, String name) {
        if (name.equals(JaxRsAnnotations.ROLES_ALLOWED)) {
            List<String> roles = stringValues(mirror, "value");
            if (roles.isEmpty() || roles.stream().anyMatch(role -> role == null || role.isBlank())) {
                throw new IllegalArgumentException("RolesAllowed value is blank on " + policyName);
            }
        } else if (name.equals(JaxRsAnnotations.AUTHORIZED)) {
            for (String scope : stringValues(mirror, "scopes")) {
                if (scope == null || scope.isBlank()) {
                    throw new IllegalArgumentException("Authorized scope is blank on " + policyName);
                }
            }
        } else if (name.equals(JaxRsAnnotations.REQUIRES_ACTION)) {
            String action = stringValue(mirror, "value");
            if (action == null || !ACTION_SEGMENTS.matcher(action).matches()) {
                throw new IllegalArgumentException("RequiresAction is not a three-segment action on " + policyName);
            }
        }
    }

    private void resolveNamed(String policyName) {
        TypeElement element = elements.getTypeElement(policyName);
        if (element == null) {
            throw new IllegalArgumentException("missing policy " + policyName);
        }
        resolve(element);
    }

    private ExecutableType asMember(DeclaredType consumer, ExecutableElement method) {
        try {
            TypeMirror viewed = types.asMemberOf(consumer, method);
            if (viewed instanceof ExecutableType executable) {
                return executable;
            }
        } catch (IllegalArgumentException ignored) {
            // The passed method is already viewed from a type asMemberOf cannot adapt twice.
        }
        if (method.asType() instanceof ExecutableType executable) {
            return executable;
        }
        throw new IllegalArgumentException("not an executable: " + method);
    }

    /**
     * Reports whether {@code candidate} is the same operation as {@code method} on {@code consumer}.
     *
     * <p>A method always corresponds to itself, whatever its modifiers: it is the operation being
     * scanned. For any other candidate the simple names must match. Viewed from {@code consumer},
     * either signature must be a subsignature of the other, so a type-variable parameter matches
     * the type that binds it. {@code isSubsignature} ignores the name, so the name check stays.
     * Other static methods, other private methods, and other package-private methods declared in
     * another package are not part of the operation.
     *
     * @param consumer the resource type collection starts from
     * @param method the operation to match
     * @param candidate a method declared on the consumer hierarchy
     * @return {@code true} when {@code candidate} belongs to {@code method}'s operation
     */
    public boolean corresponds(TypeElement consumer, ExecutableElement method, ExecutableElement candidate) {
        if (consumer == null || method == null || candidate == null) {
            return false;
        }
        if (candidate.equals(method)) {
            return true;
        }
        if (!candidate.getSimpleName().contentEquals(method.getSimpleName())) {
            return false;
        }
        if (!includeMethod(candidate, consumer)) {
            return false;
        }
        if (!(consumer.asType() instanceof DeclaredType viewedFrom)) {
            return false;
        }
        ExecutableType viewedMethod = asMember(viewedFrom, method);
        ExecutableType adapted = asMember(viewedFrom, candidate);
        return types.isSubsignature(adapted, viewedMethod) || types.isSubsignature(viewedMethod, adapted);
    }

    /**
     * Reports whether {@code candidate} belongs to {@code consumer}'s operation.
     *
     * <p>Static methods, private methods, and package-private methods declared in another package
     * are hidden. That is the same rule the runtime resolver applies.
     *
     * @param candidate a method found on the consumer hierarchy
     * @param consumer the resource type collection starts from
     * @return {@code true} when the method is part of the operation
     */
    private boolean includeMethod(ExecutableElement candidate, TypeElement consumer) {
        Set<Modifier> modifiers = candidate.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        if (!modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED)) {
            return packageName(consumer).equals(packageName(candidate.getEnclosingElement()));
        }
        return true;
    }

    private void walk(TypeElement type, List<TypeElement> hierarchy, Set<String> seen) {
        if (type == null
                || type.getQualifiedName().contentEquals("java.lang.Object")
                || !seen.add(type.getQualifiedName().toString())) {
            return;
        }
        hierarchy.add(type);
        if (type.getSuperclass() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement superclass) {
            walk(superclass, hierarchy, seen);
        }
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface instanceof DeclaredType declared && declared.asElement() instanceof TypeElement superinterface) {
                walk(superinterface, hierarchy, seen);
            }
        }
    }

    private static boolean isDirect(String name) {
        return name.equals(JaxRsAnnotations.PERMIT_ALL)
                || name.equals(JaxRsAnnotations.DENY_ALL)
                || name.equals(JaxRsAnnotations.ROLES_ALLOWED)
                || name.equals(JaxRsAnnotations.AUTHORIZED)
                || name.equals(JaxRsAnnotations.REQUIRES_ACTION);
    }

    private static boolean isProhibited(String name) {
        return name.equals(REQUIRES_POLICY) || name.startsWith(SCHEME_PACKAGE);
    }

    private boolean isVisibleComposition(AnnotationMirror mirror, Set<String> seen) {
        String name = qualifiedName(mirror);
        if (!seen.add(name) || name.startsWith("java.lang.annotation.")) {
            return false;
        }
        Element element = mirror.getAnnotationType().asElement();
        if (!(element instanceof TypeElement annotationType)) {
            return false;
        }
        for (AnnotationMirror meta : annotationType.getAnnotationMirrors()) {
            String metaName = qualifiedName(meta);
            if (metaName.startsWith("java.lang.annotation.")) {
                continue;
            }
            if (isDirect(metaName) || isProhibited(metaName) || isVisibleComposition(meta, seen)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDirect(List<? extends AnnotationMirror> mirrors) {
        for (AnnotationMirror mirror : mirrors) {
            if (isDirect(qualifiedName(mirror))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> policyNames(List<? extends AnnotationMirror> mirrors) {
        Set<String> names = new LinkedHashSet<>();
        for (AnnotationMirror mirror : mirrors) {
            if (qualifiedName(mirror).equals(REQUIRES_POLICY)) {
                String policy = policyName(mirror);
                if (policy != null) {
                    names.add(policy);
                }
            }
        }
        return names;
    }

    private String policyName(AnnotationMirror mirror) {
        Object value = annotationValue(mirror, "value");
        if (value instanceof TypeMirror type) {
            return qualifiedName(type);
        }
        return value == null ? null : value.toString();
    }

    private List<String> stringValues(AnnotationMirror mirror, String member) {
        Object value = annotationValue(mirror, member);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                Object unwrapped = item instanceof AnnotationValue annotationValue ? annotationValue.getValue() : item;
                values.add(unwrapped == null ? null : unwrapped.toString());
            }
            return values;
        }
        return List.of(value.toString());
    }

    private String stringValue(AnnotationMirror mirror, String member) {
        Object value = annotationValue(mirror, member);
        return value == null ? null : value.toString();
    }

    private Object annotationValue(AnnotationMirror mirror, String member) {
        for (var entry : elements.getElementValuesWithDefaults(mirror).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                return entry.getValue().getValue();
            }
        }
        return null;
    }

    private String packageName(Element element) {
        PackageElement pkg = elements.getPackageOf(element);
        return pkg == null ? "" : pkg.getQualifiedName().toString();
    }

    private static String qualifiedName(AnnotationMirror mirror) {
        return qualifiedName(mirror.getAnnotationType());
    }

    private static String qualifiedName(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED
                && type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            return element.getQualifiedName().toString();
        }
        return type.toString();
    }
}
