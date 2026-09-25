// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.apt.ElementInvocationPolicies;
import dev.vertique.input.processing.apt.ElementInvocationPolicies.ElementPolicyChains;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves the effective JAX-RS contract for a concrete resource class by applying the
 * precedence rule: (1) direct annotations on the concrete class or method, (2) matching
 * declarations in the superclass chain, (3) matching declarations in implemented interfaces in
 * BFS discovery order.
 *
 * <p>The BFS interface walk mirrors the runtime {@code TypeResolver.getAllInterfaces} algorithm:
 * deque + {@code LinkedHashSet} dedup keyed by binary name. First-found-wins within a given
 * precedence level, producing the same deterministic outcome as the runtime
 * {@code AnnotationResolver}.
 *
 * <p>Conflict detection is performed per annotation kind. When two implemented interfaces carry
 * conflicting values for the same kind, a compile-time error is emitted via
 * {@link CodegenContext#diagnostics()} and the offending method or class is excluded from the
 * resolved contract. Identical values across interfaces are not a conflict.
 *
 * <p>When a direct annotation on the concrete class or method disagrees with an interface
 * declaration, the direct annotation wins (precedence rule 1) and a compiler warning is emitted
 * so the developer notices the silent override.
 */
public final class EffectiveJaxRsContractResolver {

    // --- Annotation FQN constants ---

    private static final String CONSUMES_FQN = "jakarta.ws.rs.Consumes";
    private static final String PRODUCES_FQN = "jakarta.ws.rs.Produces";
    private static final String DEFAULT_VALUE_FQN = "jakarta.ws.rs.DefaultValue";
    private static final String OPERATION_FQN = "io.swagger.v3.oas.annotations.Operation";
    private static final String VALIDATE_WITH_FQN = "dev.vertique.core.validation.ValidateWith";

    // --- Multi-value shape policy ---

    /**
     * The non-{@code enum} element types accepted as a scalar array component — the compile-time
     * mirror of the boxed/{@code String} set in {@code ResourceScanner.isScalarArrayComponent}.
     */
    private static final Set<String> SCALAR_ARRAY_COMPONENT_FQNS = Set.of(
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Character");

    private final CodegenContext ctx;

    /**
     * The shared invocation-policy adapter: it owns the hierarchy-merged view of an element (the
     * declaring site, superclasses bottom-up, then interfaces), meta-annotation recursion, and the
     * precedence between the additive and the skip annotation of each axis, so compile-time
     * derivation cannot drift from the reflective runtime's.
     */
    private final ElementInvocationPolicies policies;

    /**
     * Creates a new {@code EffectiveJaxRsContractResolver} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public EffectiveJaxRsContractResolver(CodegenContext ctx) {
        this.ctx = ctx;
        this.policies = new ElementInvocationPolicies(ctx.elements(), ctx.types());
    }

    // --- Public API ---

    /**
     * Returns {@code true} iff {@code concreteClass} is a concrete (non-abstract, non-interface)
     * type AND either carries a direct {@code @Path} annotation or transitively implements an
     * interface that carries {@code @Path}.
     *
     * <p>This is the cheap yes/no predicate used by candidate discovery (step 1). It does NOT
     * run the full resolver and does NOT check for class-level conflicts.
     *
     * @param concreteClass the type element to test; must not be {@code null}
     * @return {@code true} if the class is a concrete JAX-RS resource candidate
     */
    public boolean hasEffectivePath(TypeElement concreteClass) {
        if (concreteClass.getKind() == ElementKind.INTERFACE) {
            return false;
        }
        if (concreteClass.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        // Direct @Path on the concrete class
        if (AnnotationMirrors.isPresent(concreteClass, JaxRsAnnotations.PATH)) {
            return true;
        }
        // Walk interfaces transitively via BFS
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, concreteClass)) {
            if (AnnotationMirrors.isPresent(iface, JaxRsAnnotations.PATH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the full effective contract for the given concrete resource class.
     *
     * <p>Uses {@link JaxRsMethodDiscovery#collect} for the concrete methods, then resolves each
     * method's effective annotations by the precedence rule. Conflict errors are emitted
     * immediately; conflicting methods are excluded from the returned contract's method list.
     * Class-level conflicts cause the returned contract to have an empty method list (and callers
     * should not proceed with descriptor emission).
     *
     * @param concreteClass the concrete resource class to resolve; must not be {@code null}
     * @return the resolved effective contract; never {@code null}
     */
    public EffectiveResourceContract resolve(TypeElement concreteClass) {
        // --- Class-level resolution ---
        String classPath = resolveClassPath(concreteClass);
        EffectiveSecurityContract classSecurity = resolveClassSecurity(concreteClass);

        // Class-level conflict check: did resolveClassPath or resolveClassSecurity detect a
        // conflict? If so, return an empty-methods contract to signal skip.
        if (classPath == null && !AnnotationMirrors.isPresent(concreteClass, JaxRsAnnotations.PATH)) {
            // Check if any interface has @Path — if none found, that's fine; class-level conflict
            // detection is done inside resolveClassPath via the conflictingPath flag.
        }

        // --- Method-level resolution ---
        List<ExecutableElement> concreteMethods = JaxRsMethodDiscovery.collect(ctx, concreteClass);
        List<EffectiveMethodContract> methods = new ArrayList<>();
        boolean classLevelConflict = isClassLevelConflict(concreteClass);

        if (!classLevelConflict) {
            for (ExecutableElement method : concreteMethods) {
                EffectiveMethodContract mc = resolveMethod(concreteClass, method);
                if (mc != null) {
                    methods.add(mc);
                }
            }
        }

        return new EffectiveResourceContract(concreteClass, classPath, classSecurity, List.copyOf(methods));
    }

    // --- Class-level resolution helpers ---

    /**
     * Resolves the effective class-level {@code @Path} value using the precedence rule.
     * Returns {@code null} if no effective path is found.
     *
     * <p>When the direct class annotation disagrees with an interface declaration, a warning is
     * emitted and the direct value wins.
     *
     * @param concreteClass the concrete class
     * @return the effective {@code @Path} value, or {@code null}
     */
    private String resolveClassPath(TypeElement concreteClass) {
        // Precedence 1: direct @Path on the concrete class
        String directPath = pathValue(concreteClass);
        if (directPath != null) {
            // Warn if any interface also carries @Path with a different value
            for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, concreteClass)) {
                String ifacePath = pathValue(iface);
                if (ifacePath != null && !ifacePath.equals(directPath)) {
                    ctx.diagnostics()
                            .warning(
                                    concreteClass,
                                    Diagnostics.directOverridesInterfaceWarning(
                                            concreteClass.getSimpleName().toString(),
                                            "@Path",
                                            directPath,
                                            iface.getQualifiedName().toString(),
                                            ifacePath));
                }
            }
            return directPath;
        }

        // Precedence 2: superclass chain
        String superPath = resolveClassPathFromSuperChain(concreteClass);
        if (superPath != null) {
            return superPath;
        }

        // Precedence 3: BFS interfaces — conflict detection
        return resolveClassPathFromInterfaces(concreteClass);
    }

    /**
     * Walks the superclass chain (excluding the concrete class itself) looking for a class-level
     * {@code @Path}.
     *
     * @param concreteClass the concrete class
     * @return the first found {@code @Path} value, or {@code null}
     */
    private String resolveClassPathFromSuperChain(TypeElement concreteClass) {
        TypeElement current = JaxRsHierarchy.superClass(ctx, concreteClass);
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            String p = pathValue(current);
            if (p != null) {
                return p;
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }
        return null;
    }

    /**
     * BFS over all transitively implemented interfaces looking for class-level {@code @Path}.
     * Emits a compile-time error when two interfaces carry different {@code @Path} values.
     *
     * @param concreteClass the concrete class
     * @return the first-found interface {@code @Path} value, or {@code null} on conflict or absence
     */
    private String resolveClassPathFromInterfaces(TypeElement concreteClass) {
        String found = null;
        String foundInterface = null;
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, concreteClass)) {
            String p = pathValue(iface);
            if (p == null) continue;
            if (found == null) {
                found = p;
                foundInterface = iface.getQualifiedName().toString();
            } else if (!found.equals(p)) {
                ctx.diagnostics()
                        .error(
                                concreteClass,
                                Diagnostics.classContractConflict(
                                        concreteClass.getSimpleName().toString(),
                                        "@Path",
                                        foundInterface,
                                        found,
                                        iface.getQualifiedName().toString(),
                                        p));
                return null;
            }
        }
        return found;
    }

    /**
     * Returns {@code true} if the concrete class has a class-level path-conflict or security
     * conflict across its implemented interfaces — used to gate method processing.
     *
     * @param concreteClass the concrete class
     * @return {@code true} if a class-level conflict was already detected
     */
    private boolean isClassLevelConflict(TypeElement concreteClass) {
        // Re-run the path resolution to see if it would produce null due to conflict
        // (direct path wins, so conflict only happens when direct is absent and interfaces conflict)
        if (pathValue(concreteClass) != null) {
            return false;
        }
        if (resolveClassPathFromSuperChain(concreteClass) != null) {
            return false;
        }
        // Check interfaces for path conflict
        String found = null;
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, concreteClass)) {
            String p = pathValue(iface);
            if (p == null) continue;
            if (found == null) {
                found = p;
            } else if (!found.equals(p)) {
                return true; // conflict detected
            }
        }
        return false;
    }

    /**
     * Resolves the effective class-level security contract using the precedence rule.
     *
     * @param concreteClass the concrete class
     * @return the effective security contract; never {@code null}
     */
    private EffectiveSecurityContract resolveClassSecurity(TypeElement concreteClass) {
        // Precedence 1: direct security annotations on the concrete class
        EffectiveSecurityContract direct = buildSecurityContract(concreteClass);
        if (!direct.isEmpty()) {
            // Warn if any interface also carries conflicting security
            warnSecurityOverride(concreteClass, direct, JaxRsHierarchy.allInterfaces(ctx, concreteClass), true);
            return direct;
        }

        // Precedence 2: superclass chain
        TypeElement current = JaxRsHierarchy.superClass(ctx, concreteClass);
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            EffectiveSecurityContract sc = buildSecurityContract(current);
            if (!sc.isEmpty()) {
                return sc;
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }

        // Precedence 3: BFS interfaces
        return resolveSecurityFromInterfaces(concreteClass, concreteClass, true);
    }

    // --- Method-level resolution helpers ---

    /**
     * Resolves the effective contract for a single concrete method. Returns {@code null} when a
     * method-level conflict was detected (and a diagnostic was emitted).
     *
     * @param concreteClass  the resource class
     * @param concreteMethod the concrete method
     * @return the resolved method contract, or {@code null} on conflict
     */
    private EffectiveMethodContract resolveMethod(TypeElement concreteClass, ExecutableElement concreteMethod) {
        // HTTP verb
        String httpMethod = resolveHttpVerb(concreteMethod, concreteClass);

        // Method @Path
        String methodPath =
                resolveMethodAnnotationString(concreteMethod, concreteClass, JaxRsAnnotations.PATH, "value");

        // @Operation.operationId
        String operationId = resolveOperationId(concreteMethod, concreteClass);

        // @Consumes / @Produces
        List<String> consumes = resolveMediaTypes(concreteMethod, concreteClass, CONSUMES_FQN);
        List<String> produces = resolveMediaTypes(concreteMethod, concreteClass, PRODUCES_FQN);

        // Method-level security
        EffectiveSecurityContract methodSecurity = resolveMethodSecurity(concreteMethod, concreteClass);

        // @ValidateWith
        List<TypeMirror> validationGroups = resolveValidationGroups(concreteMethod, concreteClass);

        // Route-level canonicalization / sanitization chains, resolved over the hierarchy-merged
        // view of the method and the resource type (interface- and superclass-declared policies
        // included). An element that both declares and skips a policy is a configuration error:
        // the shared resolver rejects it and the method is excluded from the resolved contract.
        ElementPolicyChains routePolicies;
        try {
            routePolicies = policies.resolveRoute(concreteMethod, concreteClass);
        } catch (InvocationPolicyConflictException e) {
            ctx.diagnostics().error(concreteMethod, e.getMessage());
            return null;
        }
        List<TypeMirror> routeCanonicalizers = routePolicies.canonicalizers();
        List<TypeMirror> routeSanitizers = routePolicies.sanitizers();

        // Parameters
        List<EffectiveParamContract> params = resolveParams(concreteMethod, concreteClass, routePolicies);

        return new EffectiveMethodContract(
                concreteMethod,
                httpMethod,
                methodPath,
                operationId,
                consumes,
                produces,
                methodSecurity,
                validationGroups,
                params,
                routeCanonicalizers,
                routeSanitizers);
    }

    /**
     * Resolves the effective HTTP verb annotation FQN for a method using the precedence rule.
     *
     * @param method        the concrete method
     * @param resourceClass the enclosing resource class (for interface lookup)
     * @return the HTTP verb annotation FQN, or {@code null} for non-endpoint methods
     */
    private String resolveHttpVerb(ExecutableElement method, TypeElement resourceClass) {
        // Precedence 1: direct verb on the concrete method
        for (String verb : JaxRsAnnotations.HTTP_VERBS) {
            if (AnnotationMirrors.isPresent(method, verb)) {
                return verb;
            }
        }

        // Precedence 2: superclass chain — method already includes inherited declarations from
        // JaxRsMethodDiscovery (the concrete method may itself be inherited, from a superclass or
        // as an interface default method)
        // The method element already IS the effective method from the chain; superclass is already
        // folded in via JaxRsMethodDiscovery. So we only need to check interfaces.

        // Precedence 3: BFS interfaces — find matching abstract method
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            for (String verb : JaxRsAnnotations.HTTP_VERBS) {
                if (AnnotationMirrors.isPresent(ifaceMethod, verb)) {
                    return verb;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a single string-valued annotation attribute on a method using the precedence rule.
     *
     * @param method         the concrete method
     * @param resourceClass  the resource class
     * @param annotationFqn  the annotation FQN
     * @param attributeName  the attribute name (usually {@code "value"})
     * @return the attribute value, or {@code null} if absent
     */
    private String resolveMethodAnnotationString(
            ExecutableElement method, TypeElement resourceClass, String annotationFqn, String attributeName) {
        // Precedence 1: direct
        String direct = findAnnotationString(method, annotationFqn, attributeName);
        if (direct != null) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            String v = findAnnotationString(ifaceMethod, annotationFqn, attributeName);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Resolves the {@code @Operation.operationId} for a method using the precedence rule.
     *
     * @param method        the concrete method
     * @param resourceClass the resource class
     * @return the operationId, or {@code null} if absent
     */
    private String resolveOperationId(ExecutableElement method, TypeElement resourceClass) {
        // Precedence 1: direct
        String direct = findAnnotationString(method, OPERATION_FQN, "operationId");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            String v = findAnnotationString(ifaceMethod, OPERATION_FQN, "operationId");
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Resolves the media types from {@code @Consumes} or {@code @Produces} using the precedence
     * rule.
     *
     * @param method         the concrete method
     * @param resourceClass  the resource class
     * @param annotationFqn  either {@link #CONSUMES_FQN} or {@link #PRODUCES_FQN}
     * @return the resolved list of media-type strings; empty if absent
     */
    private List<String> resolveMediaTypes(ExecutableElement method, TypeElement resourceClass, String annotationFqn) {
        // Precedence 1: direct on method
        List<String> direct = findAnnotationStringArray(method, annotationFqn);
        if (direct != null) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            List<String> v = findAnnotationStringArray(ifaceMethod, annotationFqn);
            if (v != null) {
                return v;
            }
        }
        return List.of();
    }

    /**
     * Resolves the effective method-level security contract using the precedence rule.
     *
     * @param method        the concrete method
     * @param resourceClass the resource class
     * @return the effective security contract; never {@code null}
     */
    private EffectiveSecurityContract resolveMethodSecurity(ExecutableElement method, TypeElement resourceClass) {
        // Precedence 1: direct on method
        EffectiveSecurityContract direct = buildSecurityContract(method);
        if (!direct.isEmpty()) {
            warnSecurityOverride(method, direct, JaxRsHierarchy.allInterfaces(ctx, resourceClass), false);
            return direct;
        }
        // Precedence 3: BFS interfaces
        return resolveSecurityFromInterfaces(method, resourceClass, false);
    }

    /**
     * Resolves the validation groups from {@code @ValidateWith} using the precedence rule.
     *
     * @param method        the concrete method
     * @param resourceClass the resource class
     * @return the list of validation group type mirrors, or {@code null} if the annotation is absent
     */
    private List<TypeMirror> resolveValidationGroups(ExecutableElement method, TypeElement resourceClass) {
        // Precedence 1: direct on method
        List<TypeMirror> direct = findAnnotationClassArray(method, VALIDATE_WITH_FQN);
        if (direct != null) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            List<TypeMirror> v = findAnnotationClassArray(ifaceMethod, VALIDATE_WITH_FQN);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Resolves the effective parameter contracts for all parameters of a concrete method.
     *
     * @param method        the concrete method
     * @param resourceClass the resource class
     * @param routePolicies the route-level chains to use as the baseline for each parameter
     * @return the list of parameter contracts; never {@code null}
     */
    private List<EffectiveParamContract> resolveParams(
            ExecutableElement method, TypeElement resourceClass, ElementPolicyChains routePolicies) {
        List<EffectiveParamContract> result = new ArrayList<>();
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            var param = params.get(i);
            result.add(resolveParam(param, i, method, resourceClass, routePolicies));
        }
        return List.copyOf(result);
    }

    /**
     * Resolves the effective contract for a single parameter at the given index.
     *
     * @param concreteParam       the concrete parameter element
     * @param paramIndex          zero-based parameter index
     * @param method              the enclosing method
     * @param resourceClass       the resource class
     * @param routePolicies       the route-level chains to use as baseline
     * @return the resolved parameter contract; never {@code null}
     */
    private EffectiveParamContract resolveParam(
            javax.lang.model.element.VariableElement concreteParam,
            int paramIndex,
            ExecutableElement method,
            TypeElement resourceClass,
            ElementPolicyChains routePolicies) {

        // Classify using the concrete param (direct annotations take precedence)
        // For param source, we also look at the interface param if no direct annotation found
        JaxRsParamSource source = classifyEffectiveParam(concreteParam, paramIndex, method, resourceClass);

        // Param name — from the classification annotation (e.g. @PathParam("id"))
        String name = resolveParamName(concreteParam, paramIndex, method, resourceClass, source);

        // @DefaultValue
        String defaultValue = resolveDefaultValue(concreteParam, paramIndex, method, resourceClass);

        // Merged annotation sources (GitHub issue #162): concreteParam plus the matching parameter
        // on each superclass/interface override that declares it, mirroring the runtime
        // AnnotationResolver.resolveParameterAnnotations merge so a converter-decision marker
        // annotation declared only on an interface method is not lost when the codegen path
        // materializes literals from concreteParam alone.
        List<javax.lang.model.element.VariableElement> annotationSources =
                resolveParamAnnotationSources(concreteParam, paramIndex, method, resourceClass);

        TypeMirror type = concreteParam.asType();

        // Component type for a multi-value shape (List<T>, Set<T>, T[], ...) — SOURCE-GATED, exactly
        // like beanParamType and genericType below. Only the sources whose reflective counterpart
        // resolves a component type may carry one; see resolveComponentType's javadoc for the scope
        // and why PATH is excluded.
        TypeMirror componentType = resolvesComponentType(source) ? resolveComponentType(type) : null;

        // Bean param type
        TypeMirror beanParamType = (source == JaxRsParamSource.BEAN_PARAM) ? type : null;

        // Generic type for BODY params — capture the full parameterized type mirror so the emitter
        // can emit a TypeReference-style token for generic bodies (e.g. List<Foo>)
        TypeMirror genericType = (source == JaxRsParamSource.BODY) ? type : null;

        // Per-parameter policies: start from the route-level chains, apply the parameter's own
        // hierarchy-merged overrides (an annotation declared only on an overridden method's
        // parameter counts). A parameter that both declares and skips a policy is a configuration
        // error: the diagnostic names the parameter and the parameter falls back to no policies.
        ElementPolicyChains paramPolicies;
        try {
            paramPolicies = policies.resolveParameter(concreteParam, paramIndex, method, resourceClass, routePolicies);
        } catch (InvocationPolicyConflictException e) {
            ctx.diagnostics().error(concreteParam, e.getMessage());
            paramPolicies = ElementPolicyChains.NONE;
        }
        List<TypeMirror> paramCanonicalizers = paramPolicies.canonicalizers();
        List<TypeMirror> paramSanitizers = paramPolicies.sanitizers();

        return new EffectiveParamContract(
                concreteParam,
                annotationSources,
                source,
                name,
                defaultValue,
                type,
                componentType,
                beanParamType,
                genericType,
                paramCanonicalizers,
                paramSanitizers);
    }

    /**
     * Classifies the effective source for a parameter, falling through to the matching interface
     * method parameter when the concrete param has no direct annotation.
     *
     * @param concreteParam the concrete parameter
     * @param paramIndex    zero-based index
     * @param method        the enclosing concrete method
     * @param resourceClass the resource class
     * @return the effective {@link JaxRsParamSource}
     */
    private JaxRsParamSource classifyEffectiveParam(
            javax.lang.model.element.VariableElement concreteParam,
            int paramIndex,
            ExecutableElement method,
            TypeElement resourceClass) {
        // Classify the concrete param first; if it falls through to BODY and there's an interface
        // param with annotations, use those
        JaxRsParamSource direct = JaxRsParamClassifier.classify(concreteParam, ctx.types(), ctx.elements());
        if (direct != JaxRsParamSource.BODY) {
            return direct;
        }
        // Check if an interface param would classify differently
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            var ifaceParams = ifaceMethod.getParameters();
            if (paramIndex >= ifaceParams.size()) continue;
            JaxRsParamSource ifaceSource =
                    JaxRsParamClassifier.classify(ifaceParams.get(paramIndex), ctx.types(), ctx.elements());
            if (ifaceSource != JaxRsParamSource.BODY) {
                return ifaceSource;
            }
        }
        return JaxRsParamSource.BODY;
    }

    /**
     * Resolves the full list of parameter elements whose annotation mirrors back the effective
     * annotation set for a parameter (GitHub issue #162), mirroring the runtime
     * {@code AnnotationResolver.resolveParameterAnnotations} merge at compile time: the concrete
     * parameter, then the matching parameter on each superclass in the chain that declares the
     * method, then the matching parameter on each transitively implemented interface (BFS order via
     * {@link JaxRsHierarchy#allInterfaces}) that declares the method. A superclass/interface method
     * with fewer parameters than {@code paramIndex} (should not normally happen for a genuine
     * override, but guarded defensively) is skipped for that level.
     *
     * <p>Unlike {@link #resolveParamName}/{@link #classifyEffectiveParam} (which resolve a single
     * effective value by first-found-wins precedence), this method returns <em>every</em> declaring
     * parameter element so a caller (the literal-annotation materializer) can union annotation types
     * across all of them — a marker annotation declared only on an interface's parameter, with no
     * direct override on the concrete parameter, must still be visible.
     *
     * @param concreteParam the concrete parameter element; always included first
     * @param paramIndex    zero-based parameter index
     * @param method        the enclosing concrete method
     * @param resourceClass the resource class
     * @return an immutable list starting with {@code concreteParam}, followed by each
     *         superclass/interface override's matching parameter that declares the method; never
     *         {@code null}, never empty
     */
    private List<javax.lang.model.element.VariableElement> resolveParamAnnotationSources(
            javax.lang.model.element.VariableElement concreteParam,
            int paramIndex,
            ExecutableElement method,
            TypeElement resourceClass) {
        List<javax.lang.model.element.VariableElement> sources = new ArrayList<>();
        sources.add(concreteParam);

        // Superclass chain (excluding the concrete class itself)
        TypeElement current = JaxRsHierarchy.superClass(ctx, resourceClass);
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            ExecutableElement superMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, current);
            if (superMethod != null && paramIndex < superMethod.getParameters().size()) {
                sources.add(superMethod.getParameters().get(paramIndex));
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }

        // BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod != null && paramIndex < ifaceMethod.getParameters().size()) {
                sources.add(ifaceMethod.getParameters().get(paramIndex));
            }
        }

        return List.copyOf(sources);
    }

    /**
     * Resolves the effective annotation name for a parameter (e.g. the value of {@code @PathParam}).
     *
     * @param concreteParam the concrete parameter
     * @param paramIndex    zero-based index
     * @param method        the enclosing method
     * @param resourceClass the resource class
     * @param source        the resolved param source
     * @return the param name, or {@code null} if the source has no name annotation
     */
    private String resolveParamName(
            javax.lang.model.element.VariableElement concreteParam,
            int paramIndex,
            ExecutableElement method,
            TypeElement resourceClass,
            JaxRsParamSource source) {
        String annotationFqn = paramSourceAnnotationFqn(source);
        if (annotationFqn == null) {
            return null;
        }
        // Precedence 1: direct on concrete param
        String direct = findAnnotationString(concreteParam, annotationFqn, "value");
        if (direct != null) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            var ifaceParams = ifaceMethod.getParameters();
            if (paramIndex >= ifaceParams.size()) continue;
            String v = findAnnotationString(ifaceParams.get(paramIndex), annotationFqn, "value");
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Returns the annotation FQN that carries the param name for the given source kind,
     * or {@code null} for sources that have no name annotation.
     *
     * @param source the param source
     * @return the annotation FQN, or {@code null}
     */
    private String paramSourceAnnotationFqn(JaxRsParamSource source) {
        return switch (source) {
            case PATH -> JaxRsAnnotations.PATH_PARAM;
            case QUERY -> JaxRsAnnotations.QUERY_PARAM;
            case HEADER -> JaxRsAnnotations.HEADER_PARAM;
            case COOKIE -> JaxRsAnnotations.COOKIE_PARAM;
            case FORM -> JaxRsAnnotations.FORM_PARAM;
            default -> null;
        };
    }

    /**
     * Resolves the {@code @DefaultValue} string for a parameter using the precedence rule.
     *
     * @param concreteParam the concrete parameter
     * @param paramIndex    zero-based index
     * @param method        the enclosing method
     * @param resourceClass the resource class
     * @return the default value string, or {@code null} if absent
     */
    private String resolveDefaultValue(
            javax.lang.model.element.VariableElement concreteParam,
            int paramIndex,
            ExecutableElement method,
            TypeElement resourceClass) {
        // Precedence 1: direct
        String direct = findAnnotationString(concreteParam, DEFAULT_VALUE_FQN, "value");
        if (direct != null) {
            return direct;
        }
        // Precedence 3: BFS interfaces
        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
            if (ifaceMethod == null) continue;
            var ifaceParams = ifaceMethod.getParameters();
            if (paramIndex >= ifaceParams.size()) continue;
            String v = findAnnotationString(ifaceParams.get(paramIndex), DEFAULT_VALUE_FQN, "value");
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Reports whether a parameter classified as {@code source} may carry a resolved
     * {@code componentType} at all — the source gate for {@link #resolveComponentType(TypeMirror)},
     * mirroring which branches of the reflective {@code ResourceScanner.extractParams} call its own
     * {@code resolveComponentType}:
     *
     * <ul>
     *   <li>{@code QUERY}, {@code HEADER}, {@code COOKIE}, {@code FORM} — the bindable multi-value
     *       sources; each of those four branches of {@code ResourceScanner.extractParams} calls its
     *       {@code resolveComponentType(param)}.</li>
     *   <li>{@code FILE_UPLOADS}, {@code ENTITY_PARTS} — classified from an unannotated
     *       {@code List<FileUpload>} / {@code List<EntityPart>}, whose element type
     *       <em>is</em> the native multipart target the runtime guards on. The reflective
     *       {@code isFileUploadList} / {@code isEntityPartList} branches hard-code
     *       {@code FileUpload.class} / {@code EntityPart.class}; resolving the declared {@code List}
     *       element yields the same class, so these must stay inside the gate.</li>
     *   <li>{@code BODY} — held exactly as it was. The two paths already disagree here (the
     *       reflective scanner hard-codes {@code null} for BODY), a divergence audited inert and
     *       routed as a deliberate follow-up rather than changed under this gate: source-gating BODY
     *       would also drop the {@code componentType} of a BODY <em>collection</em>, a
     *       consumer-visible change to the public {@code ParamMeta} record.</li>
     *   <li>{@code PATH} — excluded. A path parameter is <em>never</em> multi-valued: it is bound
     *       from {@code RoutingContext.pathParams()}, a {@code Map<String, String>}, and
     *       {@code DefaultBoundRequest.bindPath} always wraps a single scalar, so no
     *       {@code JsonArray} can reach the collection branch of extraction. The reflective
     *       {@code @PathParam} branch therefore hard-codes {@code null} and never calls its
     *       {@code resolveComponentType}. Emitting a component type here made the startup converter
     *       probe substitute the <em>element</em> type, so an unbindable {@code @PathParam String[]}
     *       passed startup validation on the generated path and failed opaquely per request, while
     *       its reflective twin was rejected loudly at startup.</li>
     *   <li>{@code CONTEXT}, {@code PRECONDITIONS}, {@code BEAN_PARAM} — excluded; the reflective
     *       branches pass {@code null} for all three, and none of them binds element-wise (a
     *       bean-param's own collection <em>fields</em> are a separate, separately-tracked concern
     *       resolved inside the bean-param model, not by this method).</li>
     * </ul>
     *
     * @param source the parameter's effective classification
     * @return {@code true} when a component type may be resolved for this source
     */
    private static boolean resolvesComponentType(JaxRsParamSource source) {
        return switch (source) {
            case QUERY, HEADER, COOKIE, FORM, FILE_UPLOADS, ENTITY_PARTS, BODY -> true;
            case PATH, CONTEXT, PRECONDITIONS, BEAN_PARAM -> false;
        };
    }

    /**
     * Extracts the element type of a multi-value parameter shape, or returns {@code null} when the
     * parameter is not multi-valued. Two shapes are recognized, mirroring the reflective
     * {@code ResourceScanner.resolveComponentType} so the generated dispatch path binds and
     * validates <em>all</em> submitted values exactly where the reflective path does:
     *
     * <ul>
     *   <li>a parameterized collection — {@code List<T>}, {@code Set<T>}, {@code SortedSet<T>},
     *       {@code NavigableSet<T>}, or {@code Collection<T>} (see
     *       {@link #isSupportedCollectionFqn(String)}) — yields {@code T}, but <em>only</em> when
     *       {@code T} is an argument the reflective side would see as a plain {@link Class} (see
     *       {@link #isReflectivelyClassTypeArgument(TypeMirror)}), mirroring that side's
     *       {@code typeArg instanceof Class<?>} gate. A wildcard ({@code List<? extends CharSequence>},
     *       {@code List<?>}), a type variable ({@code List<T>}), and a nested parameterized type
     *       ({@code List<List<String>>}) therefore resolve {@code null} on both paths;
     *   <li>an array {@code T[]} whose element type is a scalar array component (see
     *       {@link #isScalarArrayComponent(TypeMirror)}) — yields {@code T}.
     * </ul>
     *
     * <p>Array element types <em>are</em> recognized: the descriptor emits the array parameter's own
     * FQN as a <em>binary</em> base name plus source-form {@code []} pairs (see
     * {@code TypeMirrorFqn.erasedFqn}), and {@code ArrayFqns} — reached through
     * {@code GeneratedJaxRsDescriptorSupport.resolveClass} — resolves that form via
     * {@link java.lang.reflect.Array#newInstance(Class, int...)}, so a non-null
     * {@code componentType} on an array parameter no longer risks an unresolvable type name. (The
     * emitted {@code componentType} FQN itself is a plain element type such as
     * {@code java.lang.String}, which {@code Class.forName} has always resolved.)
     *
     * <p>Primitive-array element types are <em>excluded</em> so {@code byte[]} / {@code char[]} and
     * every other primitive array keep their BODY classification as binary/buffer body shapes rather
     * than being routed through the multi-value collection path.
     *
     * <p><strong>This method inspects the type only — it is deliberately not the whole policy.</strong>
     * Whether a parameter may carry a component type at all is decided by its <em>source</em>, in
     * {@link #resolvesComponentType(JaxRsParamSource)}, which is the sole caller's gate. A shape this
     * method would happily recognize (an array, a {@code List<T>}) still resolves {@code null} when
     * its source does not bind element-wise — notably {@code PATH}, whose values come from
     * {@code RoutingContext.pathParams()}, a {@code Map<String, String>}, and can never be
     * multi-valued.
     *
     * @param type the parameter type mirror
     * @return the element type for a recognized multi-value shape, or {@code null} otherwise
     */
    private TypeMirror resolveComponentType(TypeMirror type) {
        // Array shape: T[] — restricted to scalar element types, so byte[]/char[] and other
        // primitive arrays stay BODY (decision 8; mirrors ResourceScanner.isScalarArrayComponent).
        if (type instanceof javax.lang.model.type.ArrayType arrayType) {
            TypeMirror component = arrayType.getComponentType();
            return isScalarArrayComponent(component) ? component : null;
        }

        if (!(type instanceof javax.lang.model.type.DeclaredType declared)) {
            return null;
        }
        TypeMirror erased = ctx.types().erasure(type);
        var erasedEl = ctx.types().asElement(erased);
        if (erasedEl instanceof TypeElement te
                && isSupportedCollectionFqn(te.getQualifiedName().toString())) {
            var args = declared.getTypeArguments();
            // Mirror ResourceScanner.resolveComponentType's `typeArg instanceof Class<?>` gate — see
            // isReflectivelyClassTypeArgument. Returning the argument unconditionally erased a wildcard
            // or type variable to its bound, yielding a non-null componentType where the reflective twin
            // yields null; componentType is the framework's single multiplicity trigger, so that is a
            // codegen-on/codegen-off classification divergence.
            if (!args.isEmpty() && isReflectivelyClassTypeArgument(args.get(0))) {
                return args.get(0);
            }
        }
        return null;
    }

    /**
     * Returns whether {@code argument} — a collection's first type argument — is one that the reflective
     * scanner would see as a plain {@link Class}, which is the exact condition its
     * {@code typeArg instanceof Class<?>} gate tests ({@code ResourceScanner.resolveComponentType}).
     * This side must agree with it in <em>both</em> directions, because {@code componentType} is the
     * framework's single multiplicity trigger: a non-null value here where the reflective path resolves
     * {@code null} (or vice versa) classifies the same declaration differently depending on whether
     * codegen ran.
     *
     * <p>Core reflection reifies a generic signature's type argument as a {@link Class} only when it
     * carries no generic information of its own — {@code sun.reflect.generics}' factory returns a
     * {@code Class} for an array whose component is itself a {@code Class}, and a
     * {@code ParameterizedType} / {@code GenericArrayType} / {@code TypeVariable} / {@code WildcardType}
     * otherwise. The mirror-side equivalents:
     *
     * <ul>
     *   <li>a {@link javax.lang.model.type.DeclaredType} with <b>no</b> type arguments &rarr; accepted
     *       ({@code List<String>}, {@code List<Season>}, a raw {@code List<Map>});</li>
     *   <li>an {@link javax.lang.model.type.ArrayType} whose component type recursively satisfies this
     *       predicate &rarr; accepted ({@code List<Inner[]>}, {@code List<int[]>}), matching the
     *       reflective {@code Inner[].class};</li>
     *   <li>a primitive &rarr; accepted; it can only be reached as an array component;</li>
     *   <li>a parameterized {@code DeclaredType} ({@code List<List<String>>}), a wildcard
     *       ({@code List<? extends CharSequence>}, {@code List<?>}), a type variable
     *       ({@code List<T>}), or a generic array ({@code List<T[]>}) &rarr; rejected, exactly as the
     *       reflective gate rejects the {@code ParameterizedType}/{@code WildcardType}/
     *       {@code TypeVariable}/{@code GenericArrayType} it sees for each.</li>
     * </ul>
     *
     * @param argument the collection's first type argument
     * @return {@code true} when the reflective side would report this argument as a {@link Class}
     */
    private static boolean isReflectivelyClassTypeArgument(TypeMirror argument) {
        if (argument instanceof javax.lang.model.type.DeclaredType declared) {
            return declared.getTypeArguments().isEmpty();
        }
        if (argument instanceof javax.lang.model.type.ArrayType array) {
            return isReflectivelyClassTypeArgument(array.getComponentType());
        }
        return argument.getKind().isPrimitive();
    }

    /**
     * Returns {@code true} if {@code componentType} is a sensible element type for a multi-value
     * scalar array parameter: {@code java.lang.String}, a boxed numeric ({@code Integer},
     * {@code Long}, {@code Short}, {@code Byte}, {@code Double}, {@code Float}), {@code Boolean},
     * {@code Character}, or an {@code enum}. Primitive element types return {@code false} so
     * {@code byte[]} / {@code char[]} (and every other primitive array) are not treated as
     * multi-value collections.
     *
     * <p>This is the compile-time half of a policy the framework implements twice: the reflective
     * counterpart is {@code ResourceScanner.isScalarArrayComponent(Class)} in
     * {@code vertique-rest-jaxrs}. The two cannot share one method — this side sees
     * {@link TypeMirror}/{@link javax.lang.model.type.TypeKind}, the reflective side sees
     * {@link Class} — so the shapes they accept are held equal by the parity test
     * {@code GeneratedArrayParamParityTest} (plan decision 8). Any edit here must be mirrored there.
     *
     * @param componentType the array element type mirror
     * @return {@code true} when the element type is a sensible scalar array component
     */
    private boolean isScalarArrayComponent(TypeMirror componentType) {
        if (componentType.getKind().isPrimitive()) {
            return false;
        }
        TypeMirror erased = ctx.types().erasure(componentType);
        if (!(ctx.types().asElement(erased) instanceof TypeElement te)) {
            // Nested arrays (String[][]) and wildcards — not scalar elements. Note a *bounded* type
            // variable does NOT land here: erasure resolves it to its bound, so <T extends Season> T[]
            // arrives as the enum Season and is accepted below. That is deliberate parity — the
            // reflective scanner sees the same erased Season[] from Parameter.getType().
            return false;
        }
        if (te.getKind() == ElementKind.ENUM) {
            return true;
        }
        return SCALAR_ARRAY_COMPONENT_FQNS.contains(te.getQualifiedName().toString());
    }

    /**
     * Returns {@code true} if {@code fqn} names one of the multi-value collection interfaces whose
     * type argument is bound element-wise: {@code java.util.List}, {@code java.util.Set},
     * {@code java.util.SortedSet}, {@code java.util.NavigableSet}, or {@code java.util.Collection}.
     *
     * @param fqn the qualified name of the erased parameter type
     * @return {@code true} when the type is a supported multi-value collection interface
     */
    private static boolean isSupportedCollectionFqn(String fqn) {
        return "java.util.List".equals(fqn)
                || "java.util.Set".equals(fqn)
                || "java.util.SortedSet".equals(fqn)
                || "java.util.NavigableSet".equals(fqn)
                || "java.util.Collection".equals(fqn);
    }

    // --- Security resolution helpers ---

    /**
     * Resolves security annotations from BFS-ordered interfaces for a given element (class or
     * method). Returns {@link EffectiveSecurityContract#NONE} when no security is found.
     * Emits a compile-time error when two interfaces carry conflicting security kinds.
     *
     * @param element       the element whose matching interface declaration to look up (for methods,
     *                      the corresponding interface method; for classes, the interface itself)
     * @param resourceClass the resource class used as the BFS root for interface discovery
     * @param classLevel    {@code true} when resolving class-level security
     * @return the effective security contract; never {@code null}
     */
    private EffectiveSecurityContract resolveSecurityFromInterfaces(
            javax.lang.model.element.Element element, TypeElement resourceClass, boolean classLevel) {
        EffectiveSecurityContract found = null;
        String foundInterfaceName = null;

        for (TypeElement iface : JaxRsHierarchy.allInterfaces(ctx, resourceClass)) {
            javax.lang.model.element.Element target;
            if (classLevel) {
                target = iface;
            } else {
                target = JaxRsHierarchy.findMatchingMethod(ctx, (ExecutableElement) element, iface);
                if (target == null) continue;
            }
            EffectiveSecurityContract sc = buildSecurityContract(target);
            if (sc.isEmpty()) continue;

            if (found == null) {
                found = sc;
                foundInterfaceName = iface.getQualifiedName().toString();
            } else if (!securityContractsCompatible(found, sc)) {
                // Conflict: emit error
                String contextName = classLevel
                        ? resourceClass.getSimpleName().toString()
                        : ((ExecutableElement) element).getSimpleName().toString() + "()";
                String level = classLevel ? "class-level" : "method-level";
                ctx.diagnostics()
                        .error(
                                resourceClass,
                                Diagnostics.classContractConflict(
                                        contextName,
                                        level + " security",
                                        foundInterfaceName,
                                        describeSecurityKinds(found.kinds()),
                                        iface.getQualifiedName().toString(),
                                        describeSecurityKinds(sc.kinds())));
                return EffectiveSecurityContract.NONE;
            }
            // identical or compatible — continue (first-found wins)
        }
        return found != null ? found : EffectiveSecurityContract.NONE;
    }

    /**
     * Returns {@code true} if the two security contracts are compatible (i.e. represent the same
     * effective kind set and values — no conflict).
     *
     * @param a first security contract
     * @param b second security contract
     * @return {@code true} if compatible
     */
    private boolean securityContractsCompatible(EffectiveSecurityContract a, EffectiveSecurityContract b) {
        return a.kinds().equals(b.kinds())
                && a.rolesAllowed().equals(b.rolesAllowed())
                && a.authorizedScopes().equals(b.authorizedScopes())
                && a.authorizedMatchAll() == b.authorizedMatchAll();
    }

    /**
     * Emits a warning when a direct security annotation overrides an interface declaration with a
     * different security kind.
     *
     * @param element     the element carrying the direct annotation
     * @param direct      the direct security contract
     * @param interfaces  the BFS-ordered interfaces to check
     * @param classLevel  {@code true} for class-level warnings
     */
    private void warnSecurityOverride(
            javax.lang.model.element.Element element,
            EffectiveSecurityContract direct,
            List<TypeElement> interfaces,
            boolean classLevel) {
        for (TypeElement iface : interfaces) {
            javax.lang.model.element.Element target;
            if (classLevel) {
                target = iface;
            } else {
                if (!(element instanceof ExecutableElement method)) continue;
                target = JaxRsHierarchy.findMatchingMethod(ctx, method, iface);
                if (target == null) continue;
            }
            EffectiveSecurityContract ifaceSc = buildSecurityContract(target);
            if (!ifaceSc.isEmpty() && !securityContractsCompatible(direct, ifaceSc)) {
                String contextName = classLevel
                        ? ((TypeElement) element).getSimpleName().toString()
                        : ((ExecutableElement) element).getSimpleName().toString() + "()";
                ctx.diagnostics()
                        .warning(
                                element,
                                Diagnostics.directOverridesInterfaceWarning(
                                        contextName,
                                        "security",
                                        describeSecurityKinds(direct.kinds()),
                                        iface.getQualifiedName().toString(),
                                        describeSecurityKinds(ifaceSc.kinds())));
            }
        }
    }

    /**
     * Builds an {@link EffectiveSecurityContract} from the security annotations directly present
     * on the given element.
     *
     * @param element the element to inspect
     * @return the security contract, or {@link EffectiveSecurityContract#NONE} if none present
     */
    private EffectiveSecurityContract buildSecurityContract(javax.lang.model.element.Element element) {
        boolean hasDenyAll = AnnotationMirrors.isPresent(element, JaxRsAnnotations.DENY_ALL);
        boolean hasPermitAll = AnnotationMirrors.isPresent(element, JaxRsAnnotations.PERMIT_ALL);
        boolean hasRolesAllowed = AnnotationMirrors.isPresent(element, JaxRsAnnotations.ROLES_ALLOWED);
        boolean hasAuthorized = AnnotationMirrors.isPresent(element, JaxRsAnnotations.AUTHORIZED);

        if (!hasDenyAll && !hasPermitAll && !hasRolesAllowed && !hasAuthorized) {
            return EffectiveSecurityContract.NONE;
        }

        Set<EffectiveSecurityContract.SecurityKind> kinds =
                EnumSet.noneOf(EffectiveSecurityContract.SecurityKind.class);
        if (hasDenyAll) kinds.add(EffectiveSecurityContract.SecurityKind.DENY_ALL);
        if (hasPermitAll) kinds.add(EffectiveSecurityContract.SecurityKind.PERMIT_ALL);
        if (hasRolesAllowed) kinds.add(EffectiveSecurityContract.SecurityKind.ROLES_ALLOWED);
        if (hasAuthorized) kinds.add(EffectiveSecurityContract.SecurityKind.AUTHORIZED);

        List<String> rolesAllowed = List.of();
        if (hasRolesAllowed) {
            rolesAllowed = AnnotationMirrors.findByFqn(element, JaxRsAnnotations.ROLES_ALLOWED)
                    .map(m -> ctx.annotations().attributeArray(m, "value").stream()
                            .map(av -> av.getValue().toString())
                            .toList())
                    .orElse(List.of());
        }

        List<String> scopes = List.of();
        boolean matchAll = true;
        if (hasAuthorized) {
            var authorizedMirror = AnnotationMirrors.findByFqn(element, JaxRsAnnotations.AUTHORIZED);
            if (authorizedMirror.isPresent()) {
                scopes = ctx.annotations().attributeArray(authorizedMirror.get(), "scopes").stream()
                        .map(av -> av.getValue().toString())
                        .toList();
                matchAll = ctx.annotations()
                        .attribute(authorizedMirror.get(), "matchAll", Boolean.class)
                        .orElse(true);
            }
        }

        return new EffectiveSecurityContract(Set.copyOf(kinds), rolesAllowed, scopes, matchAll);
    }

    /**
     * Returns a human-readable description of the given security kind set.
     *
     * @param kinds the security kinds
     * @return a description string, e.g. {@code "@DenyAll"}
     */
    private String describeSecurityKinds(Set<EffectiveSecurityContract.SecurityKind> kinds) {
        List<String> names = new ArrayList<>();
        for (EffectiveSecurityContract.SecurityKind k : kinds) {
            names.add("@" + k.name().replace("_", ""));
        }
        return String.join(" + ", names);
    }

    // --- Annotation reading helpers ---

    /**
     * Returns the {@code String}-typed attribute value from the given annotation on the element,
     * or {@code null} if the annotation is absent.
     *
     * @param element       the element to inspect
     * @param annotationFqn the annotation FQN
     * @param attributeName the attribute name
     * @return the attribute value, or {@code null}
     */
    private String findAnnotationString(
            javax.lang.model.element.Element element, String annotationFqn, String attributeName) {
        return AnnotationMirrors.findByFqn(element, annotationFqn)
                .flatMap(m -> ctx.annotations().attribute(m, attributeName, String.class))
                .orElse(null);
    }

    /**
     * Returns the {@code String[]}-typed attribute value from the given annotation on the element
     * as a {@code List<String>}, or {@code null} if the annotation is absent.
     *
     * @param element       the element to inspect
     * @param annotationFqn the annotation FQN
     * @return the list of strings, or {@code null} if the annotation is absent
     */
    private List<String> findAnnotationStringArray(javax.lang.model.element.Element element, String annotationFqn) {
        return AnnotationMirrors.findByFqn(element, annotationFqn)
                .map(m -> ctx.annotations().attributeArray(m, "value").stream()
                        .map(av -> av.getValue().toString())
                        .toList())
                .orElse(null);
    }

    /**
     * Returns the {@code Class[]}-typed attribute value from the given annotation as a
     * {@code List<TypeMirror>}, or {@code null} if the annotation is absent.
     *
     * @param element       the element to inspect
     * @param annotationFqn the annotation FQN
     * @return the list of type mirrors, or {@code null} if the annotation is absent
     */
    private List<TypeMirror> findAnnotationClassArray(javax.lang.model.element.Element element, String annotationFqn) {
        return AnnotationMirrors.findByFqn(element, annotationFqn)
                .map(m -> {
                    List<AnnotationValue> values = ctx.annotations().attributeArray(m, "value");
                    List<TypeMirror> mirrors = new ArrayList<>();
                    for (AnnotationValue av : values) {
                        Object val = av.getValue();
                        if (val instanceof TypeMirror tm) {
                            mirrors.add(tm);
                        }
                    }
                    return mirrors.isEmpty() ? null : (List<TypeMirror>) mirrors;
                })
                .orElse(null);
    }

    /**
     * Returns the {@code @Path} value from the given element, or {@code null} if absent.
     *
     * @param element the element to inspect
     * @return the path value, or {@code null}
     */
    private String pathValue(javax.lang.model.element.Element element) {
        return AnnotationMirrors.findByFqn(element, JaxRsAnnotations.PATH)
                .flatMap(m -> ctx.annotations().attribute(m, "value", String.class))
                .orElse(null);
    }
}
