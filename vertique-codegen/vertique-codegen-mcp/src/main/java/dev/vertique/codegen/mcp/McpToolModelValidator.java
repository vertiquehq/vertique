// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.support.Identifiers;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import dev.vertique.mcp.tool.McpToolDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates {@code @McpTool} declarations and builds the {@link McpToolModel} the emitters consume.
 *
 * <p>Validation is fail-fast per declaration: the first boundary a tool violates produces exactly
 * one targeted diagnostic and no model, so a rejected tool never reaches emission and never produces
 * a second, derived complaint. The boundaries are:
 *
 * <ul>
 *   <li><strong>Direct invocability</strong> — the tool method is public, concrete, and an instance
 *       method on a Dagger-managed type with exactly one {@code @Inject} constructor. There is no
 *       reflective fallback, so anything the generated invoker cannot call directly, or the
 *       generated module cannot wire, is a compile error.</li>
 *   <li><strong>Protocol metadata</strong> — unique tool names matching
 *       {@code [A-Za-z0-9_.-]{1,128}}, a non-blank bounded description, and an omitted-when-blank
 *       title.</li>
 *   <li><strong>Honest type contracts</strong> — no raw, wildcard, type-variable, or unresolved type
 *       in a parameter or result, no {@code void} result, and no input member without a JSON schema
 *       representation.</li>
 *   <li><strong>Effective JSON profile</strong> — resolved method-over-type, with a blank id
 *       rejected at compile time rather than surfacing as an unresolvable mapper at composition.</li>
 * </ul>
 *
 * <p>Output-schema synthesis is a composition-time concern: a result type that is structurally valid
 * here but cannot be synthesized by the shared runtime schema generator fails startup, per
 * FR-MCP-124.
 */
final class McpToolModelValidator {

    /** The protocol tool-name grammar, mirroring {@link McpToolDescriptor}'s own bound. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    /** Scalar types with a direct JSON schema representation. */
    private static final Set<String> SCALARS = Set.of(
            "java.lang.String",
            "java.lang.CharSequence",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character",
            "java.lang.Number",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.UUID",
            "java.util.Currency",
            "java.util.Locale",
            "java.net.URI",
            "java.time.Duration",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.MonthDay",
            "java.time.OffsetDateTime",
            "java.time.OffsetTime",
            "java.time.Period",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.ZoneId",
            "java.time.ZoneOffset",
            "java.time.ZonedDateTime");

    /** Container types whose JSON schema representation is derived from their type arguments. */
    private static final Set<String> CONTAINERS = Set.of(
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.NavigableSet",
            "java.util.Optional",
            "java.util.Set",
            "java.util.SortedSet");

    /** Map types whose JSON schema representation is an object keyed by the first type argument. */
    private static final Set<String> MAPS = Set.of("java.util.Map", "java.util.NavigableMap", "java.util.SortedMap");

    /**
     * Package prefixes owned by the platform, the runtime, or a serialization library. A type in one
     * of these that is not an explicitly supported scalar, container, or map is not an application
     * DTO and has no JSON schema representation.
     */
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "com.fasterxml.jackson.",
            "com.sun.",
            "io.netty.",
            "io.vertx.",
            "jakarta.",
            "java.",
            "javax.",
            "jdk.",
            "org.reactivestreams.",
            "reactor.",
            "sun.",
            "tools.jackson.");

    private final CodegenContext ctx;
    private final McpAuthorizationAnnotationResolver authorization;
    private final InjectConstructorValidator injectConstructors;

    /**
     * Constructs a validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    McpToolModelValidator(CodegenContext ctx) {
        this.ctx = ctx;
        this.authorization = new McpAuthorizationAnnotationResolver(ctx);
        this.injectConstructors = new InjectConstructorValidator(ctx);
    }

    // --- Declaring type ---

    /**
     * Validates the concerns a declaring type owns once, regardless of how many tools it declares:
     * it must be a concrete class the generated module can wire, and its tool methods must not be
     * overloads (their simple names become generated invoker class names).
     *
     * @param declaringType the type declaring the tool methods
     * @param toolMethods   every {@code @McpTool} method declared by that type
     * @return {@code true} when the type may contribute tools
     */
    boolean validateDeclaringType(TypeElement declaringType, List<ExecutableElement> toolMethods) {
        if (declaringType.getKind() != ElementKind.CLASS) {
            ctx.diagnostics()
                    .error(
                            declaringType,
                            "@McpTool must be declared on a concrete Dagger-managed class; %s is a %s",
                            declaringType.getQualifiedName(),
                            declaringType.getKind().toString().toLowerCase(Locale.ROOT));
            return false;
        }
        if (declaringType.getModifiers().contains(Modifier.ABSTRACT)) {
            ctx.diagnostics()
                    .error(
                            declaringType,
                            "@McpTool must be declared on a concrete Dagger-managed class; %s is abstract",
                            declaringType.getQualifiedName());
            return false;
        }
        if (declaringType.getNestingKind().isNested()
                && !declaringType.getModifiers().contains(Modifier.STATIC)) {
            ctx.diagnostics()
                    .error(
                            declaringType,
                            "@McpTool must be declared on a top-level or static nested type; %s is an inner class",
                            declaringType.getQualifiedName());
            return false;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (ExecutableElement method : toolMethods) {
            if (!seen.add(method.getSimpleName().toString())) {
                ctx.diagnostics()
                        .error(
                                method,
                                "Overloaded @McpTool method %s.%s(); a tool method name must be unique within its"
                                        + " declaring type because it names the generated invoker",
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return false;
            }
        }

        return injectConstructors.validate(declaringType);
    }

    // --- Tool declaration ---

    /**
     * Validates one {@code @McpTool} method and builds its model.
     *
     * @param declaringType the type declaring the tool method
     * @param method        the annotated tool method
     * @param mirror        the {@code @McpTool} mirror already resolved for the method
     * @return the validated model, or empty when a diagnostic was reported
     */
    Optional<McpToolModel> validate(TypeElement declaringType, ExecutableElement method, AnnotationMirror mirror) {
        if (!validateInvocable(declaringType, method)) {
            return Optional.empty();
        }

        String toolName = attribute(mirror, "name").orElse("");
        if (!TOOL_NAME.matcher(toolName).matches()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool name '%s' on %s.%s() must match %s",
                            toolName,
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            TOOL_NAME.pattern());
            return Optional.empty();
        }
        String description = attribute(mirror, "description").orElse("");
        if (description.isBlank()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool '%s' on %s.%s() must declare a non-blank description",
                            toolName,
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return Optional.empty();
        }
        if (description.length() > McpToolDescriptor.MAX_DESCRIPTION_CHARS) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool '%s' on %s.%s() has a description of %d characters; the protocol bound is %d",
                            toolName,
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            description.length(),
                            McpToolDescriptor.MAX_DESCRIPTION_CHARS);
            return Optional.empty();
        }
        String title =
                attribute(mirror, "title").filter(value -> !value.isBlank()).orElse(null);

        Optional<McpToolReturnModel> returnModel = validateReturn(declaringType, method);
        if (returnModel.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<McpToolParameterModel>> parameters = validateParameters(declaringType, method);
        if (parameters.isEmpty()) {
            return Optional.empty();
        }
        Optional<Optional<String>> jsonProfile = resolveJsonProfile(declaringType, method);
        if (jsonProfile.isEmpty()) {
            return Optional.empty();
        }
        Optional<McpAuthorizationAnnotationResolver.Access> access = authorization.resolve(declaringType, method);
        if (access.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new McpToolModel(
                declaringType,
                method,
                toolName,
                title,
                description,
                flag(mirror, "readOnlyHint", false),
                flag(mirror, "destructiveHint", true),
                flag(mirror, "idempotentHint", false),
                flag(mirror, "openWorldHint", true),
                access.get().mode(),
                access.get().roles(),
                access.get().action(),
                jsonProfile.get().orElse(null),
                parameters.get(),
                returnModel.get()));
    }

    /**
     * Reports every tool name declared more than once across the compilation unit, naming the
     * colliding name exactly once per collision.
     *
     * @param models every validated model
     * @return {@code true} when all tool names are unique
     */
    boolean validateUniqueNames(List<McpToolModel> models) {
        Map<String, List<McpToolModel>> byName = new LinkedHashMap<>();
        models.forEach(model -> byName.computeIfAbsent(model.toolName(), name -> new ArrayList<>())
                .add(model));

        boolean unique = true;
        for (Map.Entry<String, List<McpToolModel>> entry : byName.entrySet()) {
            List<McpToolModel> colliding = entry.getValue();
            if (colliding.size() < 2) {
                continue;
            }
            unique = false;
            ctx.diagnostics()
                    .error(
                            colliding.get(1).method(),
                            "Duplicate @McpTool name '%s' declared by %s; tool names must be unique",
                            entry.getKey(),
                            colliding.stream()
                                    .map(model -> model.declaringType().getSimpleName() + "."
                                            + model.method().getSimpleName() + "()")
                                    .reduce((a, b) -> a + " and " + b)
                                    .orElse(""));
        }
        return unique;
    }

    // --- Invocability ---

    private boolean validateInvocable(TypeElement declaringType, ExecutableElement method) {
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must be public: the generated invoker calls it directly, and no"
                                    + " reflective fallback exists",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        if (method.getModifiers().contains(Modifier.STATIC)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must be an instance method on a Dagger-managed type",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        if (method.getModifiers().contains(Modifier.ABSTRACT)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must be concrete; an abstract tool method cannot be invoked"
                                    + " directly",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        if (!method.getTypeParameters().isEmpty()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must not declare type parameters; a generic tool method has no"
                                    + " resolvable schema",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        return true;
    }

    // --- Result contract ---

    private Optional<McpToolReturnModel> validateReturn(TypeElement declaringType, ExecutableElement method) {
        TypeMirror declared = method.getReturnType();
        if (declared.getKind() == TypeKind.VOID) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must not return void; a tool must produce result content",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return Optional.empty();
        }
        if (!validateResultShape(declaringType, method, declared)) {
            return Optional.empty();
        }

        TypeMirror unwrapped = declared;
        boolean asynchronous = false;
        if (erasureIs(unwrapped, McpToolProcessor.FUTURE)) {
            asynchronous = true;
            unwrapped = ((DeclaredType) unwrapped).getTypeArguments().get(0);
        }

        boolean handlerAuthored = erasureIs(unwrapped, McpToolProcessor.MCP_TOOL_RESULT);
        TypeMirror resultType =
                handlerAuthored ? ((DeclaredType) unwrapped).getTypeArguments().get(0) : unwrapped;

        if (!handlerAuthored && erasureIs(resultType, Void.class.getName())) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() must not return a void result; use McpToolResult<Void> for a"
                                    + " text-only tool",
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return Optional.empty();
        }

        McpToolReturnModel.Shape shape;
        if (handlerAuthored) {
            shape = asynchronous ? McpToolReturnModel.Shape.FUTURE_TOOL_RESULT : McpToolReturnModel.Shape.TOOL_RESULT;
        } else {
            shape = asynchronous ? McpToolReturnModel.Shape.FUTURE_VALUE : McpToolReturnModel.Shape.VALUE;
        }
        return Optional.of(new McpToolReturnModel(declared, resultType, shape));
    }

    /**
     * Rejects every result contract with no honest schema: an unknown type, a type variable, a
     * raw type, or a wildcard anywhere in the declared result.
     */
    private boolean validateResultShape(TypeElement declaringType, ExecutableElement method, TypeMirror declared) {
        Optional<TypeMirror> unresolved = firstUnresolved(declared);
        if (unresolved.isPresent()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "Unknown type %s in the result of @McpTool method %s(); the type is not on the"
                                    + " compilation classpath",
                            unresolved.get(),
                            method.getSimpleName());
            return false;
        }
        if (containsTypeVariable(declared)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() has an unbound type variable in its result %s",
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            declared);
            return false;
        }
        Optional<TypeMirror> raw = firstRaw(declared);
        if (raw.isPresent()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() has the raw result type %s; a raw type has no honest output"
                                    + " schema — declare a concrete type argument",
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            ctx.types().erasure(raw.get()));
            return false;
        }
        if (containsWildcard(declared)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@McpTool method %s.%s() has the wildcard result type %s; a wildcard has no honest output"
                                    + " schema — declare a concrete type argument",
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            declared);
            return false;
        }
        return true;
    }

    // --- Input contract ---

    private Optional<List<McpToolParameterModel>> validateParameters(
            TypeElement declaringType, ExecutableElement method) {
        List<McpToolParameterModel> parameters = new ArrayList<>();
        Set<String> protocolNames = new LinkedHashSet<>();
        Set<String> componentNames = new LinkedHashSet<>();

        for (VariableElement parameter : method.getParameters()) {
            TypeMirror type = parameter.asType();
            if (erasureIs(type, McpToolProcessor.MCP_CANCELLATION_SIGNAL)) {
                parameters.add(new McpToolParameterModel(
                        parameter,
                        "",
                        uniqueComponentName(parameter.getSimpleName().toString(), componentNames),
                        "",
                        type,
                        true));
                continue;
            }
            if (!validateParameterType(declaringType, method, parameter, type)) {
                return Optional.empty();
            }
            Optional<AnnotationMirror> paramMirror =
                    AnnotationMirrors.findByFqn(parameter, McpToolProcessor.MCP_TOOL_PARAM);
            if (paramMirror.isEmpty()) {
                ctx.diagnostics()
                        .error(
                                parameter,
                                "Parameter '%s' of @McpTool method %s.%s() must be annotated with @McpToolParam;"
                                        + " protocol names and descriptions are explicit",
                                parameter.getSimpleName(),
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return Optional.empty();
            }
            String protocolName = attribute(paramMirror.get(), "name")
                    .filter(value -> !value.isBlank())
                    .orElseGet(() -> parameter.getSimpleName().toString());
            String description = attribute(paramMirror.get(), "description").orElse("");
            if (description.isBlank()) {
                ctx.diagnostics()
                        .error(
                                parameter,
                                "@McpToolParam '%s' of @McpTool method %s.%s() must declare a non-blank description",
                                protocolName,
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return Optional.empty();
            }
            if (!protocolNames.add(protocolName)) {
                ctx.diagnostics()
                        .error(
                                parameter,
                                "Ambiguous parameter name '%s' on @McpTool method %s.%s(); every input member needs a"
                                        + " unique protocol name",
                                protocolName,
                                declaringType.getSimpleName(),
                                method.getSimpleName());
                return Optional.empty();
            }
            parameters.add(new McpToolParameterModel(
                    parameter,
                    protocolName,
                    uniqueComponentName(protocolName, componentNames),
                    description,
                    type,
                    false));
        }
        return Optional.of(List.copyOf(parameters));
    }

    private boolean validateParameterType(
            TypeElement declaringType, ExecutableElement method, VariableElement parameter, TypeMirror type) {
        Optional<TypeMirror> unresolved = firstUnresolved(type);
        if (unresolved.isPresent()) {
            ctx.diagnostics()
                    .error(
                            parameter,
                            "Unknown type %s of parameter '%s' on @McpTool method %s(); the type is not on the"
                                    + " compilation classpath",
                            unresolved.get(),
                            parameter.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        if (containsTypeVariable(type)) {
            ctx.diagnostics()
                    .error(
                            parameter,
                            "Parameter '%s' on @McpTool method %s.%s() has an unbound type variable in %s",
                            parameter.getSimpleName(),
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            type);
            return false;
        }
        Optional<TypeMirror> raw = firstRaw(type);
        if (raw.isPresent()) {
            ctx.diagnostics()
                    .error(
                            parameter,
                            "Parameter '%s' on @McpTool method %s.%s() uses the raw type %s; a raw type has no honest"
                                    + " input schema — declare a concrete type argument",
                            parameter.getSimpleName(),
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            ctx.types().erasure(raw.get()));
            return false;
        }
        if (containsWildcard(type)) {
            ctx.diagnostics()
                    .error(
                            parameter,
                            "Parameter '%s' on @McpTool method %s.%s() uses the wildcard type %s; a wildcard has no"
                                    + " honest input schema — declare a concrete type argument",
                            parameter.getSimpleName(),
                            declaringType.getSimpleName(),
                            method.getSimpleName(),
                            type);
            return false;
        }
        Optional<TypeMirror> unsupported = firstUnrepresentable(type);
        if (unsupported.isPresent()) {
            ctx.diagnostics()
                    .error(
                            parameter,
                            "Parameter '%s' of type %s on @McpTool method %s.%s() has no JSON schema representation;"
                                    + " tool inputs must be JSON-representable types",
                            parameter.getSimpleName(),
                            unsupported.get(),
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return false;
        }
        return true;
    }

    // --- Effective JSON profile ---

    /**
     * Resolves the effective {@code @JsonProfile} id, method-over-type.
     *
     * @return an outer empty when a diagnostic was reported; otherwise the resolved id, itself empty
     *         when the tool declares no profile and composition selects the boundary/global default
     */
    private Optional<Optional<String>> resolveJsonProfile(TypeElement declaringType, ExecutableElement method) {
        Optional<AnnotationMirror> mirror = AnnotationMirrors.findByFqn(method, McpToolProcessor.JSON_PROFILE);
        String level = "method";
        if (mirror.isEmpty()) {
            mirror = AnnotationMirrors.findByFqn(declaringType, McpToolProcessor.JSON_PROFILE);
            level = "type";
        }
        if (mirror.isEmpty()) {
            return Optional.of(Optional.empty());
        }
        String profile = attribute(mirror.get(), "value").orElse("");
        if (profile.isBlank()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@JsonProfile at %s level for @McpTool method %s.%s() is blank; a profile id must name a"
                                    + " registered profile",
                            level,
                            declaringType.getSimpleName(),
                            method.getSimpleName());
            return Optional.empty();
        }
        return Optional.of(Optional.of(profile));
    }

    // --- Type helpers ---

    /**
     * Returns the first type in the given type tree that has no JSON schema representation.
     *
     * <p>Primitives, the supported scalars, enums, arrays, the supported containers and maps, and
     * application types all have one. A platform, runtime, or serialization-library type that is not
     * explicitly supported does not.
     *
     * @param type the type to inspect
     * @return the first unrepresentable type, or empty when the whole tree is representable
     */
    private Optional<TypeMirror> firstUnrepresentable(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return Optional.empty();
        }
        if (type instanceof ArrayType array) {
            return firstUnrepresentable(array.getComponentType());
        }
        if (!(type instanceof DeclaredType declared)) {
            return Optional.of(type);
        }
        Element element = declared.asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return Optional.empty();
        }
        String fqn = ctx.types().erasure(declared).toString();
        if (SCALARS.contains(fqn)) {
            return Optional.empty();
        }
        if (CONTAINERS.contains(fqn) || MAPS.contains(fqn)) {
            return declared.getTypeArguments().stream()
                    .map(this::firstUnrepresentable)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        if (PLATFORM_PREFIXES.stream().anyMatch(fqn::startsWith)) {
            return Optional.of(declared);
        }
        return Optional.empty();
    }

    private Optional<TypeMirror> firstUnresolved(TypeMirror type) {
        if (type.getKind() == TypeKind.ERROR) {
            return Optional.of(type);
        }
        if (type instanceof ArrayType array) {
            return firstUnresolved(array.getComponentType());
        }
        if (type instanceof DeclaredType declared) {
            return declared.getTypeArguments().stream()
                    .map(this::firstUnresolved)
                    .flatMap(Optional::stream)
                    .findFirst();
        }
        return Optional.empty();
    }

    private Optional<TypeMirror> firstRaw(TypeMirror type) {
        if (type instanceof ArrayType array) {
            return firstRaw(array.getComponentType());
        }
        if (!(type instanceof DeclaredType declared)) {
            return Optional.empty();
        }
        if (declared.asElement() instanceof TypeElement element
                && !element.getTypeParameters().isEmpty()
                && declared.getTypeArguments().isEmpty()) {
            return Optional.of(declared);
        }
        return declared.getTypeArguments().stream()
                .map(this::firstRaw)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean containsWildcard(TypeMirror type) {
        if (type.getKind() == TypeKind.WILDCARD) {
            return true;
        }
        if (type instanceof ArrayType array) {
            return containsWildcard(array.getComponentType());
        }
        return type instanceof DeclaredType declared
                && declared.getTypeArguments().stream().anyMatch(this::containsWildcard);
    }

    private boolean containsTypeVariable(TypeMirror type) {
        if (type.getKind() == TypeKind.TYPEVAR) {
            return true;
        }
        if (type instanceof ArrayType array) {
            return containsTypeVariable(array.getComponentType());
        }
        return type instanceof DeclaredType declared
                && declared.getTypeArguments().stream().anyMatch(this::containsTypeVariable);
    }

    private boolean erasureIs(TypeMirror type, String fqn) {
        return type instanceof DeclaredType declared
                && ctx.types().erasure(declared).toString().equals(fqn);
    }

    // --- Attribute helpers ---

    private Optional<String> attribute(AnnotationMirror mirror, String name) {
        return ctx.annotations().attribute(mirror, name, String.class);
    }

    private boolean flag(AnnotationMirror mirror, String name, boolean fallback) {
        return ctx.annotations().attribute(mirror, name, Boolean.class).orElse(fallback);
    }

    private static String uniqueComponentName(String source, Set<String> used) {
        String base = Identifiers.sanitize(source);
        String candidate = base;
        int ordinal = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + ordinal++;
        }
        return candidate;
    }
}
