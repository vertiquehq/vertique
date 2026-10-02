// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.rest.openapi.docs.CompleteDocumentTestComponents.RefComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.RefEntriesApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.determinism.ByteDifferences;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests.Answer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Serves the public document of the complete-feature application {@code ref} from one loopback-bound
 * {@code HttpVerticle} instance and compares its exact JSON and YAML bytes and both entity tags with
 * pinned literals: the classpath resources {@value #GOLDEN_JSON} and {@value #GOLDEN_YAML}, and the
 * constants {@link #JSON_ENTITY_TAG} and {@link #YAML_ENTITY_TAG}.
 *
 * <p>The golden files and tags are pinned from an observed green run and reviewed against the
 * document writer's member order and the fixture's feature list. Thereafter they are literals: they
 * are never regenerated from production output without a recorded reason.
 *
 * <p>Each Maven run forks a fresh JVM for its integration tests (the failsafe default), so the served
 * bytes are compared in a fresh JVM on every run, with whatever hash-iteration order that JVM
 * chooses.
 *
 * <p>On any mismatch, or when a golden resource is missing, the test first writes the actual bytes
 * and tags to {@code target/golden-actual/} ({@code complete-document.json}, {@code
 * complete-document.yaml}, {@code entity-tags.txt}) so a reviewer can inspect and pin them, then fails
 * naming the first differing byte offset with a short excerpt around it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class CompleteDocumentGoldenBytesIT {

    /** The longest one deployment, request, or undeployment is awaited. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** The pinned entity tag of the JSON form, quotes included, exactly as served. */
    static final String JSON_ENTITY_TAG = "\"a65375481190ef356feb83799e51182caf382f89355b4095a9e2b3db224971a8\"";

    /** The pinned entity tag of the YAML form, quotes included, exactly as served. */
    static final String YAML_ENTITY_TAG = "\"82bce7c0da96e9436a1fb2fd1c8fe71a4f905aca2fa06fd0f9157277170501af\"";

    /** The classpath resource holding the pinned JSON bytes. */
    static final String GOLDEN_JSON = "golden/complete-document.json";

    /** The classpath resource holding the pinned YAML bytes. */
    static final String GOLDEN_YAML = "golden/complete-document.yaml";

    private Vertx vertx;

    private WebClient client;

    private final List<String> deploymentIds = new ArrayList<>();

    @BeforeEach
    void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterEach
    void closeClientThenUndeploy() throws Exception {
        try {
            client.close();
        } finally {
            Deployments.undeployAll(vertx, deploymentIds, WAIT);
            deploymentIds.clear();
        }
    }

    @Test
    @DisplayName("The complete-feature document is served with its pinned JSON and YAML bytes and entity tags")
    void servedBytesAndEntityTagsEqualThePinnedDocument() throws Exception {
        // Given: the application 'ref' in its own composition with the documentation module, under
        // web-validation with the canonical schema source and no configured info, one instance
        RefComponent ref =
                DaggerCompleteDocumentTestComponents_RefComponent.factory().create(vertx, webValidationConfig());
        int port =
                Deployments.deployAndReadPort(vertx, ref::httpVerticle, new DeploymentOptions(), deploymentIds, WAIT);

        // When: both forms of its document are fetched
        String base = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + RefEntriesApi.NAME;
        Answer json = DocumentRequests.get(client, port, base + "/openapi.json", null, WAIT);
        Answer yaml = DocumentRequests.get(client, port, base + "/openapi.yaml", null, WAIT);

        // Then: both answer 200, and their bytes and entity tags equal the pinned literals
        assertEquals(200, json.status(), "GET " + base + "/openapi.json must answer 200");
        assertEquals(200, yaml.status(), "GET " + base + "/openapi.yaml must answer 200");
        byte[] goldenJson = resource(GOLDEN_JSON);
        byte[] goldenYaml = resource(GOLDEN_YAML);
        List<String> problems = new ArrayList<>();
        compare("JSON", GOLDEN_JSON, goldenJson, json.body(), problems);
        compare("YAML", GOLDEN_YAML, goldenYaml, yaml.body(), problems);
        if (!JSON_ENTITY_TAG.equals(json.etag())) {
            problems.add("JSON entity tag: expected " + JSON_ENTITY_TAG + " but was " + json.etag());
        }
        if (!YAML_ENTITY_TAG.equals(yaml.etag())) {
            problems.add("YAML entity tag: expected " + YAML_ENTITY_TAG + " but was " + yaml.etag());
        }
        if (!problems.isEmpty()) {
            Path written = writeActual(json, yaml);
            fail("the served document differs from the pinned one (actual bytes and tags written to " + written + "):\n"
                    + String.join("\n", problems));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void compare(
            String form, String resourceName, @Nullable byte[] golden, byte[] actual, List<String> problems) {
        if (golden == null) {
            problems.add(form + ": the golden resource " + resourceName + " is missing");
            return;
        }
        ByteDifferences.first(golden, actual).ifPresent(difference -> problems.add(form + ": " + difference));
    }

    /** Reads a classpath resource, or returns {@code null} when it does not exist. */
    private static @Nullable byte[] resource(String name) throws IOException {
        try (InputStream in =
                CompleteDocumentGoldenBytesIT.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /**
     * Writes the served bytes and entity tags under {@code target/golden-actual} of the module
     * directory, which surefire and failsafe expose as the {@code basedir} system property.
     */
    private static Path writeActual(Answer json, Answer yaml) throws IOException {
        Path directory = Path.of(System.getProperty("basedir", "."), "target", "golden-actual");
        Files.createDirectories(directory);
        Files.write(directory.resolve("complete-document.json"), json.body());
        Files.write(directory.resolve("complete-document.yaml"), yaml.body());
        Files.writeString(
                directory.resolve("entity-tags.txt"),
                "json: " + json.etag() + "\nyaml: " + yaml.etag() + "\n",
                StandardCharsets.UTF_8);
        return directory.toAbsolutePath();
    }

    /** The loopback configuration with the {@code web-validation} strategy and no document entries. */
    private static JsonObject webValidationConfig() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        return config;
    }
}
