// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.EffectiveSecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link SecurityPolicyValidator} that checks for consistency between
 * Java security annotations and the operation's declared security requirements.
 *
 * <p>Security requirements come from {@link RestOperationDescriptor#securityRequirementSets()}
 * (sourced from Swagger {@code @SecurityRequirement} annotations at scan time), not from the
 * generated OpenAPI document. Checks performed:
 *
 * <ol>
 *   <li>Restrictive security annotations present but no declared security requirement → violation
 *   <li>Declared security requirement but no matching {@link SecuritySchemeHandler} → violation
 *   <li>{@code @PermitAll} on an operation with a declared security requirement → conflicting
 *       semantics
 * </ol>
 *
 * <p><strong>Fail-closed V1 matrix (SH-2):</strong> before the consistency checks above, the
 * validator <em>fails startup</em> with {@link RestConfigurationException} for security shapes whose
 * enforcement is deferred, so no declared semantic is ever silently ignored or fails open:
 *
 * <ol>
 *   <li>Any <strong>multi-scheme (AND) set</strong> ({@code combine()}) — AND-groups are not yet
 *       supported.
 *   <li>More than one OR set where <strong>any set has scopes</strong> — scoped-OR is not yet
 *       supported (the matched alternative is not tracked, so per-alternative scopes cannot be
 *       enforced).
 *   <li>The operation's {@link SecurityPolicy} declares <strong>required scopes</strong>
 *       (via {@code @Authorized}) <em>and</em> any set has scopes — the two scope sources cannot be
 *       merged unambiguously. Roles via {@code @RolesAllowed}/{@code @Authorized} alongside
 *       {@code @SecurityRequirement} scopes is allowed (distinct authority kinds).
 * </ol>
 *
 * <p>Supported shapes pass: no security; a single-scheme scopeless set; a single-scheme scoped set;
 * and multiple single-scheme scopeless OR sets. This slice only <em>validates</em> — it does not yet
 * enforce or merge {@code @SecurityRequirement} scopes (that is SH-4).
 */
@Slf4j
@Singleton
public class DefaultSecurityPolicyValidator implements SecurityPolicyValidator {

    private final Set<String> configuredSchemeNames;

    /**
     * Creates a new default security policy validator.
     *
     * @param schemeHandlers set of configured security scheme handlers
     */
    @Inject
    public DefaultSecurityPolicyValidator(Set<SecuritySchemeHandler> schemeHandlers) {
        this.configuredSchemeNames =
                schemeHandlers.stream().map(SecuritySchemeHandler::schemeName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Validates security policy consistency for a single operation.
     *
     * <p>The operation identifier is read from {@link RestOperationDescriptor#operationId()} and the
     * declared security requirement sets from {@link RestOperationDescriptor#securityRequirementSets()}.
     *
     * @param op     the neutral descriptor for the operation being validated
     * @param policy the resolved security policy derived from annotations on the resource method
     * @return list of violations found (empty if consistent)
     * @throws RestConfigurationException if the operation declares a security shape whose V1
     *     enforcement is deferred (multi-scheme set, scoped-OR, or both-scopes); see the fail-closed
     *     matrix on the class javadoc
     */
    @Override
    public List<SecurityPolicyViolation> validate(RestOperationDescriptor op, SecurityPolicy policy) {
        String operationId = op.operationId();

        // Fail-closed V1 matrix: reject deferred security shapes at startup before the consistency
        // checks below, so no declared semantic is silently ignored or fails open.
        enforceSupportedShape(operationId, op.securityRequirementSets(), policy);

        List<SecurityPolicyViolation> violations = new ArrayList<>();

        // A declared security requirement is any annotation-sourced @SecurityRequirement on the operation.
        boolean hasSecurityRequirement = !op.securityRequirementSets().isEmpty();

        // Check 1: restrictive policy but no declared security requirement
        if (policy.isRestrictive() && !hasSecurityRequirement) {
            violations.add(new SecurityPolicyViolation(
                    operationId,
                    SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY,
                    String.format(
                            "Operation '%s' has security annotations (%s) but no declared security requirement. "
                                    + "Authentication handlers will not run.",
                            operationId, describeAnnotations(policy))));
        }

        // Check 2: declared security requirement but no matching handler
        if (hasSecurityRequirement) {
            List<String> missingSchemes = findMissingSchemes(op);
            if (!missingSchemes.isEmpty()) {
                violations.add(new SecurityPolicyViolation(
                        operationId,
                        SecurityPolicyViolation.ViolationType.OPENAPI_SECURITY_WITHOUT_HANDLER,
                        String.format(
                                "Operation '%s' references security scheme(s) %s "
                                        + "but no matching SecuritySchemeHandler is configured.",
                                operationId, missingSchemes)));
            }
        }

        // Check 3: @PermitAll with a declared security requirement (conflicting intent)
        if (policy instanceof SecurityPolicy.PermitAll && hasSecurityRequirement) {
            violations.add(new SecurityPolicyViolation(
                    operationId,
                    SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS,
                    String.format(
                            "Operation '%s' has @PermitAll but also has a declared security requirement. "
                                    + "Authentication will run (from the requirement) but authorization is "
                                    + "skipped (from @PermitAll).",
                            operationId)));
        }

        return violations;
    }

    /**
     * Fails startup ({@link RestConfigurationException}) when the operation declares a security shape
     * whose V1 enforcement is deferred — the fail-closed matrix on the class javadoc.
     *
     * <p>This delegates to {@link EffectiveSecurityPolicy#enforceSupportedShape}, the single source of
     * truth for ADR-0124's fail-closed matrix shared with the rest-jaxrs route registrar's always-on
     * gate, so the matrix logic lives in exactly one place. The three deferred cases it rejects are any
     * multi-scheme (AND) set; scopes on an OR alternative (more than one set with any set carrying
     * scopes); and scopes declared via both {@code @Authorized} (the policy's required scopes) and
     * {@code @SecurityRequirement} (a set's scopes). Roles via {@code @RolesAllowed}/{@code @Authorized}
     * alongside {@code @SecurityRequirement} scopes is permitted (distinct authority kinds).
     *
     * @param operationId the operationId named in the diagnostic
     * @param sets        the operation's effective security requirement sets (the OR alternatives)
     * @param policy      the operation's resolved security policy
     * @throws RestConfigurationException if the shape is one of the deferred cases
     */
    private void enforceSupportedShape(String operationId, List<SecurityRequirementSet> sets, SecurityPolicy policy) {
        EffectiveSecurityPolicy.enforceSupportedShape(operationId, sets, policy);
    }

    /**
     * Finds security scheme names referenced in the operation's declared security requirement sets
     * that have no configured handler. Every scheme across every set (the OR alternatives and any
     * AND-within-a-set schemes) is checked.
     *
     * @param op the operation descriptor whose security requirement sets are inspected
     * @return list of scheme names lacking a configured {@link SecuritySchemeHandler}
     */
    private List<String> findMissingSchemes(RestOperationDescriptor op) {
        List<String> missing = new ArrayList<>();
        for (SecurityRequirementSet requirementSet : op.securityRequirementSets()) {
            for (SecurityRequirement requirement : requirementSet.schemes()) {
                String schemeName = requirement.schemeName();
                if (!configuredSchemeNames.contains(schemeName)) {
                    missing.add(schemeName);
                }
            }
        }
        return missing;
    }

    /**
     * Returns a human-readable description of the security policy for use in violation messages.
     *
     * @param policy the security policy to describe
     * @return a string describing the active security annotations
     */
    private String describeAnnotations(SecurityPolicy policy) {
        return switch (policy) {
            case SecurityPolicy.DenyAll ignored -> "@DenyAll";
            case SecurityPolicy.PermitAll ignored -> "@PermitAll";
            case SecurityPolicy.AuthenticatedOnly ignored -> "@Authorized(scopes={})";
            case SecurityPolicy.Constrained c -> {
                StringBuilder desc = new StringBuilder();
                if (!c.requiredRoles().isEmpty())
                    desc.append("@RolesAllowed(")
                            .append(String.join(",", c.requiredRoles()))
                            .append(")");
                if (!c.requiredScopes().isEmpty()) {
                    if (!desc.isEmpty()) desc.append(", ");
                    desc.append("@Authorized(scopes=")
                            .append(String.join(",", c.requiredScopes()))
                            .append(")");
                }
                yield desc.toString();
            }
            case SecurityPolicy.None ignored -> "";
        };
    }
}
