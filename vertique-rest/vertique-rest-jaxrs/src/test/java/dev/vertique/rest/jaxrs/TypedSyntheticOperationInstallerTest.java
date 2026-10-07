// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.TypedPolicies;
import dev.vertique.rest.security.DefaultSecurityPolicyValidator;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.ResourceRef;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Unit proofs for the package-private {@link DefaultSyntheticOperationInstaller} installing an
 * operation created from a typed access policy: the contributors receive the policy's effective
 * security policy and its actual required action, the descriptor reports the resolved requirement
 * annotations, and every condition a manual REST route would fail on rejects the operation before
 * the router gains any route. All cases use the real {@link DefaultSecurityPolicyValidator} except
 * where a case names another validator.
 *
 * <p>An action-only policy resolves to {@link SecurityPolicy.None} plus its action, the same shape a
 * manual route gets, so the real action-gate authentication contributor needs exactly one route
 * authentication handler; that composition rule is proved against the real contributors in {@code
 * TypedSyntheticOperationIT}, not here.
 */
class TypedSyntheticOperationInstallerTest {

    private static final String ORIGIN = "@ApiDocs on application 'typed'";
    private static final String OPERATION_ID = "apidocs:typed:json";
    private static final String APPLICATION = "typed";
    private static final String SCHEME = "bearerAuth";
    private static final String PATH = "/typed/openapi.json";
    private static final List<HttpMethod> METHODS = List.of(HttpMethod.GET, HttpMethod.HEAD);
    private static final Handler<RoutingContext> NOOP_TERMINAL =
            ctx -> ctx.response().end();
    private static final ActionRef ACTION = ActionRef.parse(TypedPolicies.ACTION);

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    // --- Contributors receive the policy and the actual action ---

    private static Stream<Arguments> installedPolicies() {
        Optional<ActionRef> noAction = Optional.empty();
        Optional<ActionRef> action = Optional.of(ACTION);
        return Stream.of(
                Arguments.of("public", TypedPolicies.Public.class, "", new SecurityPolicy.PermitAll(), noAction),
                Arguments.of("deny", TypedPolicies.Deny.class, SCHEME, new SecurityPolicy.DenyAll(), noAction),
                Arguments.of(
                        "authenticated",
                        TypedPolicies.Authenticated.class,
                        SCHEME,
                        new SecurityPolicy.AuthenticatedOnly(),
                        noAction),
                Arguments.of(
                        "roles",
                        TypedPolicies.Roles.class,
                        SCHEME,
                        new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                        noAction),
                Arguments.of(
                        "scopes",
                        TypedPolicies.Scopes.class,
                        SCHEME,
                        new SecurityPolicy.Constrained(List.of(), List.of("docs.read"), true),
                        noAction),
                Arguments.of("action only", TypedPolicies.Action.class, SCHEME, new SecurityPolicy.None(), action),
                Arguments.of(
                        "roles and action",
                        TypedPolicies.RolesAndAction.class,
                        SCHEME,
                        new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                        action),
                Arguments.of(
                        "roles, scopes and action",
                        TypedPolicies.Combined.class,
                        SCHEME,
                        new SecurityPolicy.Constrained(List.of("admin"), List.of("docs.read"), true),
                        action));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installedPolicies")
    @DisplayName("Contributors receive the policy's effective security policy and its actual action")
    void contributorsReceiveThePolicyAndTheActualAction(
            String label,
            Class<? extends AccessPolicy> policy,
            String scheme,
            SecurityPolicy expectedPolicy,
            Optional<ActionRef> expectedAction) {
        // Given a supported composition whose contributor records what it receives
        CapturingContributor contributor = new CapturingContributor();
        JaxRsRouterMount.Factory factory = supported(contributor).build();
        Router router = Router.router(vertx);

        // When an operation created from the typed policy is installed
        new DefaultSyntheticOperationInstaller(factory)
                .install(
                        router,
                        PATH,
                        METHODS,
                        SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, scheme, APPLICATION, policy),
                        NOOP_TERMINAL);

        // Then the contributor received the effective policy and the policy's actual action
        OperationRegistrationContext received = contributor.captured();
        assertEquals(expectedPolicy, received.securityPolicy(), "effective security policy");
        assertEquals(expectedAction, received.requiredAction(), "required action");

        // And the descriptor reports the same action and the resolved requirement annotations
        SyntheticOperationDescriptor descriptor =
                assertInstanceOf(SyntheticOperationDescriptor.class, received.operation());
        assertEquals(expectedAction, descriptor.requiredAction(), "descriptor requiredAction");
        List<Annotation> annotations = descriptor.methodAnnotations();
        List<Annotation> resolved = AccessPolicyResolver.resolve(policy);
        if (scheme.isEmpty()) {
            assertEquals(resolved, annotations, "an empty scheme adds no security requirement");
            assertTrue(descriptor.securityRequirementSets().isEmpty(), "no security requirement set");
        } else {
            assertEquals(resolved.size() + 1, annotations.size(), "the scheme requirement plus the resolved set");
            assertEquals(requirementOnScheme(), annotations.get(0), "the scheme requirement leads the annotation list");
            assertEquals(resolved, annotations.subList(1, annotations.size()), "then the resolved requirements");
            assertEquals(1, descriptor.securityRequirementSets().size(), "one security requirement set");
        }
        assertEquals(
                expectedAnnotationTypes(policy),
                Set.copyOf(resolved.stream().map(Annotation::annotationType).toList()),
                "the resolved requirements are exactly the policy's direct declarations");
        assertEquals(1, routesAt(router, PATH), "the operation is installed");
    }

    private static Set<Class<? extends Annotation>> expectedAnnotationTypes(Class<? extends AccessPolicy> policy) {
        if (policy == TypedPolicies.Public.class) {
            return Set.of(PermitAll.class);
        } else if (policy == TypedPolicies.Deny.class) {
            return Set.of(DenyAll.class);
        } else if (policy == TypedPolicies.Authenticated.class || policy == TypedPolicies.Scopes.class) {
            return Set.of(Authorized.class);
        } else if (policy == TypedPolicies.Roles.class) {
            return Set.of(RolesAllowed.class);
        } else if (policy == TypedPolicies.Action.class) {
            return Set.of(RequiresAction.class);
        } else if (policy == TypedPolicies.RolesAndAction.class) {
            return Set.of(RolesAllowed.class, RequiresAction.class);
        }
        return Set.of(RolesAllowed.class, Authorized.class, RequiresAction.class);
    }

    @Test
    @DisplayName("The legacy operations still report no required action and keep their two annotations")
    void aLegacyOperationStillReportsNoRequiredAction() {
        // Given the supported composition
        CapturingContributor contributor = new CapturingContributor();
        JaxRsRouterMount.Factory factory = supported(contributor).build();
        DefaultSyntheticOperationInstaller installer = new DefaultSyntheticOperationInstaller(factory);
        Router router = Router.router(vertx);

        // When the two legacy operations are installed
        installer.install(
                router,
                "/legacy/roles.json",
                METHODS,
                SyntheticOperation.withRoles(ORIGIN, "legacy:roles", SCHEME, APPLICATION, List.of("admin")),
                NOOP_TERMINAL);
        installer.install(
                router,
                "/legacy/authenticated.json",
                METHODS,
                SyntheticOperation.authenticated(ORIGIN, "legacy:authenticated", SCHEME, APPLICATION),
                NOOP_TERMINAL);

        // Then neither the contributor nor the descriptor reports an action, and each descriptor keeps
        // its two-annotation shape
        for (String operationId : List.of("legacy:roles", "legacy:authenticated")) {
            OperationRegistrationContext received = contributor.captured(operationId);
            assertEquals(Optional.empty(), received.requiredAction(), operationId + " contributor action");
            SyntheticOperationDescriptor descriptor =
                    assertInstanceOf(SyntheticOperationDescriptor.class, received.operation());
            assertEquals(Optional.empty(), descriptor.requiredAction(), operationId + " descriptor action");
            assertEquals(2, descriptor.methodAnnotations().size(), operationId + " descriptor annotations");
        }
    }

    // --- Public operations need no scheme and no security module ---

    @Test
    @DisplayName("A public operation with the empty scheme installs without a scheme handler or a security module")
    void aPublicOperationWithTheEmptySchemeNeedsNoSecurityComposition() {
        // Given a composition with no scheme handler, no auth enforcement and no policy validator
        CapturingContributor contributor = new CapturingContributor();
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .operationHandlerContributors(Set.of(contributor))
                .build();
        Router router = Router.router(vertx);

        // When a public operation with the empty scheme is installed
        new DefaultSyntheticOperationInstaller(factory)
                .install(
                        router,
                        PATH,
                        METHODS,
                        SyntheticOperation.withPolicy(
                                ORIGIN, OPERATION_ID, "", APPLICATION, TypedPolicies.Public.class),
                        NOOP_TERMINAL);

        // Then it is installed as a public operation naming no security requirement
        assertEquals(1, routesAt(router, PATH), "the public operation is installed");
        OperationRegistrationContext received = contributor.captured();
        assertEquals(new SecurityPolicy.PermitAll(), received.securityPolicy());
        assertEquals(Optional.empty(), received.requiredAction());
        assertTrue(received.operation().securityRequirementSets().isEmpty());
    }

    @Test
    @DisplayName("With no policy validator bound, a public operation naming a scheme installs and is handed "
            + "to the contributors as public")
    void aPublicOperationWithASchemeInstallsWhenNoPolicyValidatorIsBound() {
        // Given the supported composition except that no security policy validator is bound
        CapturingContributor contributor = new CapturingContributor();
        JaxRsRouterMount.Factory factory =
                supported(contributor).securityPolicyValidator(null).build();
        Router router = Router.router(vertx);

        // When a public operation naming a registered scheme is installed
        new DefaultSyntheticOperationInstaller(factory)
                .install(
                        router,
                        PATH,
                        METHODS,
                        SyntheticOperation.withPolicy(
                                ORIGIN, OPERATION_ID, SCHEME, APPLICATION, TypedPolicies.Public.class),
                        NOOP_TERMINAL);

        // Then nothing rejects the combination: it is installed and the contributor sees public access
        // with the scheme's one security requirement set
        assertEquals(1, routesAt(router, PATH), "the operation is installed");
        assertEquals(new SecurityPolicy.PermitAll(), contributor.captured().securityPolicy());
        assertEquals(
                1, contributor.captured().operation().securityRequirementSets().size());
    }

    @Test
    @DisplayName("A deny operation with a registered scheme installs and is handed to the contributors as deny")
    void aDenyOperationWithASchemeInstalls() {
        // Given the supported composition
        CapturingContributor contributor = new CapturingContributor();
        JaxRsRouterMount.Factory factory = supported(contributor).build();
        Router router = Router.router(vertx);

        // When a deny operation naming the registered scheme is installed
        new DefaultSyntheticOperationInstaller(factory)
                .install(
                        router,
                        PATH,
                        METHODS,
                        SyntheticOperation.withPolicy(
                                ORIGIN, OPERATION_ID, SCHEME, APPLICATION, TypedPolicies.Deny.class),
                        NOOP_TERMINAL);

        // Then it is installed, with the scheme's requirement, for the contributors to enforce
        assertEquals(1, routesAt(router, PATH), "the deny operation is installed");
        assertEquals(new SecurityPolicy.DenyAll(), contributor.captured().securityPolicy());
        assertEquals(
                1, contributor.captured().operation().securityRequirementSets().size());
    }

    // --- Refusals leave the router without a route ---

    private static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(
                        "an action the registry does not register",
                        factory(TypedSyntheticOperationInstallerTest::registryWithoutTheAction),
                        policy(TypedPolicies.Action.class, SCHEME),
                        routeViolations(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID)),
                Arguments.of(
                        "a roles and action policy whose action the registry does not register",
                        factory(TypedSyntheticOperationInstallerTest::registryWithoutTheAction),
                        policy(TypedPolicies.RolesAndAction.class, SCHEME),
                        routeViolations(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID)),
                Arguments.of(
                        "an action with no action registry",
                        factory(builder -> builder.actionRegistry(Optional.empty())),
                        policy(TypedPolicies.Action.class, SCHEME),
                        routeViolations(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID)),
                Arguments.of(
                        "an action with no authorizer",
                        factory(builder -> builder.authorizer(Optional.empty())),
                        policy(TypedPolicies.Action.class, SCHEME),
                        routeViolations(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID)),
                Arguments.of(
                        "an action with no auth enforcement module",
                        factory(builder -> builder.authEnforcementCapability(Optional.empty())
                                .securityPolicyValidator(null)),
                        policy(TypedPolicies.Action.class, SCHEME),
                        routeViolations(RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID)),
                Arguments.of(
                        "roles with no auth enforcement module",
                        factory(builder -> builder.authEnforcementCapability(Optional.empty())
                                .securityPolicyValidator(null)),
                        policy(TypedPolicies.Roles.class, SCHEME),
                        routeViolations(
                                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE)),
                Arguments.of(
                        "deny with no auth enforcement module",
                        factory(builder -> builder.authEnforcementCapability(Optional.empty())
                                .securityPolicyValidator(null)),
                        policy(TypedPolicies.Deny.class, SCHEME),
                        routeViolations(
                                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE)),
                Arguments.of(
                        "a scheme with no handler",
                        factory(builder -> {}),
                        policy(TypedPolicies.Roles.class, "nope"),
                        policyViolations(SecurityPolicyViolation.ViolationType.OPENAPI_SECURITY_WITHOUT_HANDLER)),
                Arguments.of(
                        "public with a supplied scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Public.class, SCHEME),
                        policyViolations(SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS)),
                Arguments.of(
                        "public with a blank scheme, which counts as supplied",
                        factory(builder -> {}),
                        policy(TypedPolicies.Public.class, "  "),
                        malformedRequirementRefusal()),
                Arguments.of(
                        "deny with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Deny.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "authenticated with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Authenticated.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "roles with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Roles.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "scopes with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Scopes.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "roles and action with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.RolesAndAction.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "combined with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Combined.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.ANNOTATION_WITHOUT_OPENAPI_SECURITY)),
                Arguments.of(
                        "action only with the empty scheme",
                        factory(builder -> {}),
                        policy(TypedPolicies.Action.class, ""),
                        authenticationHandlerRefusal("")),
                Arguments.of(
                        "roles and action with the empty scheme and no policy validator",
                        factory(builder -> builder.securityPolicyValidator(null)),
                        policy(TypedPolicies.RolesAndAction.class, ""),
                        authenticationHandlerRefusal("")),
                Arguments.of(
                        "roles and action with no enforcement runtime and no policy validator",
                        factory(builder -> builder.authEnforcementCapability(Optional.empty())
                                .securityPolicyValidator(null)),
                        policy(TypedPolicies.RolesAndAction.class, SCHEME),
                        routeViolations(
                                RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE)),
                Arguments.of(
                        "combined with no enforcement runtime and no policy validator",
                        factory(builder -> builder.authEnforcementCapability(Optional.empty())
                                .securityPolicyValidator(null)),
                        policy(TypedPolicies.Combined.class, SCHEME),
                        routeViolations(
                                RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE)),
                Arguments.of(
                        "a custom validator rejecting a public operation",
                        factory(builder -> builder.securityPolicyValidator(rejectingValidator())),
                        policy(TypedPolicies.Public.class, ""),
                        policyViolations(SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS)),
                Arguments.of(
                        "a custom validator rejecting a roles operation",
                        factory(builder -> builder.securityPolicyValidator(rejectingValidator())),
                        policy(TypedPolicies.Roles.class, SCHEME),
                        policyViolations(SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    @DisplayName("A misconfigured policy operation is refused before the router gains any route")
    void aMisconfiguredPolicyOperationIsRefusedBeforeAnyRoute(
            String label,
            Supplier<JaxRsRouterMount.Factory> factory,
            Supplier<SyntheticOperation> operation,
            Consumer<RestConfigurationException> expectedRefusal) {
        // Given a composition missing something the operation needs, over a router that would show any route
        Router spyRouter = Mockito.spy(Router.router(vertx));

        // When the operation is installed
        RestConfigurationException refusal = assertThrows(
                RestConfigurationException.class, () -> new DefaultSyntheticOperationInstaller(factory.get())
                        .install(spyRouter, PATH, METHODS, operation.get(), NOOP_TERMINAL));

        // Then the refusal starts with the operation's origin, carries the expected cause, and no route exists
        assertTrue(
                refusal.getMessage().startsWith(ORIGIN),
                "the refusal must start with the origin but was: " + refusal.getMessage());
        expectedRefusal.accept(refusal);
        verifyNoRoute(spyRouter);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AccessPolicy> notAnInterface() {
        return (Class<? extends AccessPolicy>) (Class<?>) String.class;
    }

    private static Stream<Arguments> invalidPolicies() {
        return Stream.of(
                Arguments.of("a class that is not an interface", notAnInterface()),
                Arguments.of("the marker itself", AccessPolicy.class),
                Arguments.of("a policy declaring no requirement", NoRequirement.class),
                Arguments.of("a policy allowing an empty role list", EmptyRoles.class),
                Arguments.of("a policy allowing a blank role", BlankRole.class),
                Arguments.of("a policy mixing public and deny", PublicAndDeny.class),
                Arguments.of("a policy mixing public and an action", PublicAndAction.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicies")
    @DisplayName("An invalid policy is refused as an illegal argument wrapped with the origin, before any route")
    void anInvalidPolicyIsRefusedBeforeAnyRoute(String label, Class<? extends AccessPolicy> policy) {
        // Given the supported composition and an operation created, unjudged, from an invalid policy
        Router spyRouter = Mockito.spy(Router.router(vertx));
        SyntheticOperation operation = SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, SCHEME, APPLICATION, policy);

        // When it is installed
        RestConfigurationException refusal =
                assertThrows(RestConfigurationException.class, () -> new DefaultSyntheticOperationInstaller(
                                supported(new CapturingContributor()).build())
                        .install(spyRouter, PATH, METHODS, operation, NOOP_TERMINAL));

        // Then the resolver's illegal argument is the cause, the origin leads the message, and no route exists
        assertTrue(
                refusal.getMessage().startsWith(ORIGIN),
                "the refusal must start with the origin but was: " + refusal.getMessage());
        assertInstanceOf(IllegalArgumentException.class, refusal.getCause());
        verifyNoRoute(spyRouter);
    }

    // --- Fixtures ---

    /** A policy declaring no requirement. */
    public interface NoRequirement extends AccessPolicy {}

    /** A policy allowing an empty role list. */
    @RolesAllowed({})
    public interface EmptyRoles extends AccessPolicy {}

    /** A policy allowing a blank role. */
    @RolesAllowed(" ")
    public interface BlankRole extends AccessPolicy {}

    /** A policy declaring both public and deny. */
    @PermitAll
    @DenyAll
    public interface PublicAndDeny extends AccessPolicy {}

    /** A policy declaring public together with an action. */
    @PermitAll
    @RequiresAction(TypedPolicies.ACTION)
    public interface PublicAndAction extends AccessPolicy {}

    /** A method carrying a scheme requirement, to build the annotation an operation reports. */
    static final class RequirementCarrier {
        @SecurityRequirement(name = SCHEME)
        void guarded() {}
    }

    private static Annotation requirementOnScheme() {
        try {
            return RequirementCarrier.class.getDeclaredMethod("guarded").getAnnotation(SecurityRequirement.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static TestFactories.Builder supported(CapturingContributor contributor) {
        SchemeHandler handler = new SchemeHandler(SCHEME);
        return TestFactories.builder()
                .securitySchemeHandlers(Set.of(handler))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .securityPolicyValidator(new DefaultSecurityPolicyValidator(Set.of(handler)))
                .actionRegistry(Optional.of(new Registry(ACTION)))
                .authorizer(Optional.of(new PermittingAuthorizer()))
                .operationHandlerContributors(Set.of(contributor));
    }

    private static Supplier<JaxRsRouterMount.Factory> factory(Consumer<TestFactories.Builder> customization) {
        return () -> {
            TestFactories.Builder builder = supported(new CapturingContributor());
            customization.accept(builder);
            return builder.build();
        };
    }

    private static void registryWithoutTheAction(TestFactories.Builder builder) {
        builder.actionRegistry(Optional.of(new Registry(ActionRef.parse("typed.doc.other"))));
    }

    private static Supplier<SyntheticOperation> policy(Class<? extends AccessPolicy> policy, String scheme) {
        return () -> SyntheticOperation.withPolicy(ORIGIN, OPERATION_ID, scheme, APPLICATION, policy);
    }

    private static dev.vertique.rest.core.security.SecurityPolicyValidator rejectingValidator() {
        return (RestOperationDescriptor operation, SecurityPolicy policy) -> List.of(new SecurityPolicyViolation(
                operation.operationId(),
                SecurityPolicyViolation.ViolationType.CONFLICTING_SEMANTICS,
                "custom rejection"));
    }

    /**
     * The refusal of a blank scheme: the requirement scanner rejects the malformed requirement, with
     * a configuration failure as the cause, before the policy validator is consulted.
     */
    private static Consumer<RestConfigurationException> malformedRequirementRefusal() {
        return refusal -> {
            assertInstanceOf(RestConfigurationException.class, refusal.getCause());
            assertTrue(
                    refusal.getMessage().contains("malformed @SecurityRequirement"),
                    "the refusal must name the malformed requirement but was: " + refusal.getMessage());
        };
    }

    /**
     * The refusal of a protected operation whose scheme has no authentication handler: it carries no
     * structured cause and names the scheme.
     */
    private static Consumer<RestConfigurationException> authenticationHandlerRefusal(String scheme) {
        return refusal -> {
            assertNull(refusal.getCause(), "a missing authentication handler is not a structured violation");
            assertTrue(
                    refusal.getMessage().contains("security scheme '" + scheme + "'"),
                    "the refusal must name the scheme but was: " + refusal.getMessage());
        };
    }

    private static Consumer<RestConfigurationException> routeViolations(
            RouteRegistrationViolation.ViolationType... types) {
        return refusal -> assertEquals(List.of(types), TypedSyntheticInstallProbe.routeViolationTypes(refusal));
    }

    private static Consumer<RestConfigurationException> policyViolations(
            SecurityPolicyViolation.ViolationType... types) {
        return refusal -> assertEquals(List.of(types), TypedSyntheticInstallProbe.policyViolationTypes(refusal));
    }

    private static void verifyNoRoute(Router spyRouter) {
        verify(spyRouter, never()).route(anyString());
        verify(spyRouter, never()).route(any(HttpMethod.class), anyString());
        verify(spyRouter, never()).routeWithRegex(any(HttpMethod.class), anyString());
        verify(spyRouter, never()).routeWithRegex(anyString());
    }

    private static long routesAt(Router router, String path) {
        return router.getRoutes().stream()
                .filter(route -> path.equals(route.getPath()))
                .count();
    }

    // --- Test doubles ---

    /** A scheme handler registering a pass-through authentication handler. */
    private static final class SchemeHandler implements SecuritySchemeHandler {
        private final String schemeName;

        SchemeHandler(String schemeName) {
            this.schemeName = schemeName;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(RoutingContext::next);
        }
    }

    /** An action registry knowing exactly one action. */
    private static final class Registry implements ActionRegistry {
        private final ActionRef known;

        Registry(ActionRef known) {
            this.known = known;
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(known));
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return known.value().equals(action.value());
        }
    }

    /** An authorizer that permits everything; it is only present, never asked, in these unit proofs. */
    private static final class PermittingAuthorizer implements Authorizer {
        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }
    }

    /** Captures every context it contributes to, keyed by operation id. */
    private static final class CapturingContributor implements OperationHandlerContributor {
        private final Map<String, OperationRegistrationContext> captured = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            captured.put(context.operationId(), context);
            order.add(context.operationId());
        }

        OperationRegistrationContext captured() {
            return captured(OPERATION_ID);
        }

        OperationRegistrationContext captured(String operationId) {
            OperationRegistrationContext context = captured.get(operationId);
            assertNotNull(context, "the operation '" + operationId + "' must have been contributed to");
            assertEquals(1, order.stream().filter(operationId::equals).count(), "contributed to exactly once");
            return context;
        }
    }
}
