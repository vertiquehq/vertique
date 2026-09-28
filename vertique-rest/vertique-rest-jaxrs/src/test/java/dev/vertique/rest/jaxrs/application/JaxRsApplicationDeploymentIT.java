// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.strategy.OpenApiContractPassThroughStrategy;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Proves native application composition under a real, loopback-bound {@link HttpVerticle}
 * deployment: per-instance evaluation and serving (TP-001's deployment half), the unselected-report
 * logging cadence, and TP-007, each application's gates using its own resolved contract location.
 * Built from {@link DeploymentComponents}.
 *
 * <p>The composer's logger is captured by its fully qualified name, {@link #COMPOSER_LOGGER_NAME},
 * because {@code JaxRsApplicationComposer} is package-private: this test class, in the
 * {@code application} subpackage, cannot reference it directly.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class JaxRsApplicationDeploymentIT {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JaxRsApplicationDeploymentIT.class);

    /** The composer's fully qualified logger name, captured by name because the class is package-private. */
    private static final String COMPOSER_LOGGER_NAME = "dev.vertique.rest.jaxrs.JaxRsApplicationComposer";

    private Logger composerLogger;
    private Level previousComposerLevel;
    private ListAppender<ILoggingEvent> composerAppender;

    @BeforeEach
    void resetCounters() {
        CatalogResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
        BlobLikeResource.reset();
    }

    @BeforeEach
    void captureComposerLogs() {
        composerLogger = (Logger) LoggerFactory.getLogger(COMPOSER_LOGGER_NAME);
        previousComposerLevel = composerLogger.getLevel();
        composerLogger.setLevel(Level.INFO);
        composerAppender = new ListAppender<>();
        composerAppender.start();
        composerLogger.addAppender(composerAppender);
    }

    @AfterEach
    void releaseComposerLogs() {
        composerLogger.detachAppender(composerAppender);
        composerAppender.stop();
        composerLogger.setLevel(previousComposerLevel);
    }

    @Test
    @DisplayName(
            "Two HttpVerticle instances each compose their applications once and serve their own selected resources; a path outside every application mount 404s")
    void twoInstancesEvaluateAndServePerInstance(Vertx vertx) throws Exception {
        JsonObject config =
                deploymentConfig("unitb.publicApplication.active", true, "unitb.managementApplication.active", true);
        DeploymentComponents.StandardComponent component = standardComponent(config);

        String deploymentId = await(deploy(vertx, component::httpVerticle, new DeploymentOptions().setInstances(2)));
        assertNotNull(deploymentId, "the deployment must succeed");

        assertEquals(2, CatalogResource.CONSTRUCTIONS.get(), "Catalog is constructed exactly once per composition");
        assertEquals(2, ExtraResource.CONSTRUCTIONS.get(), "Extra is constructed exactly once per composition");

        int port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        WebClient client = WebClient.create(vertx);
        try {
            HttpResponse<Buffer> catalogResponse =
                    await(client.get(port, "127.0.0.1", "/api/public/catalog").send());
            assertEquals(200, catalogResponse.statusCode());
            assertEquals("catalog", catalogResponse.bodyAsString());

            HttpResponse<Buffer> extraResponse =
                    await(client.get(port, "127.0.0.1", "/api/mgmt/extra").send());
            assertEquals(200, extraResponse.statusCode());

            HttpResponse<Buffer> rootCatalogResponse =
                    await(client.get(port, "127.0.0.1", "/catalog").send());
            assertEquals(404, rootCatalogResponse.statusCode(), "no mount exists outside the two application paths");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName(
            "The unselected-resource report logs once per HttpVerticle composition, each naming every enabled catalog entry and manual contribution no active application selected")
    void unselectedReportLogsOncePerComposition(Vertx vertx) throws Exception {
        JsonObject config = deploymentConfig("unitb.publicApplication.active", true);
        DeploymentComponents.UnselectedReportComponent component = unselectedReportComponent(config);

        String deploymentId = await(deploy(vertx, component::httpVerticle, new DeploymentOptions().setInstances(2)));
        assertNotNull(deploymentId, "the deployment must succeed");

        List<String> unselectedWarnings = composerMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains("not selected by any Application"))
                .toList();
        LOG.info("unselected-report warnings: {}", unselectedWarnings);
        assertEquals(2, unselectedWarnings.size(), "exactly one step-10 warning per composition (2 instances)");
        for (String warning : unselectedWarnings) {
            assertTrue(
                    warning.contains(ExtraResource.class.getSimpleName()), () -> "must name ExtraResource: " + warning);
        }
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), "the unselected Extra provider must never be called");
    }

    /**
     * TP-007 — Gates use each application's own contract location (AC-028.1, AC-029.1).
     *
     * <p>Given: {@code jaxrs.validationStrategy} {@code openapi-contract}, served by the test-source
     * pass-through strategy, which records every {@code bindToMount} location and, for each gate,
     * the pair (operationId, {@code mountMeta.openapiPath()}); {@code jaxrs.openapiPath}
     * {@code global.yaml}; registrations {@code a} ({@code a.yaml}), {@code b} ({@code ""}), and
     * {@code c} ({@code c.yaml}, overridden by {@code jaxrs.applications.c.openapiPath}
     * {@code c-config.yaml}), each with one operation of a distinct id; deployed.
     *
     * <p>When: one {@code GET} is sent to each application's operation.
     *
     * <p>Then: each answers 200; the per-request records are (opA, {@code a.yaml}), (opB,
     * {@code global.yaml}), and (opC, {@code c-config.yaml}); {@code bindToMount} saw exactly those
     * three locations.
     */
    @Test
    @DisplayName("Each application's contract gates use that application's own resolved contract location")
    void openapiContractGatesUseEachApplicationsContract(Vertx vertx) throws Exception {
        JsonObject config = deploymentConfig(
                "jaxrs.validationStrategy",
                "openapi-contract",
                "jaxrs.openapiPath",
                "global.yaml",
                "jaxrs.applications.c.openapiPath",
                "c-config.yaml");
        DeploymentComponents.ContractLocationComponent component = contractLocationComponent(config);

        String deploymentId = await(deploy(vertx, component::httpVerticle, new DeploymentOptions()));
        assertNotNull(deploymentId, "the deployment must succeed");

        int port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        WebClient client = WebClient.create(vertx);
        try {
            HttpResponse<Buffer> a =
                    await(client.get(port, "127.0.0.1", "/api/a/contract/a").send());
            assertEquals(200, a.statusCode());
            HttpResponse<Buffer> b =
                    await(client.get(port, "127.0.0.1", "/api/b/contract/b").send());
            assertEquals(200, b.statusCode());
            HttpResponse<Buffer> c =
                    await(client.get(port, "127.0.0.1", "/api/c/contract/c").send());
            assertEquals(200, c.statusCode());
        } finally {
            client.close();
        }

        OpenApiContractPassThroughStrategy strategy = component.openApiContractPassThroughStrategy();
        List<Map.Entry<String, String>> requestTimeLocations = strategy.requestTimeLocations();
        LOG.info("TP-007 per-request locations: {}", requestTimeLocations);
        assertEquals(
                List.of(Map.entry("opA", "a.yaml"), Map.entry("opB", "global.yaml"), Map.entry("opC", "c-config.yaml")),
                requestTimeLocations,
                "each request must record its own application's resolved contract location");
        assertEquals(
                Set.of("a.yaml", "global.yaml", "c-config.yaml"),
                Set.copyOf(strategy.bindToMountLocations()),
                "bindToMount must see exactly the three resolved locations");
    }

    // --- Component-building helpers ---

    private static DeploymentComponents.StandardComponent standardComponent(JsonObject config) {
        return DaggerDeploymentComponents_StandardComponent.factory().create(config);
    }

    private static DeploymentComponents.UnselectedReportComponent unselectedReportComponent(JsonObject config) {
        return DaggerDeploymentComponents_UnselectedReportComponent.factory().create(config);
    }

    private static DeploymentComponents.ContractLocationComponent contractLocationComponent(JsonObject config) {
        return DaggerDeploymentComponents_ContractLocationComponent.factory().create(config);
    }

    // --- Configuration-literal helper ---

    /**
     * Builds a deployment configuration {@link JsonObject} from loopback defaults
     * ({@code http.port=0}, {@code http.host=127.0.0.1}, {@code jaxrs.validationStrategy=none})
     * plus dotted-path key/value overrides, so each test's configuration reads as a flat table.
     *
     * @param dottedKeyValuePairs alternating dotted-path key ({@link String}) and value arguments
     * @return the assembled configuration object
     */
    private static JsonObject deploymentConfig(Object... dottedKeyValuePairs) {
        JsonObject root = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        for (int i = 0; i < dottedKeyValuePairs.length; i += 2) {
            putDotted(root, (String) dottedKeyValuePairs[i], dottedKeyValuePairs[i + 1]);
        }
        return root;
    }

    private static void putDotted(JsonObject root, String dottedKey, Object value) {
        String[] segments = dottedKey.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonObject next = current.getJsonObject(segments[i]);
            if (next == null) {
                next = new JsonObject();
                current.put(segments[i], next);
            }
            current = next;
        }
        current.put(segments[segments.length - 1], value);
    }

    // --- Deployment helpers ---

    /**
     * Bounds {@code vertx.deployVerticle(supplier, options)} against a synchronously escaping
     * {@link Throwable} (R10).
     *
     * @param vertx    the test's {@link Vertx} instance
     * @param supplier creates a fresh {@link Verticle} instance per deployed instance
     * @param options  the deployment options
     * @return the deployment future, failed with the escaping throwable if one occurred
     */
    private static Future<String> deploy(Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options) {
        try {
            return vertx.deployVerticle(supplier, options);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Blocks the calling (JUnit) thread for the given future's successful result, bounded by the
     * class timeout.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's result
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    // --- Log-capture helper ---

    private List<String> composerMessagesAt(Level level) {
        return composerAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
