// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.diagnostics.DiagnosticsAccess;
import dev.vertique.rest.openapi.docs.publication.PublicationAccess;
import dev.vertique.security.authz.AccessPolicy;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit proof that the documentation mount derives the classification of a document registered as
 * public again from its declaring interface, so a hand-built document cannot publish a policy that
 * is not public. The classification of a document is normally derived by the selection of documents;
 * the mount does not trust it.
 */
class DocsRouterMountTest {

    private static final String PREFIX = "/apidocs";
    private static final String NAME = "internal";

    /** Anyone may read. */
    @PermitAll
    public interface Everyone extends AccessPolicy {}

    /** Nobody may read. */
    @DenyAll
    public interface Nobody extends AccessPolicy {}

    /** Declares a public document. */
    @ApiDocs(policy = Everyone.class)
    interface PublicDeclaration {}

    /** Declares a document whose policy denies every reader. */
    @ApiDocs(policy = Nobody.class, securityScheme = "bearerAuth")
    interface DenyDeclaration {}

    /** Carries no {@code @ApiDocs} at all. */
    interface UndeclaredType {}

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Control: a document registered as public whose policy is public gets its two routes")
    void publicDocumentWithPublicPolicyIsRegistered() {
        // Given: a public document whose declaring interface declares a public policy
        Router router = Router.router(vertx);

        // When: the mount builds its routes
        mount(PublicDeclaration.class).buildInto(router);

        // Then: the JSON and YAML routes exist
        assertEquals(2, router.getRoutes().size());
    }

    @Test
    @DisplayName("A document registered as public whose declared policy denies every reader fails before any route")
    void publicDocumentWhoseDeclarationIsNotPublicFailsClosed() {
        // Given: a hand-built public document over an interface whose policy denies everyone
        Router router = Router.router(vertx);
        DocsRouterMount mount = mount(DenyDeclaration.class);

        // When: the mount builds its routes
        RestConfigurationException failure =
                assertThrows(RestConfigurationException.class, () -> mount.buildInto(router));

        // Then: the failure names the application and its interface, and no route exists
        assertTrue(failure.getMessage().contains("'" + NAME + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains(DenyDeclaration.class.getName()), failure.getMessage());
        assertTrue(router.getRoutes().isEmpty(), "a route survived the failed build");
    }

    @Test
    @DisplayName("A document registered as public whose interface carries no @ApiDocs fails before any route")
    void publicDocumentWithoutDeclarationFailsClosed() {
        // Given: a hand-built public document over an interface with no @ApiDocs
        Router router = Router.router(vertx);
        DocsRouterMount mount = mount(UndeclaredType.class);

        // When: the mount builds its routes
        RestConfigurationException failure =
                assertThrows(RestConfigurationException.class, () -> mount.buildInto(router));

        // Then: it fails naming the interface, and no route exists
        assertTrue(failure.getMessage().contains(UndeclaredType.class.getName()), failure.getMessage());
        assertTrue(router.getRoutes().isEmpty(), "a route survived the failed build");
    }

    private static DocsRouterMount mount(Class<?> declaringType) {
        EnabledDocuments.EnabledDocument document = new EnabledDocuments.EnabledDocument(
                NAME, declaringType, ApiDocs.Access.PUBLIC, "/*", ContractOrigin.GLOBAL, null, null, null);
        DocsRouterMount mount = new DocsRouterMount(
                PREFIX,
                new EnabledDocuments(List.of(document), PREFIX),
                PublicationAccess.newStore(),
                "public, max-age=60",
                Set.of(),
                Optional.empty(),
                (router, path, methods, operation, terminal) -> {
                    throw new AssertionError("a public document installs no synthetic operation");
                },
                DiagnosticsAccess.documentWarnings());
        mount.markValidated();
        return mount;
    }
}
