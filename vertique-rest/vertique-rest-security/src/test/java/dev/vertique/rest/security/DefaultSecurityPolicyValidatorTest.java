// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSecurityPolicyValidator}, covering the three startup-consistency
 * checks re-sourced from the neutral {@link RestOperationDescriptor}: annotation-without-declared
 * security, declared-security-without-handler, and {@code @PermitAll} conflicting with a declared
 * security requirement.
 *
 * <p>The validator no longer reads the OpenAPI route/contract; security requirements come from
 * {@link RestOperationDescriptor#securityRequirementSets()} (sourced from Swagger
 * {@code @SecurityRequirement} annotations at scan time).
 *
 * <p>This class also covers the V1 fail-closed matrix (SH-2): the validator throws
 * {@link RestConfigurationException} for security shapes whose enforcement is deferred — a
 * multi-scheme (AND) set, scopes on OR alternatives, and scopes declared via both {@code @Authorized}
 * and {@code @SecurityRequirement} on the same operation. Supported shapes (single-scheme scopeless,
 * single-scheme scoped, scopeless OR) pass.
 */
class DefaultSecurityPolicyValidatorTest {

    private DefaultSecurityPolicyValidator validator;

    @BeforeEach
    void setUp() {
        // No configured scheme handlers by default
        validator = new DefaultSecurityPolicyValidator(Set.of());
    }

    @Test
    @DisplayName("SecurityPolicyValidator signature accepts a RestOperationDescriptor (compile-time)")
    void signatureAcceptsRestOperationDescriptor() {
        // The lambda compiles only if validate(RestOperationDescriptor, SecurityPolicy) is the SPI
        // shape and no OpenAPIRoute/OpenAPIContract is in scope.
        SecurityPolicyValidator lambda = (RestOperationDescriptor op, SecurityPolicy p) -> List.of();
        RestOperationDescriptor descriptor = descriptor(new SecurityPolicy.None(), List.of());

        assertTrue(lambda.validate(descriptor, descriptor.securityPolicy()).isEmpty());
    }

    @Test
    @DisplayName("@PermitAll with no security requirement produces no violations")
    void permitAllNoRequirementReturnsNoViolations() {
        RestOperationDescriptor descriptor = descriptor(new SecurityPolicy.PermitAll(), List.of());

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("@DenyAll (restrictive) with no security requirement produces one violation")
    void denyAllWithoutRequirementReturnsViolation() {
        // DenyAll.isRestrictive() == true, so check 1 fires.
        RestOperationDescriptor descriptor = descriptor(new SecurityPolicy.DenyAll(), List.of());

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertEquals(1, violations.size());
        assertEquals(
                SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY,
                violations.get(0).type());
    }

    @Test
    @DisplayName("@RolesAllowed (restrictive) with no security requirement produces a violation")
    void rolesAllowedPolicyWithoutRequirementReturnsViolation() {
        // Startup-consistency check: a restrictive annotation with no declared scheme means the
        // authentication handler will never run. (Reframed from the PRD's mis-specified
        // "check role against a SecurityContext" — this validator is a startup check with no
        // request/SecurityContext.)
        RestOperationDescriptor descriptor =
                descriptor(new SecurityPolicy.Constrained(List.of("admin"), List.of(), false), List.of());

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertEquals(1, violations.size());
        assertEquals(
                SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY,
                violations.get(0).type());
    }

    @Test
    @DisplayName("Declared security requirement without a matching handler produces a violation")
    void declaredSecurityWithoutHandlerReturnsViolation() {
        RestOperationDescriptor descriptor =
                descriptor(new SecurityPolicy.None(), List.of(new SecurityRequirement("bearerAuth", List.of())));

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertEquals(1, violations.size());
        assertEquals(
                SecurityPolicyViolation.ViolationType.OPENAPI_SECURITY_WITHOUT_HANDLER,
                violations.get(0).type());
    }

    @Test
    @DisplayName("Declared security requirement with a matching handler produces no violation")
    void declaredSecurityWithHandlerReturnsNoViolation() {
        SecuritySchemeHandler handler = mock(SecuritySchemeHandler.class);
        when(handler.schemeName()).thenReturn("bearerAuth");
        validator = new DefaultSecurityPolicyValidator(Set.of(handler));

        RestOperationDescriptor descriptor =
                descriptor(new SecurityPolicy.None(), List.of(new SecurityRequirement("bearerAuth", List.of())));

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("@PermitAll with a declared security requirement produces a conflicting-semantics violation")
    void permitAllWithRequirementReturnsConflict() {
        SecuritySchemeHandler handler = mock(SecuritySchemeHandler.class);
        when(handler.schemeName()).thenReturn("bearerAuth");
        validator = new DefaultSecurityPolicyValidator(Set.of(handler));

        RestOperationDescriptor descriptor =
                descriptor(new SecurityPolicy.PermitAll(), List.of(new SecurityRequirement("bearerAuth", List.of())));

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertEquals(1, violations.size());
        assertEquals(
                SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS,
                violations.get(0).type());
    }

    @Test
    @DisplayName("@RolesAllowed with a matching handler and declared requirement produces no violation")
    void rolesAllowedWithHandlerAndRequirementReturnsNoViolation() {
        SecuritySchemeHandler handler = mock(SecuritySchemeHandler.class);
        when(handler.schemeName()).thenReturn("bearerAuth");
        validator = new DefaultSecurityPolicyValidator(Set.of(handler));

        RestOperationDescriptor descriptor = descriptor(
                new SecurityPolicy.Constrained(List.of("user"), List.of(), false),
                List.of(new SecurityRequirement("bearerAuth", List.of())));

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("@AuthenticatedOnly without a security requirement produces a violation")
    void authenticatedOnlyWithoutRequirementReturnsViolation() {
        RestOperationDescriptor descriptor = descriptor(new SecurityPolicy.AuthenticatedOnly(), List.of());

        List<SecurityPolicyViolation> violations = validator.validate(descriptor, descriptor.securityPolicy());

        assertEquals(1, violations.size());
        assertEquals(
                SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY,
                violations.get(0).type());
    }

    // --- SH-2 fail-closed matrix ---

    @Test
    @DisplayName("Matrix: a multi-scheme (AND) set throws — combine() AND-groups are deferred")
    void multiSchemeSetThrows() {
        SecuritySchemeHandler apiKey = mock(SecuritySchemeHandler.class);
        when(apiKey.schemeName()).thenReturn("apiKey");
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(apiKey, oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.None(),
                List.of(new SecurityRequirementSet(List.of(
                        new SecurityRequirement("apiKey", List.of()), new SecurityRequirement("oauth2", List.of())))));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class, () -> validator.validate(descriptor, descriptor.securityPolicy()));
        assertTrue(ex.getMessage().contains("testOp"), "message must name the operationId");
    }

    @Test
    @DisplayName("Matrix: two single-scheme OR sets where one has scopes throws — scoped-OR is deferred")
    void scopedOrThrows() {
        SecuritySchemeHandler bearer = mock(SecuritySchemeHandler.class);
        when(bearer.schemeName()).thenReturn("bearerAuth");
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(bearer, oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.None(),
                List.of(
                        new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of()))),
                        new SecurityRequirementSet(List.of(new SecurityRequirement("oauth2", List.of("read"))))));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class, () -> validator.validate(descriptor, descriptor.securityPolicy()));
        assertTrue(ex.getMessage().contains("testOp"), "message must name the operationId");
    }

    @Test
    @DisplayName("Matrix: @Authorized(scopes) plus a scoped @SecurityRequirement throws — both-scopes is deferred")
    void bothScopesThrows() {
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.Constrained(List.of(), List.of("write"), true),
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("oauth2", List.of("read"))))));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class, () -> validator.validate(descriptor, descriptor.securityPolicy()));
        assertTrue(ex.getMessage().contains("testOp"), "message must name the operationId");
    }

    @Test
    @DisplayName("Matrix: @Authorized(roles) plus a scoped @SecurityRequirement passes — distinct authority kinds")
    void rolesPlusScopedRequirementPasses() {
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("oauth2", List.of("read"))))));

        assertDoesNotThrow(() -> validator.validate(descriptor, descriptor.securityPolicy()));
    }

    @Test
    @DisplayName("Matrix: a single-scheme scoped set passes — single-scheme scope enforcement is supported")
    void singleSchemeScopedPasses() {
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.None(),
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("oauth2", List.of("read"))))));

        assertDoesNotThrow(() -> validator.validate(descriptor, descriptor.securityPolicy()));
    }

    @Test
    @DisplayName("Matrix: a scopeless OR of single-scheme sets passes")
    void scopelessOrPasses() {
        SecuritySchemeHandler bearer = mock(SecuritySchemeHandler.class);
        when(bearer.schemeName()).thenReturn("bearerAuth");
        SecuritySchemeHandler oauth2 = mock(SecuritySchemeHandler.class);
        when(oauth2.schemeName()).thenReturn("oauth2");
        validator = new DefaultSecurityPolicyValidator(Set.of(bearer, oauth2));

        RestOperationDescriptor descriptor = descriptorWithSets(
                new SecurityPolicy.None(),
                List.of(
                        new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of()))),
                        new SecurityRequirementSet(List.of(new SecurityRequirement("oauth2", List.of())))));

        assertDoesNotThrow(() -> validator.validate(descriptor, descriptor.securityPolicy()));
    }

    // --- Helpers ---

    /**
     * Builds a minimal {@link RestOperationDescriptor} stub exposing only the policy and security
     * requirements the validator consumes; all other accessors return empty/identity values. Each
     * flat requirement is wrapped into a single-scheme {@link SecurityRequirementSet}, mirroring the
     * production descriptor-boundary wrap.
     *
     * @param policy       the resolved security policy for the operation
     * @param requirements the effective security requirements (scheme + scopes)
     * @return a stub descriptor for the test
     */
    private static RestOperationDescriptor descriptor(SecurityPolicy policy, List<SecurityRequirement> requirements) {
        List<SecurityRequirementSet> sets = requirements.stream()
                .map(requirement -> new SecurityRequirementSet(List.of(requirement)))
                .toList();
        return new StubDescriptor(policy, sets);
    }

    /**
     * Builds a minimal {@link RestOperationDescriptor} stub from pre-built {@link SecurityRequirementSet}s,
     * used by the matrix tests that need multi-scheme or multi-set shapes the single-scheme
     * {@link #descriptor(SecurityPolicy, List)} helper cannot express.
     *
     * @param policy the resolved security policy for the operation
     * @param sets   the effective security requirement sets (the OR alternatives)
     * @return a stub descriptor for the test
     */
    private static RestOperationDescriptor descriptorWithSets(
            SecurityPolicy policy, List<SecurityRequirementSet> sets) {
        return new StubDescriptor(policy, sets);
    }

    /**
     * Test-only {@link RestOperationDescriptor} carrying just a {@link SecurityPolicy} and the
     * effective {@link SecurityRequirementSet}s; every other accessor is an empty/identity stand-in.
     *
     * @param securityPolicy          the operation's resolved security policy
     * @param securityRequirementSets the operation's effective security requirement sets
     */
    private record StubDescriptor(SecurityPolicy securityPolicy, List<SecurityRequirementSet> securityRequirementSets)
            implements RestOperationDescriptor {

        @Override
        public String operationId() {
            return "testOp";
        }

        @Override
        public String httpMethod() {
            return "GET";
        }

        @Override
        public String routeTemplate() {
            return "/test";
        }

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }
}
