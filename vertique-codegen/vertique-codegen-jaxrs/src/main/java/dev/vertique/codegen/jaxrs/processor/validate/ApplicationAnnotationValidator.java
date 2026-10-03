// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.JaxRsHierarchy;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * The compile-time annotation checks of {@code @RestApplication} declarations: the declaration
 * annotation allow list and the {@code @ApiDocs} shape rules. Every violation is a compile error
 * attributed to the declaring interface, and a declaration with any violation is not registered.
 *
 * <p><strong>Allow list.</strong> The checked scope is the declaring interface and every
 * superinterface, transitively, in {@link JaxRsHierarchy#allInterfaces} discovery order. Only
 * annotations directly present on those type declarations are read ({@link
 * TypeElement#getAnnotationMirrors()}, never the inherited-annotation variant, and never member
 * annotations). Allowed are:
 *
 * <ul>
 *   <li>on the declaring interface only: {@code @RestApplication}, {@code @ApiDocs}, {@code
 *       io.swagger.v3.oas.annotations.OpenAPIDefinition} in which every element other than {@code
 *       info} equals its declared default (compared structurally against {@link
 *       ExecutableElement#getDefaultValue()}), and the {@code SOURCE}-retained
 *       {@code @ConditionalOnProperty} (single or container form) and {@code @NoAutoWire};
 *   <li>anywhere in scope: annotation types in the packages {@code java.lang} and {@code
 *       java.lang.annotation}.
 * </ul>
 *
 * <p>Any other {@code RUNTIME}-retained annotation in scope fails compilation, and so does any of
 * the declaration-only annotations above on a superinterface. A repeatable container annotation
 * (such as the compiler-synthesized {@code ConditionalOnProperties}) is checked as an annotation in
 * its own right; {@link TypeElement#getAnnotationMirrors()} already returns the container, not its
 * repeated elements. Every other {@code SOURCE}- or {@code CLASS}-retained annotation, including one
 * with no {@code @Retention} at all (which defaults to {@code CLASS}), is unchecked; a {@code
 * SOURCE}-retained annotation is visible only on types compiled in the same build. Every message
 * names the declaration, the annotation, and the type carrying it, each by binary name.
 *
 * <p><strong>{@code @ApiDocs}.</strong> The documentation access annotation is recognized by the
 * fully qualified name {@link #API_DOCS_FQN}, so this module needs no dependency on the
 * documentation module, and its elements are read by name, on the declaring interface only:
 *
 * <ul>
 *   <li>{@code access = PROTECTED} needs a non-blank {@code securityScheme}, and every {@code
 *       rolesAllowed} entry must be non-blank (an empty {@code rolesAllowed} means any authenticated
 *       caller);
 *   <li>{@code access = PUBLIC} takes neither a {@code securityScheme} nor any {@code
 *       rolesAllowed} entry.
 * </ul>
 *
 * <p>Element values are judged as read, so an explicit value equal to the default counts as unset.
 * An {@code @ApiDocs} without {@code access} is javac's missing-element error and gets no further
 * check here. Each violation's message names the declaration and the offending element.
 *
 * <p>A declaration annotated {@code @NoAutoWire} is never passed to this validator: the
 * declaration scan excludes it first, so it is inert. The checks do not depend on the auto-wire
 * setting.
 */
public final class ApplicationAnnotationValidator {

    /**
     * The fully qualified name of the documentation module's {@code @ApiDocs}, held as a literal so
     * this module gains no dependency on the documentation module; the only copy of the name in the
     * processor, pinned by a test.
     */
    public static final String API_DOCS_FQN = "dev.vertique.rest.openapi.docs.ApiDocs";

    /** FQN of {@code dev.vertique.rest.core.application.RestApplication}. */
    private static final String REST_APPLICATION_FQN = "dev.vertique.rest.core.application.RestApplication";

    /** FQN of {@code io.swagger.v3.oas.annotations.OpenAPIDefinition}. */
    private static final String OPEN_API_DEFINITION_FQN = "io.swagger.v3.oas.annotations.OpenAPIDefinition";

    /**
     * The fully qualified names of the {@code RUNTIME}-retained annotations allowed on the declaring
     * interface only; on a superinterface each is a compile error. {@code @OpenAPIDefinition} is
     * additionally limited to its {@code info} element.
     */
    private static final Set<String> RUNTIME_DECLARATION_ONLY =
            Set.of(REST_APPLICATION_FQN, API_DOCS_FQN, OPEN_API_DEFINITION_FQN);

    /**
     * The fully qualified names of the {@code SOURCE}-retained annotations honored only on the
     * declaring interface; on a superinterface each is a compile error.
     */
    private static final Set<String> SOURCE_DECLARATION_ONLY = Set.of(
            "dev.vertique.codegen.ConditionalOnProperty",
            "dev.vertique.codegen.ConditionalOnProperties",
            "dev.vertique.codegen.NoAutoWire");

    /** The packages whose annotation types are allowed anywhere in scope. */
    private static final Set<String> ALLOWED_PACKAGES = Set.of("java.lang", "java.lang.annotation");

    /** The {@code @ApiDocs} access level that requires a security scheme. */
    private static final String PROTECTED = "PROTECTED";

    /** The {@code @ApiDocs} access level that takes no security scheme or roles. */
    private static final String PUBLIC = "PUBLIC";

    private final CodegenContext ctx;

    /**
     * Creates a new {@code ApplicationAnnotationValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ApplicationAnnotationValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Checks every declaration in {@code declarations} against the allow list and the
     * {@code @ApiDocs} rules, reporting one compile error per violation.
     *
     * @param declarations the {@code @RestApplication} declaring interfaces to check, none of them
     *                     annotated {@code @NoAutoWire}; must not be {@code null}
     * @return the declarations with at least one violation, which must not be registered; never
     *     {@code null}
     */
    public Set<TypeElement> validate(List<TypeElement> declarations) {
        Set<TypeElement> rejected = new LinkedHashSet<>();
        for (TypeElement declaration : declarations) {
            boolean allowListPassed = checkAllowList(declaration);
            boolean apiDocsPassed = checkApiDocs(declaration);
            if (!allowListPassed || !apiDocsPassed) {
                rejected.add(declaration);
            }
        }
        return rejected;
    }

    // --- Allow list ---

    /**
     * Checks every annotation directly present on {@code declaration} and its superinterfaces,
     * reporting one compile error per offending (carrying type, annotation) pair.
     *
     * @param declaration the declaring interface
     * @return {@code true} when no annotation in scope violates the allow list
     */
    private boolean checkAllowList(TypeElement declaration) {
        boolean passed = true;
        for (TypeElement carrier : scope(declaration)) {
            for (AnnotationMirror mirror : carrier.getAnnotationMirrors()) {
                if (!checkAnnotation(declaration, carrier, mirror)) {
                    passed = false;
                }
            }
        }
        return passed;
    }

    /**
     * Returns {@code declaration}'s annotation-check scope: the declaring interface itself, then
     * every superinterface, transitively, in {@link JaxRsHierarchy#allInterfaces} discovery order.
     *
     * @param declaration the declaring interface
     * @return the ordered scope
     */
    private List<TypeElement> scope(TypeElement declaration) {
        List<TypeElement> scope = new ArrayList<>();
        scope.add(declaration);
        scope.addAll(JaxRsHierarchy.allInterfaces(ctx, declaration));
        return scope;
    }

    /**
     * Checks one annotation directly present on one type in {@code declaration}'s scope, reporting a
     * compile error when it is a declaration-only annotation found on a superinterface, a
     * {@code RUNTIME}-retained annotation outside the allow list, or an {@code @OpenAPIDefinition}
     * setting an element other than {@code info}. A {@code CLASS}-retained annotation, one with no
     * retention meta-annotation at all, and any other {@code SOURCE}-retained annotation are
     * unchecked.
     *
     * @param declaration the declaring interface, for the diagnostic
     * @param carrier     the type in scope that directly carries {@code mirror}
     * @param mirror      the annotation mirror to check
     * @return {@code true} when the annotation is allowed where it appears
     */
    private boolean checkAnnotation(TypeElement declaration, TypeElement carrier, AnnotationMirror mirror) {
        TypeElement annotationType = asTypeElement(mirror.getAnnotationType());
        if (annotationType == null) {
            return true;
        }
        String annotationFqn = annotationType.getQualifiedName().toString();
        String retention = retentionPolicy(annotationType);
        boolean onDeclaration = carrier.equals(declaration);

        if ("SOURCE".equals(retention)) {
            if (onDeclaration || !SOURCE_DECLARATION_ONLY.contains(annotationFqn)) {
                return true;
            }
            reportError(declaration, declarationOnlyMessage(declaration, annotationType, carrier));
            return false;
        }
        if (!"RUNTIME".equals(retention)) {
            return true;
        }

        String annotationPackage =
                ctx.elements().getPackageOf(annotationType).getQualifiedName().toString();
        if (ALLOWED_PACKAGES.contains(annotationPackage)) {
            return true;
        }
        if (!RUNTIME_DECLARATION_ONLY.contains(annotationFqn)) {
            reportError(declaration, notAllowedMessage(declaration, annotationType, carrier));
            return false;
        }
        if (!onDeclaration) {
            reportError(declaration, declarationOnlyMessage(declaration, annotationType, carrier));
            return false;
        }
        if (OPEN_API_DEFINITION_FQN.equals(annotationFqn) && !isInfoOnlyOpenApiDefinition(mirror)) {
            reportError(declaration, infoOnlyMessage(declaration, annotationType));
            return false;
        }
        return true;
    }

    /**
     * Returns whether every element of {@code mirror} other than {@code info} that is explicitly set
     * equals its declared default, compared structurally since {@link AnnotationValue} has no value
     * equality of its own.
     *
     * @param mirror the {@code OpenAPIDefinition} annotation mirror to check
     * @return {@code true} when every explicitly set element other than {@code info} is at its
     *     declared default
     */
    private boolean isInfoOnlyOpenApiDefinition(AnnotationMirror mirror) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                mirror.getElementValues().entrySet()) {
            if ("info".contentEquals(entry.getKey().getSimpleName())) {
                continue;
            }
            AnnotationValue defaultValue = entry.getKey().getDefaultValue();
            if (!structurallyEqual(entry.getValue(), defaultValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two {@link AnnotationValue}s structurally: arrays are compared element-wise in order,
     * nested annotation mirrors are compared by {@link #structurallyEqualAnnotations}, and every
     * other value kind is compared with {@link Objects#equals}.
     *
     * @param a the first value, or {@code null}
     * @param b the second value, or {@code null}
     * @return {@code true} when {@code a} and {@code b} are structurally equal
     */
    private boolean structurallyEqual(AnnotationValue a, AnnotationValue b) {
        Object av = a == null ? null : a.getValue();
        Object bv = b == null ? null : b.getValue();
        if (av instanceof List<?> aList && bv instanceof List<?> bList) {
            if (aList.size() != bList.size()) {
                return false;
            }
            for (int i = 0; i < aList.size(); i++) {
                if (!structurallyEqual((AnnotationValue) aList.get(i), (AnnotationValue) bList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (av instanceof AnnotationMirror aMirror && bv instanceof AnnotationMirror bMirror) {
            return structurallyEqualAnnotations(aMirror, bMirror);
        }
        return Objects.equals(av, bv);
    }

    /**
     * Compares two nested annotation mirrors of the same annotation type element-wise, reading every
     * element (defaulted or explicit) of each through {@link
     * javax.lang.model.util.Elements#getElementValuesWithDefaults}.
     *
     * @param a the first nested annotation mirror
     * @param b the second nested annotation mirror
     * @return {@code true} when every element of {@code a} and {@code b} is structurally equal
     */
    private boolean structurallyEqualAnnotations(AnnotationMirror a, AnnotationMirror b) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> aValues =
                ctx.elements().getElementValuesWithDefaults(a);
        Map<? extends ExecutableElement, ? extends AnnotationValue> bValues =
                ctx.elements().getElementValuesWithDefaults(b);
        if (aValues.size() != bValues.size()) {
            return false;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : aValues.entrySet()) {
            AnnotationValue bValue = findBySimpleName(bValues, entry.getKey().getSimpleName());
            if (bValue == null || !structurallyEqual(entry.getValue(), bValue)) {
                return false;
            }
        }
        return true;
    }

    private static AnnotationValue findBySimpleName(
            Map<? extends ExecutableElement, ? extends AnnotationValue> values, Name simpleName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().equals(simpleName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // --- @ApiDocs ---

    /**
     * Checks the {@code @ApiDocs} directly present on {@code declaration}, if any, against the
     * access rules, reporting one compile error per violation. An {@code @ApiDocs} without {@code
     * access} is left to javac's missing-element error.
     *
     * @param declaration the declaring interface
     * @return {@code true} when {@code declaration} carries no {@code @ApiDocs}, or one that
     *     violates no rule
     */
    private boolean checkApiDocs(TypeElement declaration) {
        Optional<AnnotationMirror> apiDocs = findDirect(declaration, API_DOCS_FQN);
        if (apiDocs.isEmpty()) {
            return true;
        }
        AnnotationMirror mirror = apiDocs.get();
        Optional<String> access = ctx.annotations()
                .attribute(mirror, "access", VariableElement.class)
                .map(constant -> constant.getSimpleName().toString());
        if (access.isEmpty()) {
            return true;
        }
        String securityScheme = ctx.annotations()
                .attribute(mirror, "securityScheme", String.class)
                .orElse("");
        List<String> rolesAllowed = new ArrayList<>();
        for (AnnotationValue entry : ctx.annotations().attributeArray(mirror, "rolesAllowed")) {
            rolesAllowed.add(entry.getValue() instanceof String role ? role : "");
        }

        boolean passed = true;
        if (PROTECTED.equals(access.get())) {
            if (securityScheme.isBlank()) {
                reportError(
                        declaration,
                        ("%s declares @ApiDocs(access = PROTECTED) without a non-blank securityScheme;"
                                        + " protected documentation needs securityScheme to name the security scheme"
                                        + " that authenticates its readers")
                                .formatted(binaryName(declaration)));
                passed = false;
            }
            if (rolesAllowed.stream().anyMatch(String::isBlank)) {
                reportError(
                        declaration,
                        ("%s declares @ApiDocs(access = PROTECTED) with a blank rolesAllowed entry; every"
                                        + " rolesAllowed entry must name a role (leave rolesAllowed empty to admit any"
                                        + " authenticated caller)")
                                .formatted(binaryName(declaration)));
                passed = false;
            }
        } else if (PUBLIC.equals(access.get())) {
            if (!securityScheme.isEmpty()) {
                reportError(
                        declaration,
                        ("%s declares @ApiDocs(access = PUBLIC) with a securityScheme; securityScheme is"
                                        + " set only when access is PROTECTED")
                                .formatted(binaryName(declaration)));
                passed = false;
            }
            if (!rolesAllowed.isEmpty()) {
                reportError(
                        declaration,
                        ("%s declares @ApiDocs(access = PUBLIC) with rolesAllowed; rolesAllowed is set"
                                        + " only when access is PROTECTED")
                                .formatted(binaryName(declaration)));
                passed = false;
            }
        }
        return passed;
    }

    /**
     * Returns the annotation of type {@code annotationFqn} directly present on {@code type}.
     *
     * @param type          the type to inspect
     * @param annotationFqn the annotation type's fully qualified name
     * @return the annotation mirror, or empty when it is not directly present
     */
    private Optional<AnnotationMirror> findDirect(TypeElement type, String annotationFqn) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            TypeElement annotationType = asTypeElement(mirror.getAnnotationType());
            if (annotationType != null && annotationFqn.contentEquals(annotationType.getQualifiedName())) {
                return Optional.of(mirror);
            }
        }
        return Optional.empty();
    }

    // --- Diagnostics ---

    /**
     * Reports {@code message} as a compile error anchored on {@code declaration}. The message is
     * complete as passed, with no format arguments, so it is printed verbatim.
     *
     * @param declaration the declaring interface
     * @param message     the complete message
     */
    private void reportError(TypeElement declaration, String message) {
        ctx.diagnostics().error(declaration, message);
    }

    /**
     * Builds the message for a {@code RUNTIME}-retained annotation outside the allow list, naming
     * the declaration, the annotation, and, when it is not the declaration itself, the
     * superinterface carrying it, each by binary name.
     *
     * @param declaration    the declaring interface
     * @param annotationType the offending annotation's type
     * @param carrier        the type carrying the annotation
     * @return the violation message
     */
    private String notAllowedMessage(TypeElement declaration, TypeElement annotationType, TypeElement carrier) {
        if (carrier.equals(declaration)) {
            return ("%s carries @%s, which a @RestApplication declaration may not carry: a declaration has no"
                            + " resource semantics and may carry only @RestApplication, @ApiDocs,"
                            + " @OpenAPIDefinition with only info set, @ConditionalOnProperty, @NoAutoWire, and"
                            + " java.lang and java.lang.annotation annotations")
                    .formatted(binaryName(declaration), binaryName(annotationType));
        }
        return ("%s carries @%s on its superinterface %s, which a @RestApplication declaration's"
                        + " superinterface may not carry: a superinterface of a declaration may carry only"
                        + " java.lang and java.lang.annotation annotations")
                .formatted(binaryName(declaration), binaryName(annotationType), binaryName(carrier));
    }

    /**
     * Builds the message for a declaration-only annotation found on a superinterface, naming the
     * declaration, the annotation, and the superinterface by binary name.
     *
     * @param declaration    the declaring interface
     * @param annotationType the offending annotation's type
     * @param carrier        the superinterface carrying the annotation
     * @return the violation message
     */
    private String declarationOnlyMessage(TypeElement declaration, TypeElement annotationType, TypeElement carrier) {
        return ("%s carries @%s on its superinterface %s, which is not allowed: that annotation is honored"
                        + " only on the @RestApplication declaring interface itself")
                .formatted(binaryName(declaration), binaryName(annotationType), binaryName(carrier));
    }

    /**
     * Builds the message for an {@code @OpenAPIDefinition} on the declaration that sets an element
     * other than {@code info}, naming the declaration and the annotation by binary name.
     *
     * @param declaration    the declaring interface
     * @param annotationType the {@code @OpenAPIDefinition} annotation type
     * @return the violation message
     */
    private String infoOnlyMessage(TypeElement declaration, TypeElement annotationType) {
        return ("%s carries @%s with an element other than info set, which is not allowed on a"
                        + " @RestApplication declaration: only its info element may be set")
                .formatted(binaryName(declaration), binaryName(annotationType));
    }

    // --- Internal helpers ---

    /**
     * Returns {@code type}'s binary name, e.g. {@code Outer$Inner} for a nested type, so diagnostic
     * text equals {@code Class.getName()}.
     *
     * @param type the type element
     * @return the binary name
     */
    private String binaryName(TypeElement type) {
        return ctx.elements().getBinaryName(type).toString();
    }

    /**
     * Resolves {@code type} to a {@link TypeElement}, or {@code null} when it does not represent a
     * declared type.
     *
     * @param type the type mirror to resolve
     * @return the resolved type element, or {@code null}
     */
    private TypeElement asTypeElement(TypeMirror type) {
        return ctx.asTypeElement(type).orElse(null);
    }

    /**
     * Returns {@code annotationType}'s {@link Retention} policy name ({@code "RUNTIME"}, {@code
     * "CLASS"}, or {@code "SOURCE"}), defaulting to {@code "CLASS"} when no {@link Retention}
     * meta-annotation is present, mirroring the Java language default.
     *
     * @param annotationType the annotation type to inspect
     * @return the retention policy's simple enum-constant name
     */
    private String retentionPolicy(TypeElement annotationType) {
        return ctx.annotations()
                .find(annotationType, Retention.class)
                .flatMap(m -> ctx.annotations().attribute(m, "value", VariableElement.class))
                .map(v -> v.getSimpleName().toString())
                .orElse("CLASS");
    }
}
