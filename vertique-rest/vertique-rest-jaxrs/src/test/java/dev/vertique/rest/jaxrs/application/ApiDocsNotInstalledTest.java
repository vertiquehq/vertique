// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.publication.ApiDocsInstalled;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.ApiDocs;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
 * TP-012 (FR-037, AC-037.1): without the docs module — the optional {@code ApiDocsInstalled} empty
 * — the view logs one INFO line per active registration whose declaring interface carries an
 * annotation named {@link ApiDocsInstalled#ANNOTATION_NAME}, naming the application and stating
 * that documentation is not installed. With {@code ApiDocsInstalled} bound (the docs module
 * present), it logs nothing. The check runs once per component, when the view is built, not once
 * per resolution.
 */
class ApiDocsNotInstalledTest {

    /** The view's logger name, captured by string because {@code RestApplicationsBuilder} is package-private. */
    private static final String VIEW_LOGGER_NAME = "dev.vertique.rest.jaxrs.RestApplicationsBuilder";

    private Logger viewLogger;
    private Level previousViewLevel;
    private ListAppender<ILoggingEvent> viewAppender;

    @BeforeEach
    void captureViewLogs() {
        viewLogger = (Logger) LoggerFactory.getLogger(VIEW_LOGGER_NAME);
        previousViewLevel = viewLogger.getLevel();
        viewLogger.setLevel(Level.INFO);
        viewAppender = new ListAppender<>();
        viewAppender.start();
        viewLogger.addAppender(viewAppender);
    }

    @AfterEach
    void releaseViewLogs() {
        viewLogger.detachAppender(viewAppender);
        viewAppender.stop();
        viewLogger.setLevel(previousViewLevel);
    }

    @Test
    @DisplayName("Without the docs module, @ApiDocs logs one INFO line per active application; with it bound,"
            + " none; the check runs once per component, not once per resolution")
    void apiDocsWithoutTheDocsModuleLogsOneInfoPerActiveApplication() {
        assertEquals(
                "dev.vertique.rest.openapi.docs.ApiDocs",
                ApiDocsInstalled.ANNOTATION_NAME,
                "the constant must equal the real docs annotation's fully qualified name");
        assertEquals(
                ApiDocsInstalled.ANNOTATION_NAME,
                ApiDocs.class.getName(),
                "the test-source stand-in annotation's own name must equal the constant");

        JsonObject config = config();

        ComponentWithoutDocs withoutDocs =
                DaggerApiDocsNotInstalledTest_ComponentWithoutDocs.factory().create(config);
        Set<RouterMount> firstResolution = withoutDocs.routerMounts();
        Set<RouterMount> secondResolution = withoutDocs.routerMounts();

        List<String> infoMessages = viewAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        long docsAMentions = infoMessages.stream()
                .filter(message -> message.contains("docs-a"))
                .count();
        assertEquals(1, docsAMentions, () -> "exactly one INFO line across both resolutions: " + infoMessages);
        assertTrue(
                infoMessages.stream().noneMatch(message -> message.contains("docs-b")),
                () -> "the inactive docs-b must never be logged: " + infoMessages);
        assertTrue(
                infoMessages.stream().noneMatch(message -> message.contains("plain")),
                () -> "plain carries no @ApiDocs, so it must never be logged: " + infoMessages);

        assertMountsAreExactlyDocsAAndPlain(firstResolution);
        assertMountsAreExactlyDocsAAndPlain(secondResolution);

        viewAppender.list.clear();
        ComponentWithDocs withDocs =
                DaggerApiDocsNotInstalledTest_ComponentWithDocs.factory().create(config);
        Set<RouterMount> withDocsMounts = withDocs.routerMounts();

        List<String> withDocsInfoMessages = viewAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(
                withDocsInfoMessages.isEmpty(),
                () -> "with ApiDocsInstalled bound, nothing is logged: " + withDocsInfoMessages);
        assertMountsAreExactlyDocsAAndPlain(withDocsMounts);
    }

    private static void assertMountsAreExactlyDocsAAndPlain(Set<RouterMount> mounts) {
        assertEquals(2, mounts.size(), () -> "exactly docs-a and plain must mount: " + mounts);
        Set<String> names = mounts.stream()
                .map(mount ->
                        ApplicationMountTestAccess.applicationName(assertInstanceOf(JaxRsRouterMount.class, mount)))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("docs-a", "plain"), names);
    }

    private static JsonObject config() {
        return new JsonObject();
    }

    // --- Fixtures ---

    /** TP-012's active application whose declaring interface carries the test-source {@code @ApiDocs}. */
    @ApiDocs
    @RestApplication(name = "docs-a", path = "/docs-a", resources = DocsAResource.class)
    interface DocsAApi {}

    /** TP-012's resource for {@link DocsAApi}. */
    @Path("/docs-a")
    public static class DocsAResource {

        /** Public {@code @Inject} constructor. */
        @Inject
        public DocsAResource() {}

        /**
         * Handles {@code GET /docs-a}.
         *
         * @return the fixed body {@code "docs-a"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "docs-a";
        }
    }

    /**
     * TP-012's inactive application whose declaring interface also carries {@code @ApiDocs}: never
     * logged, since it never mounts.
     */
    @ApiDocs
    @RestApplication(name = "docs-b", path = "/docs-b", resources = DocsBResource.class)
    interface DocsBApi {}

    /** TP-012's resource for {@link DocsBApi}. Never constructed: {@code docs-b} is always inactive. */
    @Path("/docs-b")
    public static class DocsBResource {

        /** Public {@code @Inject} constructor. */
        @Inject
        public DocsBResource() {}

        /**
         * Handles {@code GET /docs-b}.
         *
         * @return the fixed body {@code "docs-b"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "docs-b";
        }
    }

    /** TP-012's active application whose declaring interface carries no {@code @ApiDocs}. */
    @RestApplication(name = "plain", path = "/plain", resources = PlainResource.class)
    interface PlainApi {}

    /** TP-012's resource for {@link PlainApi}. */
    @Path("/plain")
    public static class PlainResource {

        /** Public {@code @Inject} constructor. */
        @Inject
        public PlainResource() {}

        /**
         * Handles {@code GET /plain}.
         *
         * @return the fixed body {@code "plain"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "plain";
        }
    }

    /** TP-012's registrations and manual resource bindings, shared by both components. */
    @Module
    static final class ApiDocsRegistrationModule {

        private ApiDocsRegistrationModule() {}

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration docsARegistration() {
            return GeneratedRestApplicationRegistration.of(
                    DocsAApi.class, "docs-a", "/docs-a", List.of(DocsAResource.class), false, "", true);
        }

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration docsBRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    DocsBApi.class, "docs-b", "/docs-b", List.of(DocsBResource.class), false, "", false);
        }

        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration plainRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    PlainApi.class, "plain", "/plain", List.of(PlainResource.class), false, "", true);
        }

        @Provides
        @IntoSet
        @JaxRsResources
        static Object docsAResource(DocsAResource r) {
            return r;
        }

        @Provides
        @IntoSet
        @JaxRsResources
        static Object docsBResource(DocsBResource r) {
            return r;
        }

        @Provides
        @IntoSet
        @JaxRsResources
        static Object plainResource(PlainResource r) {
            return r;
        }
    }

    /** Binds a test {@link ApiDocsInstalled} implementation — standing in for the docs module being present. */
    @Module
    static final class ApiDocsInstalledModule {

        private ApiDocsInstalledModule() {}

        @Provides
        static ApiDocsInstalled apiDocsInstalled() {
            return new TestApiDocsInstalled();
        }
    }

    /** Marker implementation standing in for the real docs module's binding. */
    static final class TestApiDocsInstalled implements ApiDocsInstalled {}

    /** Component with no {@code ApiDocsInstalled} binding — the docs module is not in the component. */
    @Singleton
    @Component(modules = {RestModule.class, ApplicationTestSupportModule.class, ApiDocsRegistrationModule.class})
    interface ComponentWithoutDocs {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ComponentWithoutDocs create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Component with {@code ApiDocsInstalled} bound — standing in for the docs module being present. */
    @Singleton
    @Component(
            modules = {
                RestModule.class,
                ApplicationTestSupportModule.class,
                ApiDocsRegistrationModule.class,
                ApiDocsInstalledModule.class
            })
    interface ComponentWithDocs {

        /**
         * Resolves the {@code Set<RouterMount>} multibinding.
         *
         * @return the resolved mount set
         */
        Set<RouterMount> routerMounts();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            ComponentWithDocs create(@BindsInstance @VertxConfig JsonObject config);
        }
    }
}
