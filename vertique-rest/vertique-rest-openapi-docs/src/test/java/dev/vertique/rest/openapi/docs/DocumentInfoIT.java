// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.InfoRegistrations;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Serves the document of an application over a real, loopback-bound {@code HttpVerticle}
 * deployment and checks the {@code info} it publishes: every {@code @Info} member of the declaring
 * interface when nothing is configured, only the three members the interim rule reads when the
 * interface sets only those, and the configured {@code info} alone when one is configured.
 *
 * <p>Expected values are fixed literal JSON texts. One Vert.x instance and one client serve the
 * class; each case deploys its own component's verticle and undeploys it, clearing the {@code
 * vertique} local map, before the next.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DocumentInfoIT {

    private static final String HOST = "127.0.0.1";

    private static final String JSON_PATH =
            DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + InfoRegistrations.PUBLIC_NAME + "/openapi.json";

    private static final String YAML_PATH =
            DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + InfoRegistrations.PUBLIC_NAME + "/openapi.yaml";

    private static final String COMPLETE_INFO = """
            {"title":"Catalog API","summary":"Catalog","description":"Items",\
            "termsOfService":"https://example.test/terms",\
            "contact":{"name":"Ops","url":"https://example.test/ops","email":"ops@example.test"},\
            "license":{"name":"Apache 2.0","identifier":"Apache-2.0"},\
            "version":"2.1","x-audience":{"tier":"public"}}""";

    private static final String THREE_MEMBER_INFO = """
            {"title":"Basic","description":"Only three","version":"1"}""";

    private static final String CONFIGURED_INFO = """
            {"title":"Configured","version":"9"}""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static Vertx vertx;
    private static WebClient client;

    /** The outcome of one deployment: the failure, or the documents served in both forms. */
    private record Outcome(Throwable failure, JsonObject json, JsonNode yaml) {}

    @BeforeAll
    static void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    @DisplayName(
            "A declaring interface publishes all its info members, or only its three, and a configured info replaces them, in JSON and YAML")
    void declaringInterfacePublishesItsCompleteInfo() throws Exception {
        // Given: three compositions of application public: complete info, three-member info, and
        // complete info with a configured info.
        JsonObject configured = DocsConfigs.loopback();
        DocsConfigs.withDocumentInfo(configured, InfoRegistrations.PUBLIC_NAME, "Configured", "9");

        // When: each is deployed and its JSON and YAML documents are requested.
        Outcome complete = deployAndFetch(
                DaggerInfoTestComponents_FullInfoComponent.factory().create(DocsConfigs.loopback())::httpVerticle);
        Outcome threeMember = deployAndFetch(
                DaggerInfoTestComponents_BasicInfoComponent.factory().create(DocsConfigs.loopback())::httpVerticle);
        Outcome replaced = deployAndFetch(
                DaggerInfoTestComponents_FullInfoComponent.factory().create(configured)::httpVerticle);

        // Then: the complete interface info is published exactly, and the document validates.
        assertNull(complete.failure(), () -> "complete info deployment failed: " + complete.failure());
        assertInfo(COMPLETE_INFO, complete);
        OpenApi31Toolchain.assertValid(complete.json().copy());

        // Then: the three-member interface info is published exactly.
        assertNull(threeMember.failure(), () -> "three-member deployment failed: " + threeMember.failure());
        assertInfo(THREE_MEMBER_INFO, threeMember);

        // Then: the configured info replaces the interface's.
        assertNull(replaced.failure(), () -> "configured deployment failed: " + replaced.failure());
        assertInfo(CONFIGURED_INFO, replaced);
    }

    private static void assertInfo(String expectedInfo, Outcome outcome) throws Exception {
        JsonNode expected = JSON.readTree(expectedInfo);
        JsonObject info = outcome.json().getJsonObject("info");
        assertNotNull(info, "the JSON document must have an info object");
        assertEquals(expected, JSON.readTree(info.encode()), "JSON info");
        JsonNode yamlInfo = outcome.yaml().get("info");
        assertNotNull(yamlInfo, "the YAML document must have an info object");
        assertEquals(expected, yamlInfo, "YAML info");
    }

    /**
     * Deploys one verticle, requests both forms of the {@code public} document, and always undeploys.
     *
     * @param verticle the component's verticle supplier
     * @return the failure, or the documents
     */
    private static Outcome deployAndFetch(Supplier<HttpVerticle> verticle) throws Exception {
        vertx.sharedData().getLocalMap("vertique").clear();
        String id = null;
        try {
            id = Futures.await(
                    vertx.deployVerticle(() -> verticle.get(), new DeploymentOptions()), Duration.ofSeconds(15));
            Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
            assertNotNull(port, "the deployment must publish its port");
            HttpResponse<Buffer> json =
                    Futures.await(client.get(port, HOST, JSON_PATH).send(), Duration.ofSeconds(15));
            assertEquals(200, json.statusCode(), "GET " + JSON_PATH);
            HttpResponse<Buffer> yaml =
                    Futures.await(client.get(port, HOST, YAML_PATH).send(), Duration.ofSeconds(15));
            assertEquals(200, yaml.statusCode(), "GET " + YAML_PATH);
            return new Outcome(
                    null, new JsonObject(json.body()), YAML.readTree(yaml.body().getBytes()));
        } catch (Exception failure) {
            return new Outcome(failure, null, null);
        } finally {
            if (id != null) {
                try {
                    Futures.await(vertx.undeploy(id), Duration.ofSeconds(15));
                } finally {
                    vertx.sharedData().getLocalMap("vertique").clear();
                }
            }
        }
    }
}
