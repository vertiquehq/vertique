// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-018 and TP-019: the view checks every declaring interface's runtime identity against its
 * registration before any resource resolves (AR3-005, S3-010).
 *
 * <p>TP-018 uses the real {@link GeneratedRestApplicationRegistration#of} factory: {@code "other"}
 * and a missing name are not reserved, so the factory accepts them, isolating the name-equality
 * check. TP-019 needs registrations the factory itself would refuse ({@code "none"}, {@code
 * "null"}), so it simulates them with a Mockito mock of the final registration class, every
 * accessor stubbed, with a declaring interface annotated with the SAME reserved name — isolating
 * the reserved-name check from the (passing) name-equality check.
 */
class DeclarationIdentityCheckTest {

    // --- TP-018 ---

    @Test
    @DisplayName("A declaring interface whose runtime @RestApplication.name() differs from its registration's"
            + " name, or that carries no @RestApplication at runtime, fails startup before any resource"
            + " resolves; a matching declaration composes")
    void declaredNameMustEqualTheRegistrationName() {
        JsonObject config = new JsonObject();

        // Row (a): the declaring interface's own @RestApplication says "other"; the registration says "api".
        MismatchedActiveComponent mismatchedActive =
                DaggerDeclarationIdentityCheckTest_MismatchedActiveComponent.factory()
                        .create(config);
        RestConfigurationException mismatchedActiveFailure = assertThrows(
                RestConfigurationException.class, mismatchedActive::routerMounts, "declared name must match");
        assertTrue(
                mismatchedActiveFailure.getMessage().contains(MismatchedApi.class.getName()),
                mismatchedActiveFailure.getMessage());
        assertTrue(mismatchedActiveFailure.getMessage().contains("api"), mismatchedActiveFailure.getMessage());
        assertTrue(mismatchedActiveFailure.getMessage().contains("other"), mismatchedActiveFailure.getMessage());

        // Row (b): the declaring interface carries no @RestApplication at all.
        NoAnnotationComponent noAnnotation = DaggerDeclarationIdentityCheckTest_NoAnnotationComponent.factory()
                .create(config);
        RestConfigurationException noAnnotationFailure = assertThrows(
                RestConfigurationException.class, noAnnotation::routerMounts, "a missing annotation must fail");
        assertTrue(
                noAnnotationFailure.getMessage().contains(NoAnnotationApi.class.getName()),
                noAnnotationFailure.getMessage());

        // Row (c): the same mismatch as (a), inactive.
        MismatchedInactiveComponent mismatchedInactive =
                DaggerDeclarationIdentityCheckTest_MismatchedInactiveComponent.factory()
                        .create(config);
        RestConfigurationException mismatchedInactiveFailure = assertThrows(
                RestConfigurationException.class,
                mismatchedInactive::routerMounts,
                "the check must run for an inactive registration too");
        assertTrue(
                mismatchedInactiveFailure.getMessage().contains(MismatchedApi.class.getName()),
                mismatchedInactiveFailure.getMessage());
        assertTrue(mismatchedInactiveFailure.getMessage().contains("api"), mismatchedInactiveFailure.getMessage());
        assertTrue(mismatchedInactiveFailure.getMessage().contains("other"), mismatchedInactiveFailure.getMessage());

        // Control: the declaring interface's own @RestApplication matches the registration's name.
        MatchingNameComponent control = DaggerDeclarationIdentityCheckTest_MatchingNameComponent.factory()
                .create(config);
        assertDoesNotThrow(control::routerMounts, "the control's declared name matches its registration's name");
    }

    // --- TP-019 ---

    @Test
    @DisplayName("The reserved names 'none' and 'null' are refused when the view is built, whether the"
            + " registration is active or not; an unreserved name composes")
    void reservedNamesAreRefused() {
        JsonObject config = new JsonObject();

        // Row (a): "none", active.
        GeneratedRestApplicationRegistration noneMock = reservedNameMock(NoneApi.class, "none", true);
        MockComponent noneComponent = DaggerDeclarationIdentityCheckTest_MockComponent.factory()
                .create(new MockRegistrationModule(noneMock), config);
        RestConfigurationException exNone =
                assertThrows(RestConfigurationException.class, noneComponent::routerMounts, "'none' must be refused");
        assertTrue(exNone.getMessage().contains("none"), exNone.getMessage());
        assertTrue(exNone.getMessage().contains(NoneApi.class.getName()), exNone.getMessage());

        // Row (b): "null", inactive.
        GeneratedRestApplicationRegistration nullMock = reservedNameMock(NullApi.class, "null", false);
        MockComponent nullComponent = DaggerDeclarationIdentityCheckTest_MockComponent.factory()
                .create(new MockRegistrationModule(nullMock), config);
        RestConfigurationException exNull = assertThrows(
                RestConfigurationException.class,
                nullComponent::routerMounts,
                "'null' must be refused even though it is inactive");
        assertTrue(exNull.getMessage().contains("null"), exNull.getMessage());
        assertTrue(exNull.getMessage().contains(NullApi.class.getName()), exNull.getMessage());

        // Control: "nonesuch" is not reserved.
        UnreservedNameComponent control = DaggerDeclarationIdentityCheckTest_UnreservedNameComponent.factory()
                .create(config);
        assertDoesNotThrow(control::routerMounts, "an unreserved name must compose");
    }

    /**
     * Builds a mock {@link GeneratedRestApplicationRegistration} the real factory would refuse:
     * every accessor stubbed, with a declaring interface annotated with the same {@code name}, so
     * only the reserved-name rule is exercised.
     *
     * @param declaringType the declaring interface, annotated {@code @RestApplication(name = name)}
     * @param name          the reserved name ({@code "none"} or {@code "null"})
     * @param active        the mocked {@code active()} value
     * @return the stubbed mock
     */
    private static GeneratedRestApplicationRegistration reservedNameMock(
            Class<?> declaringType, String name, boolean active) {
        GeneratedRestApplicationRegistration mock = mock(GeneratedRestApplicationRegistration.class);
        doReturn(declaringType).when(mock).declaringType();
        when(mock.name()).thenReturn(name);
        when(mock.path()).thenReturn("/" + name);
        when(mock.resources()).thenReturn(List.of());
        when(mock.discover()).thenReturn(true);
        when(mock.openapiPath()).thenReturn("");
        when(mock.active()).thenReturn(active);
        return mock;
    }

    // --- TP-018 fixtures ---

    /** Its runtime {@code @RestApplication.name()} ("other") differs from its registration's name ("api"). */
    @RestApplication(name = "other", path = "/api", discover = true)
    interface MismatchedApi {}

    /** Carries no {@code @RestApplication} annotation at all. */
    interface NoAnnotationApi {}

    /** Its runtime {@code @RestApplication.name()} matches its registration's name. */
    @RestApplication(name = "api", path = "/api", discover = true)
    interface ControlApi {}

    @Module
    static final class MismatchedActiveRegistrationModule {

        private MismatchedActiveRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    MismatchedApi.class, "api", "/api", List.of(), true, "", true);
        }
    }

    @Module
    static final class MismatchedInactiveRegistrationModule {

        private MismatchedInactiveRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    MismatchedApi.class, "api", "/api", List.of(), true, "", false);
        }
    }

    @Module
    static final class NoAnnotationRegistrationModule {

        private NoAnnotationRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    NoAnnotationApi.class, "api", "/api", List.of(), true, "", true);
        }
    }

    @Module
    static final class MatchingNameRegistrationModule {

        private MatchingNameRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(ControlApi.class, "api", "/api", List.of(), true, "", true);
        }
    }

    @Singleton
    @Component(
            modules = {RestModule.class, ApplicationTestSupportModule.class, MismatchedActiveRegistrationModule.class})
    interface MismatchedActiveComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            MismatchedActiveComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    @Singleton
    @Component(
            modules = {RestModule.class, ApplicationTestSupportModule.class, MismatchedInactiveRegistrationModule.class
            })
    interface MismatchedInactiveComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            MismatchedInactiveComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, NoAnnotationRegistrationModule.class})
    interface NoAnnotationComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            NoAnnotationComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, MatchingNameRegistrationModule.class})
    interface MatchingNameComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            MatchingNameComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    // --- TP-019 fixtures ---

    /** Its runtime {@code @RestApplication.name()} is the reserved {@code "none"}. */
    @RestApplication(name = "none", path = "/none", discover = true)
    interface NoneApi {}

    /** Its runtime {@code @RestApplication.name()} is the reserved {@code "null"}. */
    @RestApplication(name = "null", path = "/null", discover = true)
    interface NullApi {}

    /** Its runtime {@code @RestApplication.name()} is {@code "nonesuch"}, not reserved. */
    @RestApplication(name = "nonesuch", path = "/nonesuch", discover = true)
    interface NonesuchApi {}

    /** Contributes a single, test-supplied mock registration — TP-019's rows need one the real factory refuses. */
    @Module
    static final class MockRegistrationModule {

        private final GeneratedRestApplicationRegistration registration;

        MockRegistrationModule(GeneratedRestApplicationRegistration registration) {
            this.registration = registration;
        }

        @Provides
        @IntoSet
        GeneratedRestApplicationRegistration registration() {
            return registration;
        }
    }

    @Module
    static final class UnreservedNameRegistrationModule {

        private UnreservedNameRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return GeneratedRestApplicationRegistration.of(
                    NonesuchApi.class, "nonesuch", "/nonesuch", List.of(), true, "", true);
        }
    }

    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, MockRegistrationModule.class})
    interface MockComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            MockComponent create(
                    MockRegistrationModule mockRegistrationModule, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, UnreservedNameRegistrationModule.class})
    interface UnreservedNameComponent {

        Set<RouterMount> routerMounts();

        @Component.Factory
        interface Factory {
            UnreservedNameComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
