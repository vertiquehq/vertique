// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Discovers and validates {@code @RestApplication} declarations: interfaces that each declare one
 * named REST application.
 *
 * <p>Scanning happens in two phases, because the second phase needs the generated module's package
 * (resolved by {@link JaxRsPipelineProcessor} from the compilation unit's DI-eligible resources
 * when it has any, and otherwise from its applications and declarations):
 *
 * <ol>
 *   <li>{@link #scan(RoundEnvironment, CodegenContext)} walks the round's root elements, top-level
 *       and nested at any depth, for types annotated {@code @RestApplication}. A type annotated
 *       {@code @NoAutoWire} is inert: it is checked first, gets one warning naming it, and is
 *       neither registered nor validated, nor counted by the compilation-unit checks. Any other
 *       annotated type that is not an interface (a class, enum, record, or annotation type) is a
 *       compile error. The remaining declaring interfaces are returned in fully-qualified-name
 *       order.</li>
 *   <li>{@link #validate(List, String, boolean, EffectiveJaxRsContractResolver, CodegenContext)}
 *       checks each declaration's name, path, membership, listed resources, and accessibility, then
 *       the compilation-unit rules (unique names, and discovery only for a unit's sole
 *       declaration), and returns the declarations to register.</li>
 * </ol>
 *
 * <p><strong>Compile-time rules.</strong> Every rule violation is a compile error attributed to
 * the declaring interface and naming it by binary name ({@code pkg.Outer$Inner} for a nested
 * interface):
 *
 * <ul>
 *   <li><strong>Name</strong> — {@code name} matches {@code [a-z0-9][a-z0-9_-]{0,63}} and is neither
 *       of the reserved names {@code none} and {@code null}.</li>
 *   <li><strong>Path</strong> — {@code path} is normalized by
 *       {@link ApplicationPathGrammar#normalize(String)} and rejected by the first rule
 *       {@link ApplicationPathGrammar#violatedRule(String)} reports; the error carries the value as
 *       written and the rule.</li>
 *   <li><strong>Membership</strong> — exactly one of a non-empty {@code resources} list and
 *       {@code discover = true}.</li>
 *   <li><strong>Listed resources</strong> — each entry is a concrete class with an effective
 *       {@code @Path} ({@link EffectiveJaxRsContractResolver#hasEffectivePath(TypeElement)}), and
 *       is not an interface, an abstract class, a provider ({@code @jakarta.ws.rs.ext.Provider}),
 *       a {@code jakarta.ws.rs.core.Feature}, or a {@code jakarta.ws.rs.container.DynamicFeature};
 *       no entry is listed twice. Each error names the declaration and the entry.</li>
 *   <li><strong>Accessibility</strong> — the declaring interface and every listed resource class
 *       are accessible from the generated module's package, because the emitted registration names
 *       each by its class literal: the type and each of its enclosing types is {@code public}, or
 *       not {@code private} and declared in the module's package. When no module package could be
 *       resolved, a type is accessible only when it and every enclosing type are
 *       {@code public}.</li>
 *   <li><strong>Unique names</strong> — no two declarations of one compilation unit share a name,
 *       whether or not either carries {@code @ConditionalOnProperty}; the error names both.</li>
 *   <li><strong>Discovery</strong> — a declaration setting {@code discover = true} fails when the
 *       compilation unit holds another {@code @RestApplication} declaration, active or not.</li>
 * </ul>
 *
 * <p>A declaration with any error is not registered. Under
 * {@code -Avertique.codegen.autoWire=false} every declaration is still validated, none is
 * registered, and each gets one warning naming it.
 *
 * <p>Since {@code vertique-rest-jaxrs} and {@code jakarta.ws.rs-api} are test-scope dependencies
 * of this module, {@code dev.vertique.rest.jaxrs.application.RestApplication},
 * {@code dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration}, and the JAX-RS
 * provider and feature types are located by fully-qualified name rather than imported.
 */
public final class RestApplicationScanner {

    /** FQN of {@code dev.vertique.rest.jaxrs.application.RestApplication}. */
    static final String REST_APPLICATION_FQN = "dev.vertique.rest.jaxrs.application.RestApplication";

    /** FQN of {@code dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration}. */
    private static final String REGISTRATION_FQN =
            "dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration";

    /** FQN of {@code jakarta.ws.rs.ext.Provider}. */
    private static final String PROVIDER_FQN = "jakarta.ws.rs.ext.Provider";

    /** FQN of {@code jakarta.ws.rs.core.Feature}. */
    private static final String FEATURE_FQN = "jakarta.ws.rs.core.Feature";

    /** FQN of {@code jakarta.ws.rs.container.DynamicFeature}. */
    private static final String DYNAMIC_FEATURE_FQN = "jakarta.ws.rs.container.DynamicFeature";

    /** The application name grammar, as stated in diagnostics. */
    private static final String NAME_GRAMMAR = "[a-z0-9][a-z0-9_-]{0,63}";

    /** The longest application name the grammar admits. */
    private static final int MAX_NAME_LENGTH = 64;

    /** Names the grammar admits but no application may use. */
    private static final Set<String> RESERVED_NAMES = Set.of("none", "null");

    private RestApplicationScanner() {}

    // --- Public API ---

    /**
     * A validated declaration, ready for registration emission.
     *
     * @param type           the declaring interface
     * @param name           the application name
     * @param normalizedPath the normalized application path
     * @param resources      the listed resource classes, in the order written; empty when
     *                       {@code discover} is {@code true}
     * @param discover       {@code true} when the application discovers its resources at startup
     * @param openapiPath    the contract location, as written; empty for the global default
     */
    public record Registration(
            TypeElement type,
            String name,
            String normalizedPath,
            List<TypeElement> resources,
            boolean discover,
            String openapiPath) {}

    /**
     * Walks the round's root elements, top-level and nested at any depth, for types annotated
     * {@code @RestApplication}, reports the opt-out warning and the declaration-form errors, and
     * returns the declaring interfaces in fully-qualified-name order.
     *
     * <p>A type annotated {@code @NoAutoWire} gets one warning naming it and is excluded, before
     * any other rule. Any other annotated type that is not an interface is a compile error and is
     * excluded. When at least one declaring interface remains but
     * {@code GeneratedRestApplicationRegistration} is not resolvable, this reports one compile
     * error naming the missing {@code vertique-rest-jaxrs} dependency and returns an empty list.
     *
     * @param roundEnv the current annotation processing round environment; must not be {@code null}
     * @param ctx      the shared codegen context; must not be {@code null}
     * @return the declaring interfaces, in fully-qualified-name order; never {@code null}
     */
    public static List<TypeElement> scan(RoundEnvironment roundEnv, CodegenContext ctx) {
        List<TypeElement> annotated = new ArrayList<>();
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof TypeElement rootType) {
                collectAnnotatedTypes(rootType, annotated);
            }
        }

        Elements elements = ctx.elements();
        List<TypeElement> declarations = new ArrayList<>();
        for (TypeElement type : annotated) {
            if (type.getAnnotation(NoAutoWire.class) != null) {
                ctx.diagnostics()
                        .warning(
                                type,
                                format(
                                        "%s is annotated @NoAutoWire, so it is not registered as a REST"
                                                + " application and is not validated.",
                                        binaryName(type, ctx)));
                continue;
            }
            if (type.getKind() != ElementKind.INTERFACE) {
                ctx.diagnostics()
                        .error(
                                type,
                                format(
                                        "%s is annotated @RestApplication, which belongs on an interface; declare"
                                                + " the application on an interface instead of this %s.",
                                        binaryName(type, ctx), kindName(type)));
                continue;
            }
            declarations.add(type);
        }

        if (declarations.isEmpty()) {
            return List.of();
        }

        if (elements.getTypeElement(REGISTRATION_FQN) == null) {
            ctx.diagnostics()
                    .error(
                            null,
                            "A @RestApplication declaration was found, but 'vertique-rest-jaxrs' is not on the"
                                    + " compile classpath; add it as a dependency to generate application"
                                    + " registrations.");
            return List.of();
        }

        declarations.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        return declarations;
    }

    /**
     * Validates each declaration and the compilation-unit rules, reporting a compile error for
     * every violation, and returns the declarations to register.
     *
     * <p>Per declaration, the checks run in this order, each reporting its own error: name
     * (grammar, then reserved names), path (the first violated rule only), membership form, each
     * listed resource, and accessibility (the declaring interface, then every listed resource that
     * passed its own check). Then, over every declaration passed in, whether valid or not, active
     * or not: name uniqueness and discovery standing alone. A declaration with any error is not
     * returned.
     *
     * <p>When {@code autoWireDisabled} is {@code true}, every declaration is still validated, none
     * is returned, and each gets one warning naming it and stating it is not registered.
     *
     * @param declarations     the declaring interfaces from {@link #scan}, in fully-qualified-name
     *                         order; must not be {@code null}
     * @param modulePackage    the generated module's resolved package, or {@code null} when
     *                         auto-wiring is disabled and no package could be determined (every
     *                         checked type and its enclosing types must then be {@code public})
     * @param autoWireDisabled {@code true} when {@code -Avertique.codegen.autoWire=false} is set
     * @param resolver         the contract resolver deciding whether a listed class has an
     *                         effective {@code @Path}; must not be {@code null}
     * @param ctx              the shared codegen context; must not be {@code null}
     * @return the valid declarations' registrations, in the same order as {@code declarations};
     *     empty when {@code autoWireDisabled} is {@code true}; never {@code null}
     */
    public static List<Registration> validate(
            List<TypeElement> declarations,
            String modulePackage,
            boolean autoWireDisabled,
            EffectiveJaxRsContractResolver resolver,
            CodegenContext ctx) {
        List<Checked> checked = new ArrayList<>(declarations.size());
        for (TypeElement declaration : declarations) {
            checked.add(check(declaration, modulePackage, resolver, ctx));
        }
        checkUniqueNames(checked, ctx);
        checkDiscoveryStandsAlone(checked, ctx);

        List<Registration> result = new ArrayList<>();
        for (Checked declaration : checked) {
            if (autoWireDisabled) {
                ctx.diagnostics()
                        .warning(
                                declaration.type,
                                format(
                                        "%s is not registered as a REST application because"
                                                + " -Avertique.codegen.autoWire=false is set.",
                                        declaration.binaryName));
            } else if (declaration.valid) {
                result.add(new Registration(
                        declaration.type,
                        declaration.name,
                        declaration.normalizedPath,
                        List.copyOf(declaration.resources),
                        declaration.discover,
                        declaration.openapiPath));
            }
        }
        return result;
    }

    // --- Per-declaration checks ---

    /** One declaration's attributes and whether every check so far passed. */
    private static final class Checked {
        private final TypeElement type;
        private final String binaryName;
        private String name;
        private String normalizedPath;
        private final List<TypeElement> resources = new ArrayList<>();
        private boolean discover;
        private String openapiPath;
        private boolean valid = true;

        private Checked(TypeElement type, String binaryName) {
            this.type = type;
            this.binaryName = binaryName;
        }
    }

    /**
     * Reads one declaration's attributes and runs its own checks in rule order. An absent
     * {@code name} or {@code path} is javac's missing-element error, already reported against the
     * declaration, so it only invalidates the declaration here.
     */
    private static Checked check(
            TypeElement type, String modulePackage, EffectiveJaxRsContractResolver resolver, CodegenContext ctx) {
        Checked declaration = new Checked(type, binaryName(type, ctx));
        AnnotationMirror mirror =
                AnnotationMirrors.findByFqn(type, REST_APPLICATION_FQN).orElseThrow();

        AnnotationMirrors annotations = ctx.annotations();
        declaration.name = annotations.attribute(mirror, "name", String.class).orElse(null);
        String path = annotations.attribute(mirror, "path", String.class).orElse(null);
        declaration.discover =
                annotations.attribute(mirror, "discover", Boolean.class).orElse(false);
        declaration.openapiPath =
                annotations.attribute(mirror, "openapiPath", String.class).orElse("");

        checkName(declaration, ctx);
        checkPath(declaration, path, ctx);

        List<AnnotationValue> entries = annotations.attributeArray(mirror, "resources");
        checkMembershipForm(declaration, !entries.isEmpty(), ctx);
        checkResources(declaration, entries, resolver, ctx);

        checkAccessible(declaration, modulePackage, ctx);
        return declaration;
    }

    private static void checkName(Checked declaration, CodegenContext ctx) {
        String name = declaration.name;
        if (name == null) {
            declaration.valid = false;
            return;
        }
        if (!matchesNameGrammar(name)) {
            reject(
                    declaration,
                    ctx,
                    "%s has an invalid @RestApplication name \"%s\"; an application name must match %s.",
                    declaration.binaryName,
                    name,
                    NAME_GRAMMAR);
            return;
        }
        if (RESERVED_NAMES.contains(name)) {
            reject(
                    declaration,
                    ctx,
                    "%s uses the @RestApplication name \"%s\", which is reserved; none and null cannot"
                            + " name an application.",
                    declaration.binaryName,
                    name);
        }
    }

    /**
     * Returns {@code true} when {@code name} matches {@code [a-z0-9][a-z0-9_-]{0,63}}, in one pass
     * over its characters.
     */
    private static boolean matchesNameGrammar(String name) {
        int length = name.length();
        if (length == 0 || length > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            boolean separator = i > 0 && (c == '_' || c == '-');
            if (!alphanumeric && !separator) {
                return false;
            }
        }
        return true;
    }

    private static void checkPath(Checked declaration, String path, CodegenContext ctx) {
        if (path == null) {
            declaration.valid = false;
            return;
        }
        String normalized = ApplicationPathGrammar.normalize(path);
        String violatedRule = ApplicationPathGrammar.violatedRule(normalized);
        if (violatedRule != null) {
            reject(
                    declaration,
                    ctx,
                    "%s has an invalid @RestApplication path \"%s\" (rule: %s); an application path may"
                            + " contain only '/' and the characters A-Z a-z 0-9 . _ ~ -",
                    declaration.binaryName,
                    path,
                    violatedRule);
            return;
        }
        declaration.normalizedPath = normalized;
    }

    private static void checkMembershipForm(Checked declaration, boolean hasResources, CodegenContext ctx) {
        if (hasResources != declaration.discover) {
            return;
        }
        reject(
                declaration,
                ctx,
                "%s sets %s; a REST application declares exactly one of a non-empty resources list and"
                        + " discover = true.",
                declaration.binaryName,
                hasResources ? "both resources and discover = true" : "neither resources nor discover = true");
    }

    /**
     * Checks every listed entry and keeps the ones that pass, in the order written. Each failing
     * entry gets one error naming the declaration and the entry.
     */
    private static void checkResources(
            Checked declaration,
            List<AnnotationValue> entries,
            EffectiveJaxRsContractResolver resolver,
            CodegenContext ctx) {
        Set<TypeElement> seen = new LinkedHashSet<>();
        for (AnnotationValue entry : entries) {
            Object value = entry.getValue();
            if (!(value instanceof TypeMirror entryType) || entryType.getKind() != TypeKind.DECLARED) {
                reject(
                        declaration,
                        ctx,
                        "%s lists a resources entry that could not be resolved to a class; list only"
                                + " concrete @Path resource classes this compilation can reference.",
                        declaration.binaryName);
                continue;
            }
            TypeElement resource = (TypeElement) ctx.types().asElement(entryType);
            String resourceName = binaryName(resource, ctx);
            if (!seen.add(resource)) {
                reject(
                        declaration,
                        ctx,
                        "%s lists %s in resources more than once; list each resource class once.",
                        declaration.binaryName,
                        resourceName);
                continue;
            }
            String reason = resourceRejection(resource, resolver, ctx);
            if (reason != null) {
                reject(
                        declaration,
                        ctx,
                        "%s lists %s in resources, but %s; list only concrete @Path resource classes.",
                        declaration.binaryName,
                        resourceName,
                        reason);
                continue;
            }
            declaration.resources.add(resource);
        }
    }

    /**
     * Returns why {@code resource} cannot be a listed resource, or {@code null} when it is a
     * concrete class with an effective {@code @Path} that is neither a provider nor a feature.
     */
    private static String resourceRejection(
            TypeElement resource, EffectiveJaxRsContractResolver resolver, CodegenContext ctx) {
        ElementKind kind = resource.getKind();
        if (kind == ElementKind.INTERFACE || kind == ElementKind.ANNOTATION_TYPE) {
            return "it is an " + kindName(resource);
        }
        if (resource.getModifiers().contains(Modifier.ABSTRACT)) {
            return "it is an abstract class";
        }
        if (AnnotationMirrors.isPresent(resource, PROVIDER_FQN)) {
            return "it is a JAX-RS provider (@Provider)";
        }
        if (isSubtypeOf(resource, FEATURE_FQN, ctx)) {
            return "it implements " + FEATURE_FQN;
        }
        if (isSubtypeOf(resource, DYNAMIC_FEATURE_FQN, ctx)) {
            return "it implements " + DYNAMIC_FEATURE_FQN;
        }
        if (!resolver.hasEffectivePath(resource)) {
            return "it has no effective @Path, on itself or on an implemented interface";
        }
        return null;
    }

    private static boolean isSubtypeOf(TypeElement type, String supertypeFqn, CodegenContext ctx) {
        TypeElement supertype = ctx.elements().getTypeElement(supertypeFqn);
        if (supertype == null) {
            return false;
        }
        Types types = ctx.types();
        return types.isSubtype(types.erasure(type.asType()), types.erasure(supertype.asType()));
    }

    /**
     * Checks the declaring interface, then every listed resource that passed its own check,
     * against the accessibility rule, reporting one error per inaccessible type.
     */
    private static void checkAccessible(Checked declaration, String modulePackage, CodegenContext ctx) {
        TypeElement inaccessible = firstInaccessibleEnclosingType(declaration.type, modulePackage, ctx);
        if (inaccessible != null) {
            reject(declaration, ctx, "%s", inaccessibleMessage(declaration.type, inaccessible, modulePackage, ctx));
        }
        for (TypeElement resource : declaration.resources) {
            TypeElement inaccessibleResource = firstInaccessibleEnclosingType(resource, modulePackage, ctx);
            if (inaccessibleResource != null) {
                reject(
                        declaration,
                        ctx,
                        "%s lists a resource class its generated registration cannot name: %s",
                        declaration.binaryName,
                        inaccessibleMessage(resource, inaccessibleResource, modulePackage, ctx));
            }
        }
    }

    // --- Compilation-unit checks ---

    /**
     * Rejects every declaration whose name an earlier declaration of the unit already uses, naming
     * both, and invalidates both.
     */
    private static void checkUniqueNames(List<Checked> declarations, CodegenContext ctx) {
        Map<String, Checked> firstByName = new LinkedHashMap<>();
        for (Checked declaration : declarations) {
            if (declaration.name == null) {
                continue;
            }
            Checked first = firstByName.putIfAbsent(declaration.name, declaration);
            if (first == null) {
                continue;
            }
            first.valid = false;
            reject(
                    declaration,
                    ctx,
                    "%s declares the application name \"%s\", which %s also declares; application names must"
                            + " be unique within a compilation unit.",
                    declaration.binaryName,
                    declaration.name,
                    first.binaryName);
        }
    }

    /**
     * Rejects every {@code discover = true} declaration that shares its compilation unit with
     * another declaration, active or not, naming the others.
     */
    private static void checkDiscoveryStandsAlone(List<Checked> declarations, CodegenContext ctx) {
        for (Checked declaration : declarations) {
            if (!declaration.discover) {
                continue;
            }
            List<String> others = declarations.stream()
                    .filter(other -> other != declaration)
                    .map(other -> other.binaryName)
                    .toList();
            if (others.isEmpty()) {
                continue;
            }
            reject(
                    declaration,
                    ctx,
                    "%s sets discover = true, but this compilation unit declares another application (%s);"
                            + " discovery is only for a compilation unit's sole application declaration.",
                    declaration.binaryName,
                    String.join(", ", others));
        }
    }

    // --- Accessibility rule ---

    /**
     * Accessibility rule: public, or not private when {@code elementPackage} equals
     * {@code targetPackage}. {@code targetPackage} may be {@code null} (no module package could be
     * determined), in which case the package-private branch never matches, so only a
     * {@code public} element passes.
     */
    private static boolean isAccessible(Element element, String elementPackage, String targetPackage) {
        var modifiers = element.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC)) {
            return true;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        return targetPackage != null && elementPackage.equals(targetPackage);
    }

    /**
     * Returns the first type, walking from {@code type} itself outward through its enclosing
     * types, that fails {@link #isAccessible(Element, String, String)}; {@code null} when
     * {@code type} and every enclosing type are accessible. A type nested in an inaccessible
     * enclosing type cannot be named from outside that enclosing type's package, even when it is
     * itself {@code public}.
     */
    private static TypeElement firstInaccessibleEnclosingType(
            TypeElement type, String targetPackage, CodegenContext ctx) {
        String typePackage = ctx.packageNameOf(type);
        Element current = type;
        while (current instanceof TypeElement enclosingType) {
            if (!isAccessible(enclosingType, typePackage, targetPackage)) {
                return enclosingType;
            }
            current = enclosingType.getEnclosingElement();
        }
        return null;
    }

    /**
     * Builds the inaccessible-type message, naming {@code type} by binary name and distinguishing
     * three cases: the type itself is inaccessible; the type is accessible but an enclosing type is
     * not, which is named too; or no generated-module package could be resolved, in which case the
     * type and its enclosing types must be public.
     */
    private static String inaccessibleMessage(
            TypeElement type, TypeElement inaccessible, String targetPackage, CodegenContext ctx) {
        String typeName = binaryName(type, ctx);
        if (targetPackage == null) {
            return format(
                    "%s is not accessible: no generated-module package could be resolved, so it and its"
                            + " enclosing types must be public.",
                    typeName);
        }
        if (inaccessible.equals(type)) {
            return format(
                    "%s is not accessible from the generated module's package '%s'; make it public, or"
                            + " package-private in that same package.",
                    typeName, targetPackage);
        }
        return format(
                "%s is not accessible from the generated module's package '%s': its enclosing type %s is not"
                        + " public; make %s public, or package-private in that same package.",
                typeName, targetPackage, binaryName(inaccessible, ctx), inaccessible.getSimpleName());
    }

    // --- Internal helpers ---

    /** Recursively collects every type annotated {@code @RestApplication}, at any nesting depth. */
    private static void collectAnnotatedTypes(TypeElement type, List<TypeElement> out) {
        if (AnnotationMirrors.isPresent(type, REST_APPLICATION_FQN)) {
            out.add(type);
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                collectAnnotatedTypes(nested, out);
            }
        }
    }

    /**
     * Reports the formatted message as a compile error against the declaring interface and
     * invalidates the declaration. The message is formatted here and passed on verbatim, so a
     * {@code %} in a value as written is never read as a format specifier.
     */
    private static void reject(Checked declaration, CodegenContext ctx, String template, Object... args) {
        declaration.valid = false;
        ctx.diagnostics().error(declaration.type, format(template, args));
    }

    private static String format(String template, Object... args) {
        return template.formatted(args);
    }

    private static String binaryName(TypeElement type, CodegenContext ctx) {
        return ctx.elements().getBinaryName(type).toString();
    }

    /** The type's kind as prose: {@code class}, {@code enum}, {@code record}, {@code annotation type}. */
    private static String kindName(TypeElement type) {
        return type.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
