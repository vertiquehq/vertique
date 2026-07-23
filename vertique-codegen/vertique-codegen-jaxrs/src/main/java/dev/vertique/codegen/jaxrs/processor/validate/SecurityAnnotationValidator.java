// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveResourceContract;
import dev.vertique.codegen.jaxrs.EffectiveSecurityContract;
import dev.vertique.codegen.jaxrs.EffectiveSecurityContract.SecurityKind;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Validates security annotation combinations on JAX-RS resource classes and methods at
 * compile time (CG-009 Tier-A).
 *
 * <p>Mirrors the conflict matrix in
 * {@link dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver} and the
 * empty-{@code @RolesAllowed} detection in {@code ResourceScanner}. Class-level and method-level
 * conflicts are checked independently — a conflict at the class level fires even when the
 * method-level policy is clean, and vice versa.
 *
 * <p>Conflict combinations checked at each declaration level:
 * <ul>
 *   <li>{@code @DenyAll} + {@code @PermitAll}</li>
 *   <li>{@code @DenyAll} + {@code @RolesAllowed}</li>
 *   <li>{@code @DenyAll} + {@code @Authorized}</li>
 *   <li>{@code @PermitAll} + {@code @RolesAllowed}</li>
 *   <li>{@code @PermitAll} + {@code @Authorized}</li>
 * </ul>
 *
 * <p>Empty-{@code @RolesAllowed} detection follows the runtime effective-policy resolution in
 * {@link dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver#hasEmptyRolesAllowed}:
 * for each HTTP-verb method, compute the effective {@code @RolesAllowed} — method-level wins;
 * if the method declares any security annotation ({@code @DenyAll}, {@code @PermitAll},
 * {@code @RolesAllowed}, or {@code @Authorized}) then class-level {@code @RolesAllowed} is
 * ignored entirely; otherwise fall back to the class-level {@code @RolesAllowed}. An empty
 * value array at the effective level is an error, emitted on the method element.
 * Class-level empty {@code @RolesAllowed} alone is NOT an error at the class level — it is
 * only an error when it is the effective policy for a method (i.e., the method declares no
 * security annotation of its own).
 *
 * <p>Two APIs are provided: the original element-based API (used by CG-009 tests) and an
 * {@link EffectiveResourceContract}/{@link EffectiveMethodContract}-based API used by
 * {@code JaxRsPipelineProcessor} (CG-010).
 */
public final class SecurityAnnotationValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new {@code SecurityAnnotationValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public SecurityAnnotationValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Element-based API (CG-009, preserved for existing tests) ---

    /**
     * Validates the class-level security annotations on the given resource type.
     *
     * <p>Emits an {@code ERROR} diagnostic if any conflicting combination is present at the
     * class level. Does NOT check for empty {@code @RolesAllowed} here — that check is
     * performed per-method in {@link #validateMethodLevel(TypeElement, ExecutableElement)}
     * using effective-policy resolution, mirroring runtime
     * {@link dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver#hasEmptyRolesAllowed}.
     *
     * @param resource the JAX-RS resource type element to validate; must not be {@code null}
     */
    public void validateClassLevel(TypeElement resource) {
        boolean hasDenyAll = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.DENY_ALL);
        boolean hasPermitAll = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.PERMIT_ALL);
        boolean hasRolesAllowed = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.ROLES_ALLOWED);
        boolean hasAuthorized = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.AUTHORIZED);
        boolean hasRequiresAction = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.REQUIRES_ACTION);

        if (isConflictingCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized)) {
            String combination = describeCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized);
            ctx.diagnostics().error(resource, Diagnostics.securityAnnotationConflict("class-level", combination));
        }
        checkRequiresActionConflict(resource, "class-level", hasRequiresAction, hasPermitAll, hasDenyAll);
    }

    /**
     * Validates the method-level security annotations on the given resource method.
     *
     * <p>Returns {@code true} when no security violation was emitted for this method (the caller
     * may proceed with downstream validators), and {@code false} when at least one violation
     * fired (the caller must skip downstream validators for this method to mirror runtime
     * {@code ResourceScanner} {@code continue}-after-violation flow — runtime never builds
     * {@code ResourceMethodMeta} or runs {@code RouteValidator.validateMethodParams} for a
     * method whose security check failed).
     *
     * <p>Conflict detection: emits an {@code ERROR} diagnostic and returns {@code false} when a
     * conflicting combination is present at the method level. A class-level conflict (already
     * reported by {@link #validateClassLevel(TypeElement)}) also returns {@code false} without
     * emitting another diagnostic, since runtime would also continue past this method.
     *
     * <p>Empty-{@code @RolesAllowed} detection uses effective-policy resolution mirroring the
     * runtime
     * {@link dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver#hasEmptyRolesAllowed}:
     * <ol>
     *   <li>If the method itself carries {@code @RolesAllowed} and it is empty → ERROR; returns {@code false}.</li>
     *   <li>If the method carries any security annotation ({@code @DenyAll}, {@code @PermitAll},
     *       {@code @RolesAllowed}, or {@code @Authorized}), class-level {@code @RolesAllowed} is
     *       ignored (class-level policy is overridden by the method's own policy).</li>
     *   <li>If the method carries NO security annotation, and the class-level {@code @RolesAllowed}
     *       is empty → ERROR; returns {@code false} (the class-level empty policy is the effective
     *       policy for this method).</li>
     * </ol>
     *
     * @param resource the resource type that declares the method; must not be {@code null}
     * @param method   the resource method to validate; must not be {@code null}
     * @return {@code true} when no security violation was emitted; {@code false} when downstream
     *     validators should be skipped for this method
     */
    public boolean validateMethodLevel(TypeElement resource, ExecutableElement method) {
        boolean hasDenyAll = AnnotationMirrors.isPresent(method, JaxRsAnnotations.DENY_ALL);
        boolean hasPermitAll = AnnotationMirrors.isPresent(method, JaxRsAnnotations.PERMIT_ALL);
        boolean hasRolesAllowed = AnnotationMirrors.isPresent(method, JaxRsAnnotations.ROLES_ALLOWED);
        boolean hasAuthorized = AnnotationMirrors.isPresent(method, JaxRsAnnotations.AUTHORIZED);
        boolean hasRequiresAction = AnnotationMirrors.isPresent(method, JaxRsAnnotations.REQUIRES_ACTION);

        if (isConflictingCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized)) {
            String combination = describeCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized);
            ctx.diagnostics().error(method, Diagnostics.securityAnnotationConflict("method-level", combination));
            return false;
        }

        // @RequiresAction conflicts with a blanket @PermitAll/@DenyAll (independent of the Jakarta
        // conflict matrix above, which does not consider @RequiresAction).
        if (checkRequiresActionConflict(method, "method-level", hasRequiresAction, hasPermitAll, hasDenyAll)) {
            return false;
        }

        // Class-level conflict already reported by validateClassLevel(); runtime would continue
        // past this method, so signal the caller to skip downstream validators.
        if (hasClassLevelConflict(resource)) {
            return false;
        }

        // Effective-policy empty-@RolesAllowed detection — mirrors runtime
        // AnnotationSecurityPolicyResolver.hasEmptyRolesAllowed exactly:
        //   1. Method-level @RolesAllowed: check it directly (empty → error on method).
        //   2. Method declares any security annotation: class-level @RolesAllowed is ignored.
        //   3. Method has no security annotation: fall back to class-level @RolesAllowed.
        if (hasRolesAllowed) {
            if (isEmptyRolesAllowed(method)) {
                ctx.diagnostics().error(method, Diagnostics.emptyRolesAllowed("method-level"));
                return false;
            }
        } else {
            boolean methodHasAny = hasDenyAll || hasPermitAll || hasAuthorized;
            if (!methodHasAny) {
                // Method has no security annotation — class-level @RolesAllowed is the effective policy.
                boolean classHasRolesAllowed = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.ROLES_ALLOWED);
                if (classHasRolesAllowed && isEmptyRolesAllowed(resource)) {
                    ctx.diagnostics().error(method, Diagnostics.emptyRolesAllowed("effective (class-level)"));
                    return false;
                }
            }
        }
        return true;
    }

    // --- Contract-based API (CG-010) ---

    /**
     * Validates the class-level security annotations from the resolved resource contract.
     *
     * <p>Applies the same conflict rules as {@link #validateClassLevel(TypeElement)}. The effective
     * security contract is already resolved by {@code EffectiveJaxRsContractResolver}; this
     * overload checks the resulting {@link EffectiveSecurityContract} for conflicting kinds.
     *
     * @param resourceContract the resolved resource contract; must not be {@code null}
     */
    public void validateClassLevel(EffectiveResourceContract resourceContract) {
        EffectiveSecurityContract sc = resourceContract.classSecurity();
        if (isConflictingKinds(sc)) {
            String combination = describeKinds(sc);
            ctx.diagnostics()
                    .error(
                            resourceContract.concreteClass(),
                            Diagnostics.securityAnnotationConflict("class-level", combination));
        }
        // @RequiresAction is not tracked in EffectiveSecurityContract; read it from the raw element.
        TypeElement clazz = resourceContract.concreteClass();
        checkRequiresActionConflict(
                clazz,
                "class-level",
                AnnotationMirrors.isPresent(clazz, JaxRsAnnotations.REQUIRES_ACTION),
                sc.kinds().contains(SecurityKind.PERMIT_ALL),
                sc.kinds().contains(SecurityKind.DENY_ALL));
    }

    /**
     * Validates the method-level security annotations from the resolved method contract.
     *
     * <p>Applies the same conflict rules and empty-{@code @RolesAllowed} detection as
     * {@link #validateMethodLevel(TypeElement, ExecutableElement)}.
     *
     * <p>Returns {@code true} when no security violation was emitted (the caller may proceed with
     * downstream validators); {@code false} when at least one violation fired.
     *
     * @param resourceContract the resolved resource contract; must not be {@code null}
     * @param methodContract   the resolved method contract; must not be {@code null}
     * @return {@code true} when no security violation was emitted; {@code false} otherwise
     */
    public boolean validateMethodLevel(
            EffectiveResourceContract resourceContract, EffectiveMethodContract methodContract) {
        EffectiveSecurityContract methodSec = methodContract.methodSecurity();
        ExecutableElement method = methodContract.concreteMethod();

        if (isConflictingKinds(methodSec)) {
            String combination = describeKinds(methodSec);
            ctx.diagnostics().error(method, Diagnostics.securityAnnotationConflict("method-level", combination));
            return false;
        }

        // @RequiresAction conflicts with a blanket @PermitAll/@DenyAll at the method level
        // (@RequiresAction is read from the raw element; it is not part of EffectiveSecurityContract).
        if (checkRequiresActionConflict(
                method,
                "method-level",
                AnnotationMirrors.isPresent(method, JaxRsAnnotations.REQUIRES_ACTION),
                methodSec.kinds().contains(SecurityKind.PERMIT_ALL),
                methodSec.kinds().contains(SecurityKind.DENY_ALL))) {
            return false;
        }

        // Class-level conflict already reported — skip downstream
        if (isConflictingKinds(resourceContract.classSecurity())) {
            return false;
        }

        // Effective-policy empty-@RolesAllowed detection
        if (methodSec.kinds().contains(SecurityKind.ROLES_ALLOWED)) {
            if (methodSec.rolesAllowed().isEmpty()) {
                ctx.diagnostics().error(method, Diagnostics.emptyRolesAllowed("method-level"));
                return false;
            }
        } else {
            boolean methodHasAny = !methodSec.isEmpty();
            if (!methodHasAny) {
                // Fall back to class-level @RolesAllowed
                EffectiveSecurityContract classSec = resourceContract.classSecurity();
                if (classSec.kinds().contains(SecurityKind.ROLES_ALLOWED)
                        && classSec.rolesAllowed().isEmpty()) {
                    ctx.diagnostics().error(method, Diagnostics.emptyRolesAllowed("effective (class-level)"));
                    return false;
                }
            }
        }
        return true;
    }

    // --- Private helpers ---

    /**
     * Emits a {@code @RequiresAction}-conflict diagnostic when {@code @RequiresAction} is combined
     * with a blanket {@code @PermitAll} or {@code @DenyAll} at the same declaration level.
     * {@code @RequiresAction} AND-composes only with {@code @RolesAllowed}/{@code @Authorized}, so a
     * blanket allow/deny is a contradiction. Mirrors the runtime startup check in
     * {@code JaxRsRouteRegistrar}.
     *
     * @param element           the element to attach the diagnostic to (class or method)
     * @param level             the declaration level ({@code "class-level"} / {@code "method-level"})
     * @param hasRequiresAction whether {@code @RequiresAction} is present at this level
     * @param hasPermitAll      whether {@code @PermitAll} is present at this level
     * @param hasDenyAll        whether {@code @DenyAll} is present at this level
     * @return {@code true} if a conflict diagnostic was emitted, {@code false} otherwise
     */
    private boolean checkRequiresActionConflict(
            Element element, String level, boolean hasRequiresAction, boolean hasPermitAll, boolean hasDenyAll) {
        if (!hasRequiresAction || !(hasPermitAll || hasDenyAll)) {
            return false;
        }
        String conflicting = hasPermitAll ? "@PermitAll" : "@DenyAll";
        ctx.diagnostics().error(element, Diagnostics.requiresActionConflict(level, conflicting));
        return true;
    }

    /**
     * Returns {@code true} if the given combination of security annotation flags represents
     * a conflicting combination at a single declaration level.
     *
     * @param hasDenyAll      whether {@code @DenyAll} is present
     * @param hasPermitAll    whether {@code @PermitAll} is present
     * @param hasRolesAllowed whether {@code @RolesAllowed} is present
     * @param hasAuthorized   whether {@code @Authorized} is present
     * @return {@code true} if the combination is mutually exclusive
     */
    private boolean isConflictingCombination(
            boolean hasDenyAll, boolean hasPermitAll, boolean hasRolesAllowed, boolean hasAuthorized) {
        if (hasDenyAll && (hasPermitAll || hasRolesAllowed || hasAuthorized)) return true;
        if (hasPermitAll && (hasRolesAllowed || hasAuthorized)) return true;
        return false;
    }

    /**
     * Returns {@code true} if the given {@link EffectiveSecurityContract} carries a conflicting
     * combination of security kinds.
     *
     * @param sc the security contract to check
     * @return {@code true} if the kinds set is conflicting
     */
    private boolean isConflictingKinds(EffectiveSecurityContract sc) {
        boolean hasDenyAll = sc.kinds().contains(SecurityKind.DENY_ALL);
        boolean hasPermitAll = sc.kinds().contains(SecurityKind.PERMIT_ALL);
        boolean hasRolesAllowed = sc.kinds().contains(SecurityKind.ROLES_ALLOWED);
        boolean hasAuthorized = sc.kinds().contains(SecurityKind.AUTHORIZED);
        return isConflictingCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized);
    }

    /**
     * Builds a human-readable description of the conflicting security annotations as a string like
     * {@code "@DenyAll + @PermitAll"}, used as the {@code combination} argument in the diagnostic.
     *
     * @param hasDenyAll      whether {@code @DenyAll} is present
     * @param hasPermitAll    whether {@code @PermitAll} is present
     * @param hasRolesAllowed whether {@code @RolesAllowed} is present
     * @param hasAuthorized   whether {@code @Authorized} is present
     * @return the combination string
     */
    private String describeCombination(
            boolean hasDenyAll, boolean hasPermitAll, boolean hasRolesAllowed, boolean hasAuthorized) {
        List<String> present = new ArrayList<>();
        if (hasDenyAll) present.add("@DenyAll");
        if (hasPermitAll) present.add("@PermitAll");
        if (hasRolesAllowed) present.add("@RolesAllowed");
        if (hasAuthorized) present.add("@Authorized");
        return String.join(" + ", present);
    }

    /**
     * Builds a human-readable description of the security kinds in the given contract.
     *
     * @param sc the security contract
     * @return the combination string
     */
    private String describeKinds(EffectiveSecurityContract sc) {
        List<String> present = new ArrayList<>();
        if (sc.kinds().contains(SecurityKind.DENY_ALL)) present.add("@DenyAll");
        if (sc.kinds().contains(SecurityKind.PERMIT_ALL)) present.add("@PermitAll");
        if (sc.kinds().contains(SecurityKind.ROLES_ALLOWED)) present.add("@RolesAllowed");
        if (sc.kinds().contains(SecurityKind.AUTHORIZED)) present.add("@Authorized");
        return String.join(" + ", present);
    }

    /**
     * Returns {@code true} if the given resource class carries a conflicting security annotation
     * combination at the class level (i.e., a combination already reported by
     * {@link #validateClassLevel(TypeElement)}). When a class-level conflict exists, the
     * effective-policy fallback for methods with no security annotations of their own is
     * suppressed — the class-level conflict is already reported once, and reporting an additional
     * empty-{@code @RolesAllowed} error per method would be misleading noise.
     *
     * @param resource the resource type to inspect; must not be {@code null}
     * @return {@code true} if the class carries conflicting security annotations
     */
    private boolean hasClassLevelConflict(TypeElement resource) {
        boolean hasDenyAll = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.DENY_ALL);
        boolean hasPermitAll = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.PERMIT_ALL);
        boolean hasRolesAllowed = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.ROLES_ALLOWED);
        boolean hasAuthorized = AnnotationMirrors.isPresent(resource, JaxRsAnnotations.AUTHORIZED);
        return isConflictingCombination(hasDenyAll, hasPermitAll, hasRolesAllowed, hasAuthorized);
    }

    /**
     * Returns {@code true} if the {@code @RolesAllowed} annotation on the given element has an
     * empty value array.
     *
     * @param element the element carrying {@code @RolesAllowed}; must not be {@code null}
     * @return {@code true} when the value array is empty
     */
    private boolean isEmptyRolesAllowed(Element element) {
        return AnnotationMirrors.findByFqn(element, JaxRsAnnotations.ROLES_ALLOWED)
                .map(mirror -> ctx.annotations().attributeArray(mirror, "value"))
                .map(List::isEmpty)
                .orElse(false);
    }
}
