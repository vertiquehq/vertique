// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.security.authz.ActionRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Derives a tool's protocol access mode from the Jakarta base policy and Vertique action
 * annotations it resolves, exactly as REST resolves the same annotations.
 *
 * <p>Resolution runs independently for the two families — one base policy ({@code @PermitAll},
 * {@code @DenyAll}, {@code @RolesAllowed}) and one {@code @RequiresAction} — over the same ordered
 * source tiers: the concrete method, each overridden superclass method nearest-first, the unique
 * interface methods it overrides, the concrete type, each superclass type nearest-first, and finally
 * the type's interfaces. The first tier that declares a policy wins, so a method source always
 * overrides a type source. Several interface candidates in one tier must agree; incomparable
 * candidates with different policies are a compile error.
 *
 * <p>The derived wire shape is:
 *
 * <ul>
 *   <li>no base policy and no action — the existing REST {@code SecurityPolicy.None} — publishes as
 *       {@link McpAccessMode#PERMIT_ALL} with no roles and no action;</li>
 *   <li>{@code @PermitAll} publishes as {@link McpAccessMode#PERMIT_ALL};</li>
 *   <li>{@code @DenyAll} publishes as {@link McpAccessMode#DENY_ALL};</li>
 *   <li>{@code @RolesAllowed} and/or {@code @RequiresAction} publish as
 *       {@link McpAccessMode#RESTRICTED}, composing with AND when both are present.</li>
 * </ul>
 *
 * <p>{@code @RequiresAction} conflicts with {@code @PermitAll} and {@code @DenyAll}, matching REST.
 * Its value must be a canonical {@link dev.vertique.security.authz.ActionRef} string, because the
 * generated invoker emits {@code ActionRef.parse(<value>)}: a value outside that grammar would
 * otherwise compile into source that always throws at composition.
 *
 * <p>{@code @Authorized} is REST-specific and is rejected on MCP tools. That rejection runs over
 * <em>every</em> source tier, not only the direct method and declaring type — an {@code @Authorized}
 * inherited from an overridden interface method or a superclass type resolves exactly like the
 * policy families do, so accepting it would silently publish a REST-annotated declaration as an MCP
 * tool.
 */
final class McpAuthorizationAnnotationResolver {

    private final CodegenContext ctx;

    /**
     * Constructs a resolver bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpAuthorizationAnnotationResolver(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The resolved access contract of one tool.
     *
     * @param mode   the derived protocol access mode
     * @param roles  the required roles; empty unless a {@code @RolesAllowed} was resolved
     * @param action the canonical {@code @RequiresAction} value, or {@code null} when none was
     *               resolved
     */
    record Access(McpAccessMode mode, List<String> roles, String action) {

        /** Canonicalizes the record by defensively copying the role list. */
        Access {
            roles = List.copyOf(roles);
        }
    }

    /**
     * Resolves the effective access contract for one tool method, reporting every conflict as a
     * compile error.
     *
     * @param declaringType the type declaring the tool method
     * @param method        the tool method
     * @return the resolved access contract, or empty when a diagnostic was reported
     */
    Optional<Access> resolve(TypeElement declaringType, ExecutableElement method) {
        List<List<Element>> tiers = sourceTiers(declaringType, method);

        Optional<Element> authorized = tiers.stream()
                .flatMap(List::stream)
                .filter(source -> AnnotationMirrors.isPresent(source, JaxRsAnnotations.AUTHORIZED))
                .findFirst();
        if (authorized.isPresent()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@Authorized is REST-specific and is not supported on the @McpTool method %s.%s();"
                                    + " it is declared on %s — declare @RolesAllowed and/or @RequiresAction instead",
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            describe(authorized.get()));
            return Optional.empty();
        }

        BaseResolution base = resolveBase(tiers, method, declaringType);
        if (!base.valid()) {
            return Optional.empty();
        }
        ActionResolution action = resolveAction(tiers, method, declaringType);
        if (!action.valid()) {
            return Optional.empty();
        }

        if (action.value() != null && (base.kind() == BaseKind.PERMIT_ALL || base.kind() == BaseKind.DENY_ALL)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@RequiresAction on the @McpTool method %s.%s() conflicts with %s;"
                                    + " @RequiresAction composes only with @RolesAllowed",
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            base.kind() == BaseKind.PERMIT_ALL ? "@PermitAll" : "@DenyAll");
            return Optional.empty();
        }

        return switch (base.kind()) {
            case PERMIT_ALL, NONE ->
                action.value() == null
                        ? Optional.of(new Access(McpAccessMode.PERMIT_ALL, List.of(), null))
                        : Optional.of(new Access(McpAccessMode.RESTRICTED, List.of(), action.value()));
            case DENY_ALL -> Optional.of(new Access(McpAccessMode.DENY_ALL, List.of(), null));
            case ROLES_ALLOWED -> Optional.of(new Access(McpAccessMode.RESTRICTED, base.roles(), action.value()));
        };
    }

    // --- Base policy ---

    /** The Jakarta base policy families a tool may resolve. */
    private enum BaseKind {
        NONE,
        PERMIT_ALL,
        DENY_ALL,
        ROLES_ALLOWED
    }

    /**
     * The outcome of base-policy resolution.
     *
     * @param valid {@code false} when a diagnostic was already reported
     * @param kind  the resolved policy family; {@link BaseKind#NONE} when the tool declares none
     * @param roles the roles declared by a resolved {@code @RolesAllowed}
     */
    private record BaseResolution(boolean valid, BaseKind kind, List<String> roles) {

        static BaseResolution invalid() {
            return new BaseResolution(false, BaseKind.NONE, List.of());
        }

        static BaseResolution none() {
            return new BaseResolution(true, BaseKind.NONE, List.of());
        }
    }

    /**
     * The outcome of {@code @RequiresAction} resolution.
     *
     * @param valid {@code false} when a diagnostic was already reported
     * @param value the resolved canonical action value, or {@code null} when none was declared
     */
    private record ActionResolution(boolean valid, String value) {

        static ActionResolution invalid() {
            return new ActionResolution(false, null);
        }

        static ActionResolution none() {
            return new ActionResolution(true, null);
        }
    }

    private BaseResolution resolveBase(List<List<Element>> tiers, ExecutableElement method, TypeElement declaringType) {
        for (List<Element> tier : tiers) {
            List<BaseResolution> resolved = new ArrayList<>();
            for (Element source : tier) {
                BaseResolution single = readBase(source, method, declaringType);
                if (!single.valid()) {
                    return BaseResolution.invalid();
                }
                if (single.kind() != BaseKind.NONE) {
                    resolved.add(single);
                }
            }
            if (resolved.isEmpty()) {
                continue;
            }
            BaseResolution first = resolved.get(0);
            boolean agree = resolved.stream()
                    .allMatch(r -> r.kind() == first.kind() && r.roles().equals(first.roles()));
            if (!agree) {
                ctx.diagnostics()
                        .error(
                                method,
                                "The @McpTool method %s.%s() inherits conflicting base security policies from"
                                        + " incomparable interfaces; declare the effective policy directly on the tool"
                                        + " method or its declaring type",
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return BaseResolution.invalid();
            }
            return first;
        }
        return BaseResolution.none();
    }

    private BaseResolution readBase(Element source, ExecutableElement method, TypeElement declaringType) {
        boolean permitAll = AnnotationMirrors.isPresent(source, JaxRsAnnotations.PERMIT_ALL);
        boolean denyAll = AnnotationMirrors.isPresent(source, JaxRsAnnotations.DENY_ALL);
        boolean rolesAllowed = AnnotationMirrors.isPresent(source, JaxRsAnnotations.ROLES_ALLOWED);

        if (countTrue(permitAll, denyAll, rolesAllowed) > 1) {
            ctx.diagnostics()
                    .error(
                            source,
                            "Conflicting base security policies on %s for the @McpTool method %s.%s();"
                                    + " declare exactly one of @DenyAll, @PermitAll, or @RolesAllowed",
                            source.getSimpleName(),
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return BaseResolution.invalid();
        }
        if (permitAll) {
            return new BaseResolution(true, BaseKind.PERMIT_ALL, List.of());
        }
        if (denyAll) {
            return new BaseResolution(true, BaseKind.DENY_ALL, List.of());
        }
        if (!rolesAllowed) {
            return BaseResolution.none();
        }
        List<String> roles = roles(source);
        if (roles.isEmpty()) {
            ctx.diagnostics()
                    .error(
                            source,
                            "@RolesAllowed on %s declares no role for the @McpTool method %s.%s();"
                                    + " use @DenyAll to deny access or name at least one role",
                            source.getSimpleName(),
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return BaseResolution.invalid();
        }
        return new BaseResolution(true, BaseKind.ROLES_ALLOWED, roles);
    }

    private ActionResolution resolveAction(
            List<List<Element>> tiers, ExecutableElement method, TypeElement declaringType) {
        for (List<Element> tier : tiers) {
            List<String> declared = tier.stream()
                    .map(source -> AnnotationMirrors.findByFqn(source, JaxRsAnnotations.REQUIRES_ACTION))
                    .flatMap(Optional::stream)
                    .map(this::actionValue)
                    .toList();
            if (declared.isEmpty()) {
                continue;
            }
            if (declared.stream().anyMatch(value -> value == null || value.isBlank())) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@RequiresAction on the @McpTool method %s.%s() declares a blank action",
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return ActionResolution.invalid();
            }
            if (declared.stream().distinct().count() > 1) {
                ctx.diagnostics()
                        .error(
                                method,
                                "The @McpTool method %s.%s() inherits conflicting @RequiresAction declarations from"
                                        + " incomparable interfaces; declare the effective action directly on the tool"
                                        + " method or its declaring type",
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return ActionResolution.invalid();
            }
            String action = declared.get(0);
            // The generated invoker emits ActionRef.parse(action), so anything ActionRef rejects would
            // compile into source that always throws at composition. Parse it here instead.
            try {
                ActionRef.parse(action);
            } catch (IllegalArgumentException e) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@RequiresAction(\"%s\") on the @McpTool method %s.%s() is not a canonical action:"
                                        + " %s. Declare exactly three dot-separated segments, each matching"
                                        + " ^[a-z][a-z0-9]*$ — for example \"weather.city.read\"",
                                action,
                                declaringType.getSimpleName(),
                                method.getSimpleName(),
                                e.getMessage());
                return ActionResolution.invalid();
            }
            return new ActionResolution(true, action);
        }
        return ActionResolution.none();
    }

    // --- Source tiers ---

    /**
     * Builds the ordered policy source tiers for one tool method. Every tier is resolved before the
     * next one is considered; a tier with more than one element holds incomparable interface
     * candidates that must agree.
     *
     * @param declaringType the type declaring the tool method
     * @param method        the tool method
     * @return the ordered tiers, method sources before type sources
     */
    private List<List<Element>> sourceTiers(TypeElement declaringType, ExecutableElement method) {
        List<List<Element>> tiers = new ArrayList<>();
        tiers.add(List.of(method));

        List<TypeElement> superclasses = superclasses(declaringType);
        for (TypeElement superclass : superclasses) {
            overriddenIn(superclass, method, declaringType).ifPresent(m -> tiers.add(List.of(m)));
        }

        List<TypeElement> interfaces = interfaces(declaringType);
        List<Element> interfaceMethods = interfaces.stream()
                .map(candidate -> overriddenIn(candidate, method, declaringType))
                .flatMap(Optional::stream)
                .map(Element.class::cast)
                .toList();
        if (!interfaceMethods.isEmpty()) {
            tiers.add(interfaceMethods);
        }

        tiers.add(List.of(declaringType));
        superclasses.forEach(superclass -> tiers.add(List.of(superclass)));
        if (!interfaces.isEmpty()) {
            tiers.add(interfaces.stream().map(Element.class::cast).toList());
        }
        return tiers;
    }

    /**
     * Returns the superclass chain of the given type, nearest first and excluding
     * {@code java.lang.Object}.
     *
     * @param type the type to walk
     * @return the superclass chain, nearest first
     */
    private List<TypeElement> superclasses(TypeElement type) {
        List<TypeElement> chain = new ArrayList<>();
        TypeMirror current = type.getSuperclass();
        while (current != null && current.getKind() == TypeKind.DECLARED) {
            Element element = ((DeclaredType) current).asElement();
            if (!(element instanceof TypeElement superclass)
                    || Object.class.getName().contentEquals(superclass.getQualifiedName())) {
                break;
            }
            chain.add(superclass);
            current = superclass.getSuperclass();
        }
        return chain;
    }

    /**
     * Returns every interface reachable from the given type, in breadth-first discovery order.
     *
     * @param type the type to walk
     * @return the reachable interfaces
     */
    private List<TypeElement> interfaces(TypeElement type) {
        Set<TypeElement> found = new LinkedHashSet<>();
        ctx.typeResolver()
                .allSupertypes(type.asType())
                .map(mirror -> ctx.asTypeElement(mirror).orElse(null))
                .filter(element -> element != null && element.getKind() == ElementKind.INTERFACE)
                .forEach(found::add);
        return List.copyOf(found);
    }

    /**
     * Returns the declaration in {@code owner} that {@code method} overrides, if any.
     *
     * @param owner         the supertype to search
     * @param method        the overriding tool method
     * @param declaringType the type the override is viewed from
     * @return the overridden declaration, or empty when {@code owner} declares none
     */
    private Optional<ExecutableElement> overriddenIn(
            TypeElement owner, ExecutableElement method, TypeElement declaringType) {
        return owner.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.METHOD)
                .map(ExecutableElement.class::cast)
                .filter(candidate -> ctx.elements().overrides(method, candidate, declaringType))
                .findFirst();
    }

    /**
     * Describes a policy source element for a diagnostic, so a rejection names the declaration the
     * offending annotation actually sits on rather than only the tool it was inherited by.
     *
     * @param source the policy source element
     * @return {@code Owner.method()} for a method source, the qualified name for a type source, and
     *         the simple name otherwise
     */
    private static String describe(Element source) {
        if (source instanceof ExecutableElement executable) {
            return executable.getEnclosingElement().getSimpleName() + "." + executable.getSimpleName() + "()";
        }
        if (source instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        return source.getSimpleName().toString();
    }

    // --- Attribute reading ---

    private List<String> roles(Element source) {
        return AnnotationMirrors.findByFqn(source, JaxRsAnnotations.ROLES_ALLOWED)
                .map(mirror -> ctx.annotations().attributeArray(mirror, "value").stream()
                        .map(AnnotationValue::getValue)
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(role -> !role.isBlank())
                        .toList())
                .orElseGet(List::of);
    }

    private String actionValue(AnnotationMirror mirror) {
        return ctx.annotations().attribute(mirror, "value", String.class).orElse(null);
    }

    private static int countTrue(boolean... flags) {
        int count = 0;
        for (boolean flag : flags) {
            if (flag) {
                count++;
            }
        }
        return count;
    }
}
