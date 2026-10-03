// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TP-013 and TP-014: proves {@code ApplicationAnnotationAllowList}'s runtime re-check — the
 * allow list the annotation processor applies at compile time, re-checked by reflection over every
 * hand-written registration's declaring interface and superinterfaces, active or inactive, before
 * any resource is resolved (a registration the processor did not produce still fails startup).
 *
 * <p>Every row resolves {@link AllowListFixtures.AllowListComponent#routerMounts()} exactly once,
 * against a fresh component built from a registration the test builds inline with
 * {@link GeneratedRestApplicationRegistration#of}.
 */
class ApplicationAnnotationBackstopTest {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationAnnotationBackstopTest.class);

    /** R-007: TP-013's {@code @OpenAPIDefinition} row's reason fragment. */
    private static final String ONLY_INFO_ELEMENT_FRAGMENT = "only its info element";

    /** R-007: TP-014's every row's reason fragment. */
    private static final String HONORED_ONLY_ON_DECLARING_INTERFACE_FRAGMENT =
            "honored only on the declaring interface";

    /** G-007: the failure header every composer violation exception carries. */
    private static final String INVALID_COMPOSITION_HEADER = "Invalid JAX-RS application composition:";

    /** PIT G3: the reason fragment for a disallowed annotation refused the same way on any type in scope. */
    private static final String NOT_ALLOWED_FRAGMENT =
            "which is not allowed: application declarations carry no resource semantics";

    @BeforeEach
    void resetCounters() {
        AllowListFixtures.resetCounters();
    }

    /**
     * TP-013 — An annotation outside FR-025's allow list fails startup, wherever it sits.
     *
     * <p>Given: hand-written registrations, one per row: {@code @RolesAllowed} on the declaring
     * interface; {@code @Path} on the declaring interface; {@code @Singleton} on the declaring
     * interface; {@code @OpenAPIDefinition(servers = ...)} on the declaring interface;
     * {@code @RolesAllowed} on a direct superinterface, and on a superinterface two levels up; the
     * {@code @RolesAllowed} row again with the registration inactive; and the composing controls
     * {@code @RestApplication} alone, an info-only {@code @OpenAPIDefinition}, the test-source
     * {@code @ApiDocs}, and {@code @Deprecated}.
     *
     * <p>When: each component's {@code Set<RouterMount>} is resolved.
     *
     * <p>Then: every failing row throws {@link RestConfigurationException} naming the application
     * (name and declaring interface), the annotation's fully qualified name, and the type carrying
     * it; the {@code @OpenAPIDefinition} row never echoes {@code zq7}; no resource is constructed
     * before the failure; every control composes one mount.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allowListCases")
    @DisplayName("An annotation outside the allow list fails startup, wherever it sits")
    void annotationOutsideTheAllowListFailsStartup(BackstopCase testCase) {
        runCase(testCase, "TP-013");
    }

    /**
     * TP-014 — A runtime-retained honored-only annotation on a superinterface fails startup.
     *
     * <p>Given: hand-written registrations whose declaring interface carries only
     * {@code @RestApplication} and extends a superinterface carrying, one per row,
     * {@code @RestApplication(name = "other", ...)}, the test-source {@code @ApiDocs}, or an
     * info-only {@code @OpenAPIDefinition}; and the controls whose superinterface carries only
     * {@code @Deprecated}, or (PIT G4) only {@code java.lang.annotation} types via an
     * annotation-type superinterface.
     *
     * <p>When: each component's {@code Set<RouterMount>} is resolved.
     *
     * <p>Then: each failing row throws {@link RestConfigurationException} naming the application, the
     * annotation, and the superinterface; no resource is constructed before the failure; each control
     * composes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("honoredOnlyOnSuperinterfaceCases")
    @DisplayName("A runtime-retained honored-only annotation on a superinterface fails startup")
    void honoredOnlyAnnotationOnASuperinterfaceFailsStartup(BackstopCase testCase) {
        runCase(testCase, "TP-014");
    }

    private void runCase(BackstopCase testCase, String label) {
        GeneratedRestApplicationRegistration registration = GeneratedRestApplicationRegistration.of(
                testCase.declaringType(),
                testCase.registeredName(),
                AllowListFixtures.PATH,
                List.of(AllowListFixtures.AllowListResource.class),
                false,
                "",
                testCase.active());
        AllowListFixtures.AllowListComponent component = AllowListFixtures.component(registration);

        if (testCase.expectFailure()) {
            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class, component::routerMounts, testCase.name() + " must fail startup");
            LOG.info("{} {} failure: {}", label, testCase.name(), ex.getMessage());

            for (String fragment : testCase.expectedFragments()) {
                assertTrue(
                        ex.getMessage() != null && ex.getMessage().contains(fragment),
                        () -> testCase.name() + ": expected the failure to name '" + fragment + "': "
                                + ex.getMessage());
            }
            assertFalse(
                    ex.getMessage() != null && ex.getMessage().contains("zq7"),
                    () -> testCase.name() + ": the message must never echo a value: " + ex.getMessage());
            assertEquals(
                    0,
                    AllowListFixtures.resourceConstructions(),
                    () -> testCase.name() + ": no resource must be resolved before the failure");
        } else {
            Set<RouterMount> mounts = assertDoesNotThrow(component::routerMounts, testCase.name() + " must compose");
            assertEquals(1, mounts.size(), testCase.name() + ": exactly one application mount must compose");
        }
    }

    /**
     * TP-013's 11 rows: 7 failing (4 on the declaring interface, 2 on a superinterface, 1 inactive
     * repeat) and 4 composing controls.
     *
     * @return the 11 cases, in contract order
     */
    private static Stream<BackstopCase> allowListCases() {
        String rolesAllowed = RolesAllowed.class.getName();
        String pathAnnotation = Path.class.getName();
        String singleton = Singleton.class.getName();
        String openApiDefinition = OpenAPIDefinition.class.getName();

        return Stream.of(
                new BackstopCase(
                        "roles-on-declaring-interface",
                        AllowListFixtures.RolesOnDeclaringApi.class,
                        "backstop-roles",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-roles'",
                                rolesAllowed,
                                AllowListFixtures.RolesOnDeclaringApi.class.getName())),
                new BackstopCase(
                        "path-on-declaring-interface",
                        AllowListFixtures.PathOnDeclaringApi.class,
                        "backstop-path",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-path'",
                                pathAnnotation,
                                AllowListFixtures.PathOnDeclaringApi.class.getName())),
                new BackstopCase(
                        "singleton-on-declaring-interface",
                        AllowListFixtures.SingletonOnDeclaringApi.class,
                        "backstop-singleton",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-singleton'",
                                singleton,
                                AllowListFixtures.SingletonOnDeclaringApi.class.getName())),
                new BackstopCase(
                        "openapi-servers-on-declaring-interface",
                        AllowListFixtures.OpenApiServersOnDeclaringApi.class,
                        "backstop-openapi",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-openapi'",
                                openApiDefinition,
                                AllowListFixtures.OpenApiServersOnDeclaringApi.class.getName(),
                                ONLY_INFO_ELEMENT_FRAGMENT)),
                new BackstopCase(
                        "roles-on-direct-superinterface",
                        AllowListFixtures.RolesOnDirectSuperinterfaceApi.class,
                        "backstop-roles-super",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-roles-super'",
                                rolesAllowed,
                                AllowListFixtures.RolesOnDirectSuperinterfaceApi.class.getName(),
                                AllowListFixtures.DirectRolesSuperinterface.class.getName(),
                                NOT_ALLOWED_FRAGMENT)),
                new BackstopCase(
                        "roles-on-superinterface-two-levels-up",
                        AllowListFixtures.RolesOnSuperinterfaceTwoLevelsUpApi.class,
                        "backstop-roles-super2",
                        true,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-roles-super2'",
                                rolesAllowed,
                                AllowListFixtures.RolesOnSuperinterfaceTwoLevelsUpApi.class.getName(),
                                AllowListFixtures.TwoLevelsUpRolesSuperinterface.class.getName(),
                                NOT_ALLOWED_FRAGMENT)),
                new BackstopCase(
                        "inactive-roles-on-declaring-interface",
                        AllowListFixtures.RolesOnDeclaringApi.class,
                        "backstop-roles",
                        false,
                        true,
                        List.of(
                                INVALID_COMPOSITION_HEADER,
                                "'backstop-roles'",
                                rolesAllowed,
                                AllowListFixtures.RolesOnDeclaringApi.class.getName())),
                new BackstopCase(
                        "control-plain",
                        AllowListFixtures.PlainControlApi.class,
                        "backstop-control-plain",
                        true,
                        false,
                        List.of()),
                new BackstopCase(
                        "control-openapi-info-only",
                        AllowListFixtures.OpenApiInfoOnlyControlApi.class,
                        "backstop-control-openapi",
                        true,
                        false,
                        List.of()),
                new BackstopCase(
                        "control-apidocs",
                        AllowListFixtures.ApiDocsControlApi.class,
                        "backstop-control-apidocs",
                        true,
                        false,
                        List.of()),
                new BackstopCase(
                        "control-deprecated",
                        AllowListFixtures.DeprecatedControlApi.class,
                        "backstop-control-deprecated",
                        true,
                        false,
                        List.of()));
    }

    /**
     * TP-014's 5 rows: 3 failing (a superinterface carrying an annotation honored only on the
     * declaring interface) and 2 composing controls (a {@code java.lang} type and, PIT G4, a
     * {@code java.lang.annotation} type reached through an annotation-type superinterface).
     *
     * @return the 5 cases, in contract order
     */
    private static Stream<BackstopCase> honoredOnlyOnSuperinterfaceCases() {
        return Stream.of(
                new BackstopCase(
                        "rest-application-on-superinterface",
                        AllowListFixtures.RestApplicationOnSuperinterfaceApi.class,
                        "backstop-other-super",
                        true,
                        true,
                        List.of(
                                "'backstop-other-super'",
                                RestApplication.class.getName(),
                                AllowListFixtures.RestApplicationOnSuperinterfaceApi.class.getName(),
                                AllowListFixtures.OtherApplicationSuperinterface.class.getName(),
                                HONORED_ONLY_ON_DECLARING_INTERFACE_FRAGMENT)),
                new BackstopCase(
                        "apidocs-on-superinterface",
                        AllowListFixtures.ApiDocsOnSuperinterfaceApi.class,
                        "backstop-apidocs-super",
                        true,
                        true,
                        List.of(
                                "'backstop-apidocs-super'",
                                ApiDocs.class.getName(),
                                AllowListFixtures.ApiDocsOnSuperinterfaceApi.class.getName(),
                                AllowListFixtures.ApiDocsSuperinterface.class.getName(),
                                HONORED_ONLY_ON_DECLARING_INTERFACE_FRAGMENT)),
                new BackstopCase(
                        "openapi-on-superinterface",
                        AllowListFixtures.OpenApiOnSuperinterfaceApi.class,
                        "backstop-openapi-super",
                        true,
                        true,
                        List.of(
                                "'backstop-openapi-super'",
                                OpenAPIDefinition.class.getName(),
                                AllowListFixtures.OpenApiOnSuperinterfaceApi.class.getName(),
                                AllowListFixtures.OpenApiSuperinterface.class.getName(),
                                HONORED_ONLY_ON_DECLARING_INTERFACE_FRAGMENT)),
                new BackstopCase(
                        "control-deprecated-on-superinterface",
                        AllowListFixtures.DeprecatedOnSuperinterfaceApi.class,
                        "backstop-deprecated-super",
                        true,
                        false,
                        List.of()),
                new BackstopCase(
                        "control-java-lang-annotation-on-superinterface",
                        AllowListFixtures.MetaAnnotatedSuperinterfaceApi.class,
                        "backstop-meta-super",
                        true,
                        false,
                        List.of()));
    }

    /**
     * One TP-013/TP-014 row.
     *
     * @param name             the case's display name
     * @param declaringType    the declaring interface to register
     * @param registeredName   the application name to register it under
     * @param active           whether the registration is active
     * @param expectFailure    whether {@code routerMounts()} must fail
     * @param expectedFragments substrings the thrown message must contain (empty for a composing
     *                          row)
     */
    private record BackstopCase(
            String name,
            Class<?> declaringType,
            String registeredName,
            boolean active,
            boolean expectFailure,
            @Nullable List<String> expectedFragments) {

        @Override
        public String toString() {
            return name;
        }
    }
}
