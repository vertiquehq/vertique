// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TP-003: proves the composer step 1a runtime backstop — the same annotation allow list the
 * compile-time {@code ApplicationAnnotationValidator} enforces, re-checked by reflection over every
 * hand-written registration's class hierarchy, active or inactive, before any application is
 * constructed or any resource is resolved.
 *
 * <p>Every row resolves {@link AllowListFixtures.AllowListComponent#routerMounts()} exactly once,
 * against a fresh component ({@link AllowListFixtures#component(Class, boolean)}), never calling
 * {@code createRouter}: the mount's validated mark and the routing-base-path warning are both
 * irrelevant to step 1a, which runs long before either. The case list mirrors the compile-time
 * allow-list's rows in their runtime form: 10 active failing rows, 1 inactive failing row (the
 * sole registration), and 3 composing rows that guard against over-rejection.
 */
class ApplicationAnnotationBackstopTest {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationAnnotationBackstopTest.class);

    @BeforeEach
    void resetCounters() {
        AllowListFixtures.resetCounters();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("A hand-written registration outside the allow list fails startup, active or not")
    void handWrittenRegistrationWithResourceAnnotationFailsStartup(Tp003Case testCase) {
        AllowListFixtures.AllowListComponent component =
                AllowListFixtures.component(testCase.applicationType(), testCase.active());

        if (testCase.expectFailure()) {
            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class, component::routerMounts, testCase.name() + " must fail startup");
            LOG.info("TP-003 {} failure: {}", testCase.name(), ex.getMessage());
            assertEquals(
                    RestConfigurationException.class,
                    ex.getClass(),
                    () -> testCase.name() + ": expected exactly RestConfigurationException, got " + ex.getClass());

            String expectedMessage = expectedMessage(testCase);
            if (testCase.pinExactMessage()) {
                assertEquals(
                        "Invalid JAX-RS application composition:\n- " + expectedMessage,
                        ex.getMessage(),
                        () -> testCase.name() + ": expected the message to equal the pinned RL-2 template, got: "
                                + ex.getMessage());
            } else {
                assertTrue(
                        ex.getMessage() != null && ex.getMessage().contains(expectedMessage),
                        () -> testCase.name() + ": expected the message to contain '" + expectedMessage + "', got: "
                                + ex.getMessage());
            }

            assertEquals(
                    0,
                    AllowListFixtures.applicationConstructions(),
                    () -> testCase.name() + ": no application must be constructed before a step 1a violation fails");
            assertEquals(
                    0,
                    AllowListFixtures.resourceConstructions(),
                    () -> testCase.name() + ": no resource must be resolved before a step 1a violation fails");
        } else {
            Set<RouterMount> mounts = assertDoesNotThrow(component::routerMounts, testCase.name() + " must compose");
            LOG.info("TP-003 {} composed {} mount(s)", testCase.name(), mounts.size());
            assertEquals(1, mounts.size(), testCase.name() + ": exactly one application mount must compose");

            RouterMount mount = mounts.iterator().next();
            JaxRsRouterMount jaxRsMount = assertInstanceOf(
                    JaxRsRouterMount.class, mount, testCase.name() + ": every mount here is a JaxRsRouterMount");
            assertEquals(
                    testCase.applicationType(),
                    ApplicationMountTestAccess.applicationType(jaxRsMount),
                    () -> testCase.name() + ": the mount's applicationType() must equal the registered application");
            assertEquals(
                    Set.of(AllowListFixtures.AllowListResource.class),
                    jaxRsMount.meta().resourceTypes(),
                    () -> testCase.name() + ": the mount's resources must be exactly the listed resource");
        }
    }

    /**
     * Builds the allow-list violation message {@code testCase} must contain (or, when {@link
     * Tp003Case#pinExactMessage()}, be the sole line of the composer's step-one aggregate, {@code
     * "Invalid JAX-RS application composition:\n- <message>"}): {@code "Application <A> carries @<annotation>
     * on <declaring type>, which is not allowed: application classes carry no resource semantics"},
     * with the {@code "; only its info element may be set"} suffix for the
     * {@code openapi-tags-non-default} row.
     *
     * @param testCase a failing row
     * @return the expected message text
     */
    private static String expectedMessage(Tp003Case testCase) {
        String base = ("Application %s carries @%s on %s, which is not allowed: application classes carry no"
                        + " resource semantics")
                .formatted(
                        testCase.applicationType().getName(),
                        testCase.annotationFqn(),
                        testCase.declaringType().getName());
        return testCase.expectOpenApiSuffix() ? base + "; only its info element may be set" : base;
    }

    /**
     * TP-003's 14 named rows: 10 active failing rows, 1 inactive failing row, and 3 composing
     * rows.
     *
     * @return the 14 cases, in contract order
     */
    private static Stream<Tp003Case> cases() {
        String rolesAllowed = RolesAllowed.class.getName();
        String permitAll = PermitAll.class.getName();
        String securityRequirement = SecurityRequirement.class.getName();
        String jsonProfile = JsonProfile.class.getName();
        String auditStandIn = AllowListFixtures.AuditStandIn.class.getName();
        String applicationPath = ApplicationPath.class.getName();
        String openApiDefinition = OpenAPIDefinition.class.getName();

        return Stream.of(
                new Tp003Case(
                        "roles-on-application",
                        AllowListFixtures.RolesOnApplicationApplication.class,
                        true,
                        true,
                        AllowListFixtures.RolesOnApplicationApplication.class,
                        rolesAllowed,
                        false,
                        true),
                new Tp003Case(
                        "roles-on-abstract-superclass",
                        AllowListFixtures.RolesOnAbstractSuperclassApplication.class,
                        true,
                        true,
                        AllowListFixtures.GuardedSuperclassApplication.class,
                        rolesAllowed,
                        false,
                        false),
                new Tp003Case(
                        "roles-on-interface",
                        AllowListFixtures.RolesOnInterfaceApplication.class,
                        true,
                        true,
                        AllowListFixtures.GuardedInterface.class,
                        rolesAllowed,
                        false,
                        false),
                new Tp003Case(
                        "roles-on-superinterface",
                        AllowListFixtures.RolesOnSuperinterfaceApplication.class,
                        true,
                        true,
                        AllowListFixtures.AnnotatedRootInterface.class,
                        rolesAllowed,
                        false,
                        false),
                new Tp003Case(
                        "permitall",
                        AllowListFixtures.PermitAllApplication.class,
                        true,
                        true,
                        AllowListFixtures.PermitAllApplication.class,
                        permitAll,
                        false,
                        false),
                new Tp003Case(
                        "security-requirement",
                        AllowListFixtures.SecurityRequirementApplication.class,
                        true,
                        true,
                        AllowListFixtures.SecurityRequirementApplication.class,
                        securityRequirement,
                        false,
                        false),
                new Tp003Case(
                        "json-profile",
                        AllowListFixtures.JsonProfileApplication.class,
                        true,
                        true,
                        AllowListFixtures.JsonProfileApplication.class,
                        jsonProfile,
                        false,
                        false),
                new Tp003Case(
                        "audit-stand-in",
                        AllowListFixtures.AuditStandInApplication.class,
                        true,
                        true,
                        AllowListFixtures.AuditStandInApplication.class,
                        auditStandIn,
                        false,
                        false),
                new Tp003Case(
                        "application-path-only-on-interface",
                        AllowListFixtures.ApplicationPathOnlyOnInterfaceApplication.class,
                        true,
                        true,
                        AllowListFixtures.PathContract.class,
                        applicationPath,
                        false,
                        false),
                new Tp003Case(
                        "openapi-tags-non-default",
                        AllowListFixtures.OpenApiTagsNonDefaultApplication.class,
                        true,
                        true,
                        AllowListFixtures.OpenApiTagsNonDefaultApplication.class,
                        openApiDefinition,
                        true,
                        false),
                new Tp003Case(
                        "inactive-roles-on-application",
                        AllowListFixtures.RolesOnApplicationApplication.class,
                        false,
                        true,
                        AllowListFixtures.RolesOnApplicationApplication.class,
                        rolesAllowed,
                        false,
                        false),
                new Tp003Case(
                        "allow-listed",
                        AllowListFixtures.AllowListedApplication.class,
                        true,
                        false,
                        null,
                        null,
                        false,
                        false),
                new Tp003Case(
                        "openapi-tags-empty",
                        AllowListFixtures.OpenApiTagsEmptyApplication.class,
                        true,
                        false,
                        null,
                        null,
                        false,
                        false),
                new Tp003Case(
                        "member-annotation-control",
                        AllowListFixtures.MemberAnnotationControlApplication.class,
                        true,
                        false,
                        null,
                        null,
                        false,
                        false));
    }

    /**
     * One TP-003 row: the application type to register, its activity flag, whether {@code
     * routerMounts()} must fail, and — for a failing row — the exact declaring type and annotation
     * FQN the violation message must name, whether the OpenAPIDefinition suffix is expected, and
     * whether the message is pinned exactly (roles-on-application only).
     *
     * @param name                the case's display name
     * @param applicationType     the application type to register
     * @param active              whether the registration is active
     * @param expectFailure       whether {@code routerMounts()} must fail with a {@link
     *                            RestConfigurationException}
     * @param declaringType       the type the violation message must name as carrying the
     *                            offending annotation ({@code null} for a composing row)
     * @param annotationFqn       the offending annotation's fully qualified name ({@code null} for
     *                            a composing row)
     * @param expectOpenApiSuffix whether the message must end with "; only its info element may be
     *                            set"
     * @param pinExactMessage     whether the message must equal the message template exactly,
     *                            rather than merely contain it
     */
    private record Tp003Case(
            String name,
            Class<? extends Application> applicationType,
            boolean active,
            boolean expectFailure,
            @Nullable Class<?> declaringType,
            @Nullable String annotationFqn,
            boolean expectOpenApiSuffix,
            boolean pinExactMessage) {

        @Override
        public String toString() {
            return name;
        }
    }
}
