// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.strategy.WebValidationPassThroughStrategy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The mount composition validator's shared-operationId warning: one WARN per operationId that more
 * than one mount uses where at least one is an application mount, naming each application by name
 * and each hand-built mount by path, and stating that controls keyed on the operationId alone must
 * also key on the application; nothing for ids no application mount shares, and nothing when the
 * validator's own checks refuse the composition.
 */
class SharedOperationIdWarningTest {

    /** The validator's logger, looked up by name because the validator class is package-private. */
    private static final String VALIDATOR_LOGGER_NAME = "dev.vertique.rest.jaxrs.JaxRsApplicationMountValidator";

    /** The statement every warning carries. */
    private static final String KEY_ON_APPLICATION =
            "Controls keyed on the operationId alone (audit capture bindings, custom validation strategies,"
                    + " interceptors) must also key on the application.";

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureValidatorLogs() {
        logger = (Logger) LoggerFactory.getLogger(VALIDATOR_LOGGER_NAME);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseValidatorLogs() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("Two applications whose distinct owners share 'list' compose and log one WARN naming both")
    void twoApplicationsSharingAnIdLogOneWarning() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource()),
                appMount("/api/partner/*", "partner", PartnerApp.class, new PartnerListResource()));

        assertTrue(validator().validate(mounts).isEmpty(), "distinct owners on two applications compose");

        assertEquals(
                List.of("OperationId 'list' is used by more than one JAX-RS mount: application 'partner', application"
                        + " 'public'. " + KEY_ON_APPLICATION),
                warnings());
    }

    @Test
    @DisplayName("An application beside a hand-built mount sharing 'list' logs one WARN naming the application by"
            + " name and the hand-built mount by path")
    void applicationBesideHandBuiltMountLogsOneWarning() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource()),
                handBuiltMount("/manual/*", new PartnerListResource()));

        assertTrue(validator().validate(mounts).isEmpty(), "an application and a hand-built mount compose");

        assertEquals(
                List.of("OperationId 'list' is used by more than one JAX-RS mount: application 'public', hand-built"
                        + " JAX-RS mount '/manual/*'. " + KEY_ON_APPLICATION),
                warnings());
    }

    @Test
    @DisplayName("One resource listed by two applications (the same owner) still logs the WARN")
    void sameOwnerReuseLogsOneWarning() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource()),
                appMount("/api/partner/*", "partner", PartnerApp.class, new PublicListResource()));

        assertTrue(validator().validate(mounts).isEmpty(), "the same owner composes");

        assertEquals(1, warnings().size(), () -> "expected one warning: " + warnings());
        String warning = warnings().get(0);
        assertTrue(warning.contains("'list'"), warning);
        assertTrue(warning.contains("application 'public'"), warning);
        assertTrue(warning.contains("application 'partner'"), warning);
    }

    @Test
    @DisplayName("Three mounts sharing 'list' log one WARN, not one per pair, and distinct ids log nothing")
    void oneWarningPerOperationIdAndNoneForDistinctIds() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource(), new OnlyHereResource()),
                appMount("/api/partner/*", "partner", PartnerApp.class, new PartnerListResource()),
                handBuiltMount("/manual/*", new ManualListResource()));

        assertTrue(validator().validate(mounts).isEmpty(), "every pair includes an application mount");

        assertEquals(
                List.of("OperationId 'list' is used by more than one JAX-RS mount: application 'partner', application"
                        + " 'public', hand-built JAX-RS mount '/manual/*'. " + KEY_ON_APPLICATION),
                warnings());
    }

    @Test
    @DisplayName("A refused composition (two hand-built mounts sharing 'list') logs no WARN")
    void refusedCompositionLogsNoWarning() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource()),
                handBuiltMount("/manual/one/*", new PartnerListResource()),
                handBuiltMount("/manual/two/*", new ManualListResource()));

        List<String> violations = validator().validate(mounts);

        assertEquals(1, violations.size(), () -> "expected the hand-built pair refusal: " + violations);
        assertTrue(violations.get(0).contains("'/manual/one/*'"), violations.get(0));
        assertTrue(violations.get(0).contains("'/manual/two/*'"), violations.get(0));
        assertFalse(warnings().stream().anyMatch(w -> w.contains("'list'")), () -> "no warning: " + warnings());
    }

    @Test
    @DisplayName("Two hand-built mounts listing one resource beside a declared, inactive application log no WARN,"
            + " since no application mount uses the id")
    void handBuiltOnlySharingLogsNoWarning() {
        MountCompositionValidator validator = ApplicationMountTestAccess.newValidator(
                new RestApplications(List.of(new RestApplications.Entry(
                        "inactive",
                        PublicApp.class,
                        false,
                        "/api/inactive/*",
                        null,
                        RestApplications.ContractOrigin.GLOBAL))),
                JaxRsConfig.builder().validationStrategy("web-validation").build(),
                Set.of(new WebValidationPassThroughStrategy()));
        List<RouterMount> mounts = List.of(
                handBuiltMount("/manual/one/*", new ManualListResource()),
                handBuiltMount("/manual/two/*", new ManualListResource()));

        assertTrue(validator.validate(mounts).isEmpty(), "the same owner on two hand-built mounts composes");
        assertEquals(List.of(), warnings());
    }

    @Test
    @DisplayName("Several shared ids log one WARN each, in operationId order")
    void severalSharedIdsLogInOperationIdOrder() {
        List<RouterMount> mounts = List.of(
                appMount("/api/public/*", "public", PublicApp.class, new PublicListResource(), new OnlyHereResource()),
                appMount(
                        "/api/partner/*",
                        "partner",
                        PartnerApp.class,
                        new PartnerListResource(),
                        new OnlyHereResource()));

        assertTrue(validator().validate(mounts).isEmpty(), "same-owner and distinct-owner reuse compose");

        List<String> warnings = warnings();
        assertEquals(2, warnings.size(), () -> "one WARN per shared id: " + warnings);
        assertTrue(warnings.get(0).startsWith("OperationId 'list' "), warnings.get(0));
        assertTrue(warnings.get(1).startsWith("OperationId 'onlyHere' "), warnings.get(1));
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static MountCompositionValidator validator() {
        return ApplicationMountTestAccess.newValidator(
                new RestApplications(List.of()),
                JaxRsConfig.builder().validationStrategy("web-validation").build(),
                Set.of(new WebValidationPassThroughStrategy()));
    }

    private static JaxRsRouterMount appMount(
            String mountPath, String name, Class<?> declaringType, Object... resources) {
        return ApplicationMountTestAccess.createApplicationMount(
                ApplicationMountTestAccess.factory(), mountPath, null, Set.of(resources), name, declaringType);
    }

    private static JaxRsRouterMount handBuiltMount(String mountPath, Object resource) {
        return ApplicationMountTestAccess.factory().create(mountPath, "openapi.json", Set.of(resource));
    }

    // --- Declaring-type fixtures (identity only, never constructed) ---

    private static final class PublicApp {}

    private static final class PartnerApp {}

    // --- Resource fixtures: three distinct owners of 'list' and one unshared id ---

    /** One owner of {@code list}. */
    @Path("/warn/public")
    public static class PublicListResource {

        /**
         * Handles {@code GET .../warn/public}.
         *
         * @return the fixed body {@code "public"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "public";
        }
    }

    /** A second owner of {@code list}. */
    @Path("/warn/partner")
    public static class PartnerListResource {

        /**
         * Handles {@code GET .../warn/partner}.
         *
         * @return the fixed body {@code "partner"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "partner";
        }
    }

    /** A third owner of {@code list}. */
    @Path("/warn/manual")
    public static class ManualListResource {

        /**
         * Handles {@code GET .../warn/manual}.
         *
         * @return the fixed body {@code "manual"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "manual";
        }
    }

    /** An operationId no other mount uses. */
    @Path("/warn/only-here")
    public static class OnlyHereResource {

        /**
         * Handles {@code GET .../warn/only-here}.
         *
         * @return the fixed body {@code "onlyHere"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String onlyHere() {
            return "onlyHere";
        }
    }
}
