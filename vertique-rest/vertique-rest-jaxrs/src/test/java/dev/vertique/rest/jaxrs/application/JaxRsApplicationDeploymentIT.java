// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unitb.PublicApplication;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * Proves {@code JaxRsApplicationComposer}'s C-COMPOSE behavior under a real, loopback-bound,
 * multi-instance {@link HttpVerticle} deployment (TP-010 to TP-013): per-instance evaluation and
 * serving, the wrapped-failure and re-entry-free failed-deployment path, and the unselected-report
 * logging cadence. Built from L01's shared {@code unita} and {@code unitb} fixture modules through
 * {@link DeploymentComponents}.
 *
 * <p>The composer's logger is captured by its fully qualified name, {@link #COMPOSER_LOGGER_NAME},
 * because {@code JaxRsApplicationComposer} is package-private: this test class, in the
 * {@code application} subpackage, cannot reference it directly. Observed texts (failure messages,
 * cause chains, warnings) are logged through this class's own logger so they land in the Failsafe
 * {@code *-output.txt} for evidence.
 *
 * <p><strong>R10 bounding.</strong> Vert.x 5.1.6 catches only {@link Exception} around the verticle
 * supplier it invokes inside {@code deployVerticle(...)}; a {@link LinkageError} (TP-011 variant b,
 * before the composer wraps it) would otherwise escape that call synchronously. {@link #deploy} wraps
 * every {@code deployVerticle} call in a {@code catch (Throwable)} that converts such an escape into
 * a failed {@link Future}, so it surfaces as a red assertion rather than a hang or an unhandled
 * {@code <error>}.
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
        PublicApplication.reset();
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
            "Two HttpVerticle instances each evaluate their applications once and serve their own selected resources; a path outside every application mount 404s")
    void twoInstancesEvaluateAndServePerInstance(Vertx vertx) throws Exception {
        JsonObject config = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        DeploymentComponents.StandardComponent component = standardComponent(config);

        String deploymentId = await(deploy(vertx, component::httpVerticle, new DeploymentOptions().setInstances(2)));
        assertNotNull(deploymentId, "the deployment must succeed");

        assertEquals(
                2,
                PublicApplication.GET_CLASSES_CALLS.get(),
                "PublicApplication.getClasses() is called exactly once per HttpVerticle instance");
        assertEquals(2, CatalogResource.CONSTRUCTIONS.get(), "Catalog is constructed exactly once per composition");

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
            "A throwing PublicApplication.getClasses() fails deployment as a RestConfigurationException naming PublicApplication and /api/public, for both RuntimeException and NoClassDefFoundError")
    void throwingGetClassesFailsDeploymentNamingApplication(Vertx vertx) throws Exception {
        assertThrowingVariantFailsDeployment(
                vertx, PublicApplication.MODE_THROW_RUNTIME_EXCEPTION, RuntimeException.class, "(a) RuntimeException");
        assertThrowingVariantFailsDeployment(
                vertx,
                PublicApplication.MODE_THROW_NO_CLASS_DEF_FOUND_ERROR,
                NoClassDefFoundError.class,
                "(b) NoClassDefFoundError");
    }

    /**
     * Runs one TP-011 variant: deploys with {@link PublicApplication} configured to throw via
     * {@code mode}, and asserts the wrapped failure, the absent {@code http.port}, and that no
     * selected generated resource of any application was constructed.
     *
     * @param vertx             the test's {@link Vertx} instance
     * @param mode              {@link PublicApplication#MODE_CONFIG_KEY} value selecting the throw
     * @param expectedCauseType the original throwable type the wrapping {@link RestConfigurationException}
     *                          must carry as its cause
     * @param label             the variant's display label, used in every assertion message
     */
    private void assertThrowingVariantFailsDeployment(
            Vertx vertx, String mode, Class<? extends Throwable> expectedCauseType, String label) throws Exception {
        resetCounters();
        clearPublishedPort(vertx);

        JsonObject config = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.MODE_CONFIG_KEY,
                mode);
        DeploymentComponents.StandardComponent component = standardComponent(config);

        AsyncResult<String> outcome = awaitOutcome(deploy(vertx, component::httpVerticle, new DeploymentOptions()));

        assertTrue(outcome.failed(), () -> label + ": the deployment must fail");
        LOG.info("TP-011 {} failure chain: {}", label, describeChain(outcome.cause()));

        RestConfigurationException restEx = assertInstanceOf(
                RestConfigurationException.class,
                outcome.cause(),
                () -> label + ": the deployment failure cause must be a RestConfigurationException");
        assertTrue(
                restEx.getMessage().contains(PublicApplication.class.getSimpleName()),
                () -> label + ": message must name PublicApplication: " + restEx.getMessage());
        assertTrue(
                restEx.getMessage().contains("/api/public"),
                () -> label + ": message must name /api/public: " + restEx.getMessage());
        assertInstanceOf(
                expectedCauseType,
                restEx.getCause(),
                () -> label + ": the RestConfigurationException's cause must be the original throwable");

        assertNull(
                vertx.sharedData().getLocalMap("vertique").get("http.port"),
                () -> label + ": no http.port must be published after a failed deployment");
        assertEquals(0, CatalogResource.CONSTRUCTIONS.get(), label + ": Catalog must never be constructed");
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), label + ": Extra must never be constructed");
    }

    @Test
    @DisplayName(
            "The unselected-resource report logs once per HttpVerticle composition, each naming every enabled catalog entry and manual contribution no active application selected")
    void unselectedReportLogsOncePerComposition(Vertx vertx) throws Exception {
        JsonObject config = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        DeploymentComponents.UnselectedReportComponent component = unselectedReportComponent(config);

        String deploymentId = await(deploy(vertx, component::httpVerticle, new DeploymentOptions().setInstances(2)));
        assertNotNull(deploymentId, "the deployment must succeed");

        List<String> unselectedWarnings = composerMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains("not selected by any Application"))
                .toList();
        LOG.info("TP-012 unselected warnings: {}", unselectedWarnings);
        assertEquals(2, unselectedWarnings.size(), "exactly one step-10 warning per composition (2 instances)");
        for (String warning : unselectedWarnings) {
            assertTrue(
                    warning.contains(ExtraResource.class.getSimpleName()), () -> "must name ExtraResource: " + warning);
            assertTrue(
                    warning.contains(BlobLikeResource.class.getSimpleName()),
                    () -> "must name BlobLikeResource: " + warning);
        }
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), "the unselected Extra provider must never be called");
    }

    @Test
    @DisplayName(
            "A computed empty getClasses() selection fails deployment naming PublicApplication and /api/public, with no fallback to discovery")
    void computedEmptySelectionFailsNamingApplication(Vertx vertx) throws Exception {
        clearPublishedPort(vertx);

        JsonObject config = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.MODE_CONFIG_KEY,
                PublicApplication.MODE_EMPTY);
        DeploymentComponents.StandardComponent component = standardComponent(config);

        AsyncResult<String> outcome = awaitOutcome(deploy(vertx, component::httpVerticle, new DeploymentOptions()));

        assertTrue(outcome.failed(), "an empty getClasses() selection must fail deployment");
        LOG.info("TP-013 failure chain: {}", describeChain(outcome.cause()));

        RestConfigurationException restEx = assertInstanceOf(
                RestConfigurationException.class,
                outcome.cause(),
                "the deployment failure cause must be a RestConfigurationException");
        assertTrue(
                restEx.getMessage().contains(PublicApplication.class.getSimpleName()),
                () -> "message must name PublicApplication: " + restEx.getMessage());
        assertTrue(
                restEx.getMessage().contains("/api/public"),
                () -> "message must name /api/public: " + restEx.getMessage());

        assertNull(
                vertx.sharedData().getLocalMap("vertique").get("http.port"),
                "no http.port must be published after a failed deployment, and no fallback to discovery occurs");
    }

    // --- Component-building helpers ---

    private static DeploymentComponents.StandardComponent standardComponent(JsonObject config) {
        return DaggerDeploymentComponents_StandardComponent.factory().create(config);
    }

    private static DeploymentComponents.UnselectedReportComponent unselectedReportComponent(JsonObject config) {
        return DaggerDeploymentComponents_UnselectedReportComponent.factory().create(config);
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
     * Clears the published {@code http.port} shared-data entry, so a later "absent" assertion is
     * meaningful even if an earlier deployment on the same {@link Vertx} published one.
     *
     * @param vertx the test's {@link Vertx} instance
     */
    private static void clearPublishedPort(Vertx vertx) {
        vertx.sharedData().getLocalMap("vertique").remove("http.port");
    }

    /**
     * Bounds {@code vertx.deployVerticle(supplier, options)} against a synchronously escaping
     * {@link Throwable} (R10): Vert.x 5.1.6 catches only {@link Exception} around the supplier it
     * invokes while constructing verticle instances, so an unwrapped {@link Error} (a
     * {@link LinkageError}, before the composer wraps it) would otherwise propagate out of this call
     * instead of failing the returned {@link Future}.
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
     * class timeout. Throws if the future fails: only used for deployments this suite expects to
     * succeed.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's result
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Blocks the calling (JUnit) thread for the given future's outcome, bounded by the class
     * timeout, without throwing on failure: used for deployments this suite expects to fail, so the
     * failure itself can be asserted.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's outcome, successful or failed
     */
    private static <T> AsyncResult<T> awaitOutcome(Future<T> future) throws Exception {
        CompletableFuture<AsyncResult<T>> outcome = new CompletableFuture<>();
        future.onComplete(outcome::complete);
        return outcome.get(15, TimeUnit.SECONDS);
    }

    // --- Exception-chain helper ---

    private static String describeChain(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (!text.isEmpty()) {
                text.append(" -> ");
            }
            text.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
        }
        return text.toString();
    }

    // --- Log-capture helper ---

    private List<String> composerMessagesAt(Level level) {
        return composerAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
