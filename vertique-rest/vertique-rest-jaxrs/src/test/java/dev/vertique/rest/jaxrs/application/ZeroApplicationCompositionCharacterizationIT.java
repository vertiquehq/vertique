// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.application.legacy.CatalogResource;
import dev.vertique.rest.jaxrs.application.legacy.DaggerLegacyComponents_ResourceAndManualComponent;
import dev.vertique.rest.jaxrs.application.legacy.DisabledResource;
import dev.vertique.rest.jaxrs.application.legacy.ExtraResource;
import dev.vertique.rest.jaxrs.application.legacy.LegacyComponents;
import dev.vertique.rest.jaxrs.application.legacy.ManualResource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterizes today's zero-declaration composition under a real two-instance
 * {@link HttpVerticle} deployment: composition runs once per instance (plan pre-flight finding 1,
 * "composition runs once per {@code HttpVerticle} instance"), so the legacy-shaped and manual
 * resources construct once per instance, and the default mount serves the configured
 * {@code jaxrs.basePath} over loopback HTTP. This is the repository's first multi-instance
 * {@code HttpVerticle} deployment test (plan pre-flight finding 8).
 *
 * <p>This suite is green at the rest-024 branch-start commit with no production change and stays
 * green at every later rest-024 task commit; later tasks never edit its assertions. A later red is
 * an FR-001 regression, not an authorized flip.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ZeroApplicationCompositionCharacterizationIT {

    private static final JsonObject CONFIG = new JsonObject()
            .put("jaxrs", new JsonObject().put("basePath", "/api/*").put("validationStrategy", "none"))
            .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"));

    @BeforeEach
    void resetCounters() {
        CatalogResource.CONSTRUCTIONS.set(0);
        ExtraResource.CONSTRUCTIONS.set(0);
        DisabledResource.CONSTRUCTIONS.set(0);
        ManualResource.CONSTRUCTIONS.set(0);
    }

    /** Blocks the calling (JUnit) thread for the given future's result, bounded by the class timeout. */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /** Performs the pinned-loopback {@code GET /api/catalog} request and returns the response. */
    private static HttpResponse<Buffer> getCatalog(WebClient client, int port) throws Exception {
        return await(client.get(port, "127.0.0.1", "/api/catalog").send());
    }

    @Test
    @DisplayName("two HttpVerticle instances compose twice, constructing Catalog and Manual once per "
            + "instance, and serve GET /api/catalog over loopback")
    void twoInstancesConstructPerInstanceAndServe(Vertx vertx) throws Exception {
        LegacyComponents.ResourceAndManualComponent component =
                DaggerLegacyComponents_ResourceAndManualComponent.factory().create(CONFIG);
        Supplier<Verticle> supplier = component::httpVerticle;

        String deploymentId = await(vertx.deployVerticle(supplier, new DeploymentOptions().setInstances(2)));

        assertNotNull(deploymentId);
        assertEquals(2, CatalogResource.CONSTRUCTIONS.get(), "one composition per HttpVerticle instance");
        assertEquals(2, ManualResource.CONSTRUCTIONS.get(), "one composition per HttpVerticle instance");

        int port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        WebClient client = WebClient.create(vertx);
        try {
            HttpResponse<Buffer> response = getCatalog(client, port);
            assertEquals(200, response.statusCode());
            assertEquals("catalog", response.bodyAsString());
        } finally {
            client.close();
        }
    }
}
