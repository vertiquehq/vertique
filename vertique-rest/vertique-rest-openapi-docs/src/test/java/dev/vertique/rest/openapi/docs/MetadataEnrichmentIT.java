// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.openapi.docs.EnrichmentTestComponents.GenComponent;
import dev.vertique.rest.openapi.docs.EnrichmentTestComponents.ReflComponent;
import dev.vertique.rest.openapi.docs.EnrichmentTestComponents.ShopComponent;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.GenApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.GeneratedCatalogResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.ReflApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.ReflectedCatalogResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment.ShopApi;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Serves public documents of declared applications under {@code web-validation} over real,
 * loopback-bound {@code HttpVerticle} deployments and checks the Swagger documentation metadata they
 * publish: operation, tag, parameter, and request-body documentation applied identically on the
 * generated and the reflective discovery path, and body member metadata published exactly as the
 * canonical generator wrote it into the schema the gate received.
 *
 * <p>Every expected value is a fixed literal, except the expected body component, which is the
 * recording schema source's copy of the gate's original with its redaction manifest applied by one
 * helper. One Vert.x instance and one client serve the class; each composition is deployed, its
 * document fetched, and undeployed before the next, clearing the {@code vertique} local map around
 * each deployment.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MetadataEnrichmentIT {

    private static final String HOST = "127.0.0.1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Vertx vertx;
    private static WebClient client;

    @BeforeAll
    static void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Documentation attributes on both discovery paths
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Operation, tag, parameter, and request-body documentation is published identically on the generated and the reflective path, with root tags sorted by name")
    void documentationAttributesAreAppliedOnBothDiscoveryPaths() throws Exception {
        // Given: twin catalog resources with identical annotations and operation ids, one described by
        // its generated companion and listed by gen, the other by the reflective scanner and listed by
        // refl, each application in a composition of its own with an enabled public document
        GeneratedJaxRsDescriptorRegistry registry = GeneratedJaxRsDescriptorRegistry.shared();
        assertTrue(
                registry.lookup(GeneratedCatalogResource.class).isPresent(),
                "the gen twin must have a generated descriptor companion");
        assertTrue(
                registry.lookup(ReflectedCatalogResource.class).isEmpty(),
                "the refl twin must have no generated descriptor companion");
        GenComponent gen = DaggerEnrichmentTestComponents_GenComponent.factory()
                .create(InputAssemblyIT.webValidationConfig(GenApi.NAME));
        ReflComponent refl = DaggerEnrichmentTestComponents_ReflComponent.factory()
                .create(InputAssemblyIT.webValidationConfig(ReflApi.NAME));

        // When: both documents are requested
        byte[] genJson = fetchDocument(gen::httpVerticle, GenApi.NAME);
        byte[] reflJson = fetchDocument(refl::httpVerticle, ReflApi.NAME);

        // Then: each document carries the metadata, and the two are equal apart from servers and info
        List<Executable> checks = new ArrayList<>();
        checks.addAll(catalogMetadataChecks("gen", parse(genJson)));
        checks.addAll(catalogMetadataChecks("refl", parse(reflJson)));
        checks.add(() -> assertEquals(
                MetadataDocuments.normalized(genJson),
                MetadataDocuments.normalized(reflJson),
                "the gen and refl documents must be equal once servers and info are dropped"));
        assertAll("the catalog documents on both discovery paths", checks.stream());
    }

    /** Returns the checks of one catalog document's documentation metadata. */
    private static List<Executable> catalogMetadataChecks(String label, JsonObject document) {
        JsonObject list = InputAssemblyIT.operation(document, "/items", "get");
        JsonObject create = InputAssemblyIT.operation(document, "/items", "post");
        List<Executable> checks = new ArrayList<>();

        // The listing operation: its @Operation documentation and its tags in declaration order.
        checks.add(() -> assertEquals("listItems", list.getString("operationId"), label + ": GET operation id"));
        checks.add(() -> assertEquals("List items", list.getValue("summary"), label + ": GET summary"));
        checks.add(
                () -> assertEquals("All published items", list.getValue("description"), label + ": GET description"));
        checks.add(() -> assertEquals(Boolean.TRUE, list.getValue("deprecated"), label + ": GET deprecated"));
        checks.add(() -> assertEquals(
                new JsonObject().put("url", "https://docs.example.test/list"),
                list.getValue("externalDocs"),
                label + ": GET externalDocs"));
        checks.add(() -> assertEquals(
                new JsonArray().add("read").add("alpha").add("catalog"),
                list.getValue("tags"),
                label + ": GET tags: @Operation tags, then the method's @Tag, then the class's"));

        // Its query parameter: the example text parses as JSON, so it is the number 42.
        checks.add(() -> {
            JsonObject limit =
                    InputAssemblyIT.byName(list.getJsonArray("parameters")).get("limit");
            assertNotNull(limit, label + ": GET publishes query limit");
            assertEquals(42, limit.getValue("example"), label + ": limit example is the JSON number 42");
            assertEquals(Boolean.TRUE, limit.getValue("deprecated"), label + ": limit deprecated");
        });

        // The creating operation: @Tags unwrapped in declaration order, then the class's tag.
        checks.add(() -> assertEquals("createItem", create.getString("operationId"), label + ": POST operation id"));
        checks.add(() -> assertEquals(
                new JsonArray().add("zeta").add("beta").add("catalog"),
                create.getValue("tags"),
                label + ": POST tags: the method's @Tags in declaration order, then the class's"));
        checks.add(() -> {
            JsonObject requestBody = create.getJsonObject("requestBody");
            assertNotNull(requestBody, () -> label + ": POST publishes its request body: " + create.encode());
            assertEquals("New item", requestBody.getValue("description"), label + ": request body description");
        });

        // The root tags: every @Tag once, sorted by name, the class's carrying its documentation.
        checks.add(() -> {
            JsonArray tags = document.getJsonArray("tags");
            assertNotNull(tags, label + ": the document publishes root tags");
            List<Object> names = new ArrayList<>();
            JsonObject catalog = null;
            for (int index = 0; index < tags.size(); index++) {
                JsonObject tag = tags.getJsonObject(index);
                names.add(tag.getValue("name"));
                if ("catalog".equals(tag.getValue("name"))) {
                    catalog = tag;
                }
            }
            assertEquals(List.of("alpha", "beta", "catalog", "zeta"), names, label + ": root tag names in order");
            assertNotNull(catalog, label + ": root tags hold catalog");
            assertEquals("Catalog operations", catalog.getValue("description"), label + ": catalog description");
            assertEquals(
                    new JsonObject().put("url", "https://docs.example.test/catalog"),
                    catalog.getValue("externalDocs"),
                    label + ": catalog externalDocs");
        });
        return checks;
    }

    // ---------------------------------------------------------------------------------------------
    // Canonical member metadata
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Body member metadata is published under the JSON names exactly as the canonical generator wrote it, and the operation's summary is published")
    void canonicalMemberMetadataIsPublishedUntouched() throws Exception {
        // Given: POST /products taking a body whose renamed creator parameter and setter carry
        // constraints and @Schema documentation, an operation summary, and the canonical schema
        // source wrapped by a recording source, with no Bean Validation Validator bound
        ShopComponent shop = DaggerEnrichmentTestComponents_ShopComponent.factory()
                .create(InputAssemblyIT.webValidationConfig(ShopApi.NAME));

        // When: the public document is requested
        JsonObject document = parse(fetchDocument(shop::httpVerticle, ShopApi.NAME));

        // Then: the expected component is the gate's original with its redaction manifest applied
        RecordingSchemaSource.Recording recording = shop.recordingSource().recording("createProduct");
        JsonObject original = recording.bodyCopy();
        assertNotNull(original, "the source returned a body schema for createProduct");
        RedactionManifest manifest = assertInstanceOf(
                RedactionManifest.class,
                recording.bodyProvenance(),
                "the gate's original carries the generator's redaction manifest");
        JsonObject expected = redacted(original, manifest);
        JsonObject component = InputAssemblyIT.schemaComponent(document, "createProduct.request");
        JsonObject properties = component.getJsonObject("properties");
        assertNotNull(properties, () -> "the body component publishes properties: " + component.encode());

        assertAll(
                "the published body component and operation",
                () -> {
                    JsonObject displayName = properties.getJsonObject("display_name");
                    assertNotNull(displayName, () -> "display_name is published: " + properties.encode());
                    assertEquals(40, displayName.getValue("maxLength"), "display_name maxLength");
                    assertEquals("Shown to buyers", displayName.getValue("description"), "display_name description");
                    assertEquals("Display name", displayName.getValue("title"), "display_name title");
                },
                () -> {
                    JsonObject skuCode = properties.getJsonObject("sku_code");
                    assertNotNull(skuCode, () -> "sku_code is published: " + properties.encode());
                    assertEquals("^[A-Z]{3}-[0-9]{4}$", skuCode.getValue("pattern"), "sku_code pattern");
                    assertEquals("Stock code", skuCode.getValue("description"), "sku_code description");
                },
                () -> assertFalse(
                        properties.containsKey("displayName"),
                        () -> "no property is keyed by the Java name displayName: " + properties.encode()),
                () -> assertFalse(
                        properties.containsKey("sku"),
                        () -> "no property is keyed by the Java name sku: " + properties.encode()),
                () -> assertEquals(
                        expected, component, "the body component equals the gate's original after redaction"),
                () -> assertEquals(
                        "Create product",
                        InputAssemblyIT.operation(document, "/products", "post").getValue("summary"),
                        "the operation's summary is published"));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a copy of a schema with every location its redaction manifest lists removed: each
     * pointer is resolved first, then object members are removed and array elements are removed in
     * descending index order. A manifest without pointers returns an equal copy.
     */
    private static JsonObject redacted(JsonObject original, RedactionManifest manifest) throws Exception {
        JsonNode tree = JSON.readTree(original.encode());
        List<Runnable> memberRemovals = new ArrayList<>();
        Map<ArrayNode, SortedSet<Integer>> elementRemovals = new IdentityHashMap<>();
        for (String pointer : manifest.pointers()) {
            JsonPointer parsed = JsonPointer.compile(pointer);
            JsonPointer head = parsed.head();
            if (parsed.matches() || head == null) {
                fail("a manifest pointer must name a location inside the schema: '" + pointer + "'");
            }
            JsonNode parent = tree.at(head);
            JsonPointer last = parsed.last();
            if (parent instanceof ObjectNode object && object.has(last.getMatchingProperty())) {
                String member = last.getMatchingProperty();
                memberRemovals.add(() -> object.remove(member));
            } else if (parent instanceof ArrayNode array
                    && last.getMatchingIndex() >= 0
                    && last.getMatchingIndex() < array.size()) {
                elementRemovals
                        .computeIfAbsent(array, ignored -> new TreeSet<>(Comparator.reverseOrder()))
                        .add(last.getMatchingIndex());
            } else {
                fail("a manifest pointer must resolve in the gate's original: '" + pointer + "'");
            }
        }
        memberRemovals.forEach(Runnable::run);
        elementRemovals.forEach((array, indices) -> indices.forEach(index -> array.remove(index.intValue())));
        return new JsonObject(JSON.writeValueAsString(tree));
    }

    private static JsonObject parse(byte[] json) {
        return new JsonObject(Buffer.buffer(json));
    }

    /**
     * Deploys a component's verticle after clearing the {@code vertique} local map, fetches one
     * document's JSON form, and undeploys, clearing the map again.
     *
     * @param verticles    the component's verticle supplier
     * @param documentName the document (application) name
     * @return the document's JSON bytes
     */
    private static byte[] fetchDocument(Supplier<Verticle> verticles, String documentName) throws Exception {
        vertx.sharedData().getLocalMap("vertique").clear();
        String id = Futures.await(vertx.deployVerticle(verticles, new DeploymentOptions()), Duration.ofSeconds(15));
        try {
            Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
            assertNotNull(port, "the deployment must publish its port");
            String uri = InputAssemblyIT.documentPath(documentName, InputAssemblyIT.JSON_FORM);
            HttpResponse<Buffer> response =
                    Futures.await(client.get(port, HOST, uri).send(), Duration.ofSeconds(15));
            assertEquals(200, response.statusCode(), () -> "GET " + uri + " must answer 200");
            Buffer body = response.body();
            return body == null ? new byte[0] : body.getBytes();
        } finally {
            try {
                Futures.await(vertx.undeploy(id), Duration.ofSeconds(15));
            } finally {
                vertx.sharedData().getLocalMap("vertique").clear();
            }
        }
    }
}
