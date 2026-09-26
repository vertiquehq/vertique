// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.it.apps.resources.CatalogResource;
import dev.vertique.it.apps.resources.DisabledResource;
import dev.vertique.it.apps.resources.ExtraResource;
import dev.vertique.it.apps.resources.StatusResource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T003 TP-005: real, processor-generated code, across two compilation units ({@code resources} and
 * {@code app}), serves both {@link PublicApplication}'s and {@link ManagementApplication}'s mounts
 * over HTTP from {@link dev.vertique.rest.core.router.HttpVerticle} instances built by
 * {@link AppComponent}, with no handwritten registration or mount module.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TwoUnitApplicationsTest {

    /** Bound on every blocking wait this test performs. */
    private static final long AWAIT_TIMEOUT_SECONDS = 15L;

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        CatalogResource.reset();
        StatusResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    @DisplayName(
            "PublicApplication and ManagementApplication mounts serve across the resources and app "
                    + "compilation units; a path outside both application mounts 404s")
    void publicAndManagementMountsServeAcrossUnits() throws Exception {
        JsonObject config =
                new JsonObject()
                        .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                        .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        AppComponent component = DaggerAppComponent.factory().create(config);

        String deploymentId = await(vertx.deployVerticle(component::httpVerticle, new DeploymentOptions().setInstances(2)));
        assertNotNull(deploymentId, "the deployment must succeed");

        int port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        WebClient client = WebClient.create(vertx);
        try {
            HttpResponse<Buffer> catalogResponse = await(client.get(port, "127.0.0.1", "/api/public/catalog").send());
            assertEquals(200, catalogResponse.statusCode(), "GET /api/public/catalog must return 200");
            assertEquals(
                    "catalog", catalogResponse.bodyAsString(), "GET /api/public/catalog must return the catalog body");

            HttpResponse<Buffer> statusResponse = await(client.get(port, "127.0.0.1", "/api/mgmt/status").send());
            assertEquals(200, statusResponse.statusCode(), "GET /api/mgmt/status must return 200");

            HttpResponse<Buffer> rootCatalogResponse = await(client.get(port, "127.0.0.1", "/catalog").send());
            assertEquals(
                    404, rootCatalogResponse.statusCode(), "no default mount exists outside the two application paths");
        } finally {
            client.close();
        }

        assertEquals(
                0, ExtraResource.CONSTRUCTIONS.get(), "ExtraResource must never be constructed: no application selects it");
        assertEquals(
                0,
                DisabledResource.CONSTRUCTIONS.get(),
                "DisabledResource must never be constructed: its condition never matches and no application selects"
                        + " it");
    }

    /**
     * Blocks the calling (JUnit) thread for the given future's result, bounded by
     * {@link #AWAIT_TIMEOUT_SECONDS}.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's result
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
