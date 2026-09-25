// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Discovers and validates eligible {@code jakarta.ws.rs.core.Application} subtypes.
 *
 * <p>Scanning happens in two phases, because the second phase needs the generated module's package
 * (resolved by {@link JaxRsPipelineProcessor} from the compilation unit's DI-eligible resources
 * when it has any, and from the eligible applications themselves otherwise):
 *
 * <ol>
 *   <li>{@link #scan(RoundEnvironment, CodegenContext)} walks the round's root elements for
 *       concrete {@code Application} subtypes, top-level or static nested at any depth. It reports
 *       the {@code @NoAutoWire} warning, the non-static-inner compile error, and the missing
 *       {@code vertique-rest-jaxrs} dependency compile error, and returns the remaining
 *       structurally-eligible candidates in fully-qualified-name order.</li>
 *   <li>{@link #validate(List, String, boolean, CodegenContext)} checks each candidate's
 *       accessibility, construction, and {@code @ApplicationPath} (all four compile-time steps
 *       of the application path grammar, via {@link ApplicationPathGrammar}), reports the
 *       corresponding compile errors, and emits one
 *       registration NOTE (auto-wiring enabled) or one {@code autoWire=false} WARNING (auto-wiring
 *       disabled) per successfully validated application.</li>
 * </ol>
 *
 * <p><strong>No resolvable module package.</strong> When auto-wiring is disabled and no module is
 * written, {@link JaxRsPipelineProcessor} may be unable to determine any package at all (for
 * example, the eligible applications and DI-eligible resources span disjoint packages with no
 * common prefix and no explicit override). Validation still runs in that case, with the candidate
 * and every enclosing type, and its constructor, treated as reachable only if they are declared
 * {@code public} — see {@link #validate}'s {@code modulePackage} parameter.
 *
 * <p>Since {@code jakarta.ws.rs-api} and {@code vertique-rest-jaxrs} are test-scope dependencies of
 * this module, both {@code jakarta.ws.rs.core.Application} and
 * {@code dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration} are located by
 * fully-qualified name through {@link Elements#getTypeElement(CharSequence)} rather than imported.
 *
 * <p><strong>{@code @NoAutoWire} exemption (owner decision Q4).</strong> A class annotated
 * {@code @NoAutoWire} is inert: it is checked first, before every other scanning or
 * {@code @ApplicationPath} rule (including step 0), so it is neither registered nor validated.
 * It gets only the {@code @NoAutoWire} warning.
 */
public final class JaxRsApplicationScanner {

    /** FQN of {@code jakarta.ws.rs.core.Application}. */
    private static final String APPLICATION_FQN = "jakarta.ws.rs.core.Application";

    /** FQN of {@code dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration}. */
    private static final String REGISTRATION_FQN =
            "dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration";

    private JaxRsApplicationScanner() {}

    // --- Public API ---

    /**
     * A structurally- and semantically-eligible application, validated and ready for registration
     * emission.
     *
     * @param type           the application's type element
     * @param constructedByProvider {@code true} when {@code type} has an {@code @Inject}
     *                       constructor and must be constructed through a Dagger {@code Provider};
     *                       {@code false} when it is constructed through its no-arg constructor
     *                       ({@code type::new})
     * @param normalizedPath the normalized {@code @ApplicationPath} literal
     */
    public record Registration(TypeElement type, boolean constructedByProvider, String normalizedPath) {}

    /**
     * Walks the round's root elements for concrete {@code Application} subtypes, top-level or
     * static nested at any depth, reports the structural scanning diagnostics, and returns the
     * remaining candidates in fully-qualified-name order.
     *
     * <p>A candidate annotated {@code @NoAutoWire} gets the {@code @NoAutoWire} warning and is
     * excluded. A candidate that is a non-static inner class is a compile error and is excluded.
     * When {@code jakarta.ws.rs.core.Application} is not resolvable, no class is eligible. When at
     * least one candidate remains eligible but
     * {@code GeneratedJaxRsApplicationRegistration} is not resolvable, this reports one compile
     * error naming the missing {@code vertique-rest-jaxrs} dependency (checked before any
     * registration is emitted) and returns an empty list.
     *
     * @param roundEnv the current annotation processing round environment; must not be {@code null}
     * @param ctx      the shared codegen context; must not be {@code null}
     * @return the structurally-eligible candidates, in fully-qualified-name order; never
     *     {@code null}
     */
    public static List<TypeElement> scan(RoundEnvironment roundEnv, CodegenContext ctx) {
        Elements elements = ctx.elements();
        Types types = ctx.types();

        TypeElement applicationType = elements.getTypeElement(APPLICATION_FQN);
        if (applicationType == null) {
            return List.of();
        }

        List<TypeElement> candidates = new ArrayList<>();
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof TypeElement rootType) {
                collectApplicationSubtypes(rootType, applicationType, types, candidates);
            }
        }

        List<TypeElement> eligible = new ArrayList<>();
        for (TypeElement candidate : candidates) {
            if (candidate.getAnnotation(NoAutoWire.class) != null) {
                ctx.diagnostics()
                        .warning(
                                candidate,
                                "%s is annotated @NoAutoWire, so it is not registered or validated;"
                                        + " its resources fall back to the default mount.",
                                candidate.getSimpleName());
                continue;
            }
            if (!isTopLevelOrStaticNested(candidate)) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "%s must be a top-level or static nested class to be an"
                                        + " auto-wired JAX-RS application.",
                                candidate.getSimpleName());
                continue;
            }
            eligible.add(candidate);
        }

        if (eligible.isEmpty()) {
            return List.of();
        }

        if (elements.getTypeElement(REGISTRATION_FQN) == null) {
            ctx.diagnostics()
                    .error(
                            null,
                            "An eligible JAX-RS application was found, but 'vertique-rest-jaxrs' is not"
                                    + " on the compile classpath; add it as a dependency to generate"
                                    + " application registrations.");
            return List.of();
        }

        eligible.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        return eligible;
    }

    /**
     * Validates each structurally-eligible candidate against its construction and accessibility
     * rules and the application path grammar's steps 0 to 3 (required, normalized, and well-formed
     * {@code @ApplicationPath}), reporting a compile error naming the class for the first rule a
     * candidate fails and excluding it from the result. A malformed value (step 3) is reported by
     * step 4: one compile error naming the class, the value as written, and the violated rule.
     *
     * <p>Every successfully validated application gets exactly one diagnostic: a registration NOTE
     * naming its class and normalized path when {@code autoWireDisabled} is {@code false}, or a
     * warning naming it and stating that the default {@code @JaxRsResources} mount is used when
     * {@code autoWireDisabled} is {@code true}. This runs regardless of {@code autoWireDisabled}
     * (validation runs for every eligible application).
     *
     * @param eligible         the structurally-eligible candidates from {@link #scan}, in
     *                         fully-qualified-name order; must not be {@code null}
     * @param modulePackage    the generated module's resolved package, or {@code null} when
     *                         auto-wiring is disabled and no package could be determined (the
     *                         candidate and every enclosing type, and its constructor, are then
     *                         treated as reachable only if {@code public} — see
     *                         {@link #isAccessible(Element, String, String)})
     * @param autoWireDisabled {@code true} when {@code -Avertique.codegen.autoWire=false} is set
     * @param ctx              the shared codegen context; must not be {@code null}
     * @return the successfully validated registrations, in the same order as {@code eligible};
     *     never {@code null}
     */
    public static List<Registration> validate(
            List<TypeElement> eligible, String modulePackage, boolean autoWireDisabled, CodegenContext ctx) {
        List<Registration> result = new ArrayList<>();
        for (TypeElement candidate : eligible) {
            String candidatePackage = ctx.packageNameOf(candidate);
            TypeElement inaccessibleType = firstInaccessibleEnclosingType(candidate, candidatePackage, modulePackage);
            if (inaccessibleType != null) {
                ctx.diagnostics().error(candidate, inaccessibleMessage(candidate, inaccessibleType, modulePackage));
                continue;
            }

            Boolean constructedByProvider = resolveConstructionMode(candidate, candidatePackage, modulePackage, ctx);
            if (constructedByProvider == null) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "%s has no usable constructor for application registration: it needs"
                                        + " exactly one @Inject constructor, or an accessible no-arg"
                                        + " constructor.",
                                candidate.getSimpleName());
                continue;
            }

            String normalizedPath = ApplicationPathGrammar.normalize(candidate, ctx);
            if (normalizedPath == null) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "%s declares no @ApplicationPath on itself or any superclass; declare"
                                        + " @ApplicationPath(\"/\") for a root application.",
                                candidate.getSimpleName());
                continue;
            }

            String violatedRule = ApplicationPathGrammar.violatedRule(normalizedPath);
            if (violatedRule != null) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "%s has an invalid @ApplicationPath value \"%s\" (rule: %s); an application"
                                        + " path may contain only '/' and the characters A-Z a-z 0-9 . _ ~ -",
                                candidate.getSimpleName(),
                                ApplicationPathGrammar.originalValue(candidate, ctx),
                                violatedRule);
                continue;
            }

            result.add(new Registration(candidate, constructedByProvider, normalizedPath));
            if (autoWireDisabled) {
                ctx.diagnostics()
                        .warning(
                                candidate,
                                "%s is not auto-wired because -Avertique.codegen.autoWire=false is set;"
                                        + " its resources are exposed through the default @JaxRsResources"
                                        + " mount.",
                                candidate.getSimpleName());
            } else {
                ctx.diagnostics()
                        .note(
                                candidate,
                                "Registered JAX-RS application %s at %s",
                                candidate.getSimpleName(),
                                normalizedPath);
            }
        }
        return result;
    }

    // --- Internal helpers ---

    /**
     * Recursively collects every concrete {@code Application} subtype reachable from {@code type},
     * regardless of nesting depth or static-ness — the static-ness check happens later in
     * {@link #scan} so that a non-static inner subtype is still discovered and reported.
     */
    private static void collectApplicationSubtypes(
            TypeElement type, TypeElement applicationType, Types types, List<TypeElement> out) {
        if (isConcreteClass(type) && types.isAssignable(type.asType(), applicationType.asType())) {
            out.add(type);
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                collectApplicationSubtypes(nested, applicationType, types, out);
            }
        }
    }

    private static boolean isConcreteClass(TypeElement type) {
        return type.getKind() == ElementKind.CLASS && !type.getModifiers().contains(Modifier.ABSTRACT);
    }

    /**
     * Returns {@code true} when {@code type} is top-level, or static nested at any depth: every
     * enclosing type down to the top level is itself a static member type.
     */
    private static boolean isTopLevelOrStaticNested(TypeElement type) {
        TypeElement current = type;
        while (true) {
            NestingKind nesting = current.getNestingKind();
            if (nesting == NestingKind.TOP_LEVEL) {
                return true;
            }
            if (nesting != NestingKind.MEMBER || !current.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
            Element enclosing = current.getEnclosingElement();
            if (!(enclosing instanceof TypeElement enclosingType)) {
                return false;
            }
            current = enclosingType;
        }
    }

    /**
     * Accessibility rule: public, or not private when {@code elementPackage} equals
     * {@code targetPackage}. {@code targetPackage} may be {@code null} (no module package could be
     * determined; see {@link #validate}'s {@code modulePackage} parameter), in which case the
     * package-private branch never matches, so only a {@code public} element passes.
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
     * Accessibility rule for an application candidate: {@code type} itself and every one of its
     * enclosing types (up to the top level) must each satisfy {@link #isAccessible(Element, String,
     * String)}. A class nested inside an inaccessible enclosing type is itself unreachable from
     * outside the enclosing type's package, even when the nested class is declared {@code public} —
     * a caller outside that package cannot even name it.
     *
     * @return the first inaccessible type found, walking from {@code type} itself outward through
     *     its enclosing types; {@code null} when {@code type} and every enclosing type are
     *     accessible. The returned type is {@code type} itself when {@code type}'s own modifiers are
     *     the problem, or a distinct enclosing type when {@code type} is accessible but one of its
     *     enclosing types is not — the two cases get different diagnostic wording; see
     *     {@link #inaccessibleMessage}.
     */
    private static TypeElement firstInaccessibleEnclosingType(
            TypeElement type, String typePackage, String targetPackage) {
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
     * Builds the inaccessible-application diagnostic message, naming {@code candidate} by simple
     * name and distinguishing three cases: {@code candidate} itself is the inaccessible type;
     * {@code candidate} is accessible but an enclosing type is not, which is named too; or no
     * generated-module package could be resolved at all ({@code targetPackage} is {@code null},
     * under {@code -Avertique.codegen.autoWire=false} with disjoint origin packages and no
     * override), in which case the message never echoes the literal {@code null} and instead states
     * that {@code candidate} and its enclosing types must be public.
     *
     * @param candidate     the application candidate under validation
     * @param inaccessible  the first inaccessible type {@link #firstInaccessibleEnclosingType}
     *                      found; either {@code candidate} itself or one of its enclosing types
     * @param targetPackage the generated module's resolved package, or {@code null} when none could
     *                      be resolved
     * @return the formatted diagnostic message
     */
    private static String inaccessibleMessage(TypeElement candidate, TypeElement inaccessible, String targetPackage) {
        if (targetPackage == null) {
            return ("%s is not accessible: no generated-module package could be resolved, so the application"
                            + " and its enclosing types must be public.")
                    .formatted(candidate.getSimpleName());
        }
        if (inaccessible.equals(candidate)) {
            return ("%s is not accessible from the generated module's package '%s'; make it public, or"
                            + " package-private in that same package.")
                    .formatted(candidate.getSimpleName(), targetPackage);
        }
        return ("%s is not accessible from the generated module's package '%s': its enclosing type %s is not"
                        + " public; make %s public, or package-private in that same package.")
                .formatted(
                        candidate.getSimpleName(),
                        targetPackage,
                        inaccessible.getQualifiedName(),
                        inaccessible.getSimpleName());
    }

    /**
     * Resolves {@code candidate}'s construction mode: {@code true} (through the Dagger
     * {@code Provider}) when it has an {@code @Inject} constructor; {@code false} (through
     * {@code candidate::new}) when it has an accessible no-arg constructor instead; {@code null}
     * when neither applies.
     */
    private static Boolean resolveConstructionMode(
            TypeElement candidate, String candidatePackage, String modulePackage, CodegenContext ctx) {
        if (JaxRsCandidateScanner.hasInjectConstructor(candidate)) {
            return Boolean.TRUE;
        }
        ExecutableElement noArgConstructor = findNoArgConstructor(candidate);
        if (noArgConstructor != null && isAccessible(noArgConstructor, candidatePackage, modulePackage)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static ExecutableElement findNoArgConstructor(TypeElement type) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                    && enclosed instanceof ExecutableElement ctor
                    && ctor.getParameters().isEmpty()) {
                return ctor;
            }
        }
        return null;
    }
}
