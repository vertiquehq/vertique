// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.EquivalenceTestComponents.CorpusComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusApi;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusFixtures;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusFixtures.CorpusFixture;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusResource;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.Validator;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves that the published document's components give the validation gate's verdicts: for every
 * fixture of the embedding-equivalence corpus (see {@code fixture.corpus.CorpusFixtures}), each
 * instance validated against the component inside the whole published document gets the same
 * verdict as against the schema the gate received.
 *
 * <p>One deployment serves every row: the {@code corpus} application under {@code web-validation},
 * whose schema source records a deep copy of every schema the gate received (the original). Before
 * the server starts, the recording source replaces the schema of one query parameter with a
 * hand-written one carrying local definitions and every fragment-only reference form. The public
 * document is fetched once; a copy of it is dereferenced once into a schema repository under a
 * fixed URI, so every rewritten reference resolves within the whole document; and the document is
 * checked once by {@link OpenApi31Toolchain} (each check starts its own Vert.x instance and loads
 * the meta-schema, so it is not repeated per row; every row asserts the one verdict). Nothing is
 * asserted during that setup, so a missing document or component fails each row on its own.
 *
 * <p>Per row: the generator reserves no name in the body type's schema (so redaction would remove
 * nothing from this corpus); the verdicts are equal for every instance, where an evaluation error
 * counts as a mismatch, and the gate's schema accepts at least one instance and rejects at least
 * one; the component holds no local definitions, and moving its relocated definitions back and
 * reversing the rewritten references gives the gate's schema exactly; and the document is a valid
 * OpenAPI 3.1 document. Each row logs its verdict counts.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class EmbeddingEquivalenceIT {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingEquivalenceIT.class);

    private static final String HOST = "127.0.0.1";

    /** The fixed URI the published document is dereferenced under. */
    private static final String DOCUMENT_URI = "https://equivalence.test/document.json";

    /** The prefix of every reference to a component of the published document. */
    private static final String COMPONENTS_PREFIX = "#/components/schemas/";

    /** The options the validation gate compiles its schemas with. */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** Every character a component key replaces with {@code _}. */
    private static final Pattern OUTSIDE_KEY_ALPHABET = Pattern.compile("[^A-Za-z0-9._-]");

    /** Keywords whose values are literal data, never schemas. */
    private static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

    /** Keywords whose members are names, each mapped to a schema. */
    private static final Set<String> NAMED_SCHEMA_MAPS =
            Set.of("properties", "patternProperties", "$defs", "dependentSchemas");

    private static Vertx vertx;
    private static WebClient client;
    private static String deploymentId;
    private static RecordingSchemaSource recordingSource;
    private static int documentStatus;
    private static JsonObject document;
    private static SchemaRepository publishedRepository;
    private static RuntimeException dereferenceFailure;
    private static OpenApi31Toolchain.Verdict toolchainVerdict;

    @BeforeAll
    static void deployAndFetchTheDocument(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = WebClient.create(vertx);

        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        DocsConfigs.withDocumentInfo(config, CorpusApi.NAME, CorpusApi.TITLE, CorpusApi.VERSION);
        CorpusComponent component =
                DaggerEquivalenceTestComponents_CorpusComponent.factory().create(config);
        recordingSource = component.recordingSource();
        recordingSource.replaceParameter(
                CorpusResource.PARAMETER_REFS,
                ParamLocation.QUERY,
                CorpusResource.CODE,
                CorpusFixtures.parameterSchema());

        vertx.sharedData().getLocalMap("vertique").clear();
        Supplier<Verticle> verticles = component::httpVerticle;
        deploymentId = await(vertx.deployVerticle(verticles, new DeploymentOptions()));
        Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        if (port == null) {
            throw new IllegalStateException("the deployment did not publish its port");
        }

        HttpResponse<Buffer> response =
                await(client.get(port, HOST, CorpusApi.DOCUMENT_URL).send());
        documentStatus = response.statusCode();
        if (documentStatus != 200) {
            return;
        }
        document = response.bodyAsJsonObject();
        try {
            // The repository writes into what it dereferences: hand it a copy.
            publishedRepository =
                    SchemaRepository.create(GATE_OPTIONS).dereference(DOCUMENT_URI, JsonSchema.of(document.copy()));
        } catch (RuntimeException failed) {
            dereferenceFailure = failed;
        }
        toolchainVerdict = OpenApi31Toolchain.validate(vertx, document.copy());
    }

    @AfterAll
    static void undeployAndCloseTheClient() throws Exception {
        try {
            if (deploymentId != null) {
                await(vertx.undeploy(deploymentId));
            }
        } finally {
            vertx.sharedData().getLocalMap("vertique").clear();
            client.close();
        }
    }

    static Stream<Arguments> corpus() {
        return CorpusFixtures.all().stream().map(fixture -> Arguments.of(Named.of(fixture.name(), fixture)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName(
            "Each published component gives the gate's verdict for every corpus instance, keeps no local definitions, and reverses to the gate's schema")
    void publishedComponentsGiveTheGatesVerdicts(CorpusFixture fixture) {
        // Given: the generator reserves no name in the body's schema, so redaction removes nothing here.
        // A query parameter has no generator-bound manifest, so its row has no such precondition.
        if (fixture.bodyType() != null) {
            assertTrue(
                    GeneratedBodies.manifestIsEmpty(fixture.bodyType(), fixture.profileId()),
                    "precondition: the generator must reserve no name in the schema of "
                            + fixture.bodyType().getName() + " under profile " + fixture.profileId());
        }
        assertEquals(200, documentStatus, "the public document must be served");
        assertNull(dereferenceFailure, () -> "the published document must dereference: " + dereferenceFailure);
        JsonObject original = original(fixture);
        String component = componentKey(fixture);

        // When: every instance is validated against the published component and against the original.
        List<InstanceVerdict> verdicts = new ArrayList<>();
        for (String instance : fixture.instances()) {
            verdicts.add(verdictsFor(component, original, instance));
        }

        // Then: the verdicts are equal, and the gate's schema both accepts and rejects something.
        long valid = verdicts.stream()
                .filter(verdict -> Boolean.TRUE.equals(verdict.original()))
                .count();
        long invalid = verdicts.stream()
                .filter(verdict -> Boolean.FALSE.equals(verdict.original()))
                .count();
        List<InstanceVerdict> mismatches =
                verdicts.stream().filter(verdict -> !verdict.matches()).toList();
        LOG.info(
                "Equivalence corpus '{}' (component '{}'): {} instances, {} valid and {} invalid by the gate's"
                        + " schema, {} published verdicts equal, {} mismatches",
                fixture.name(),
                component,
                verdicts.size(),
                valid,
                invalid,
                verdicts.size() - mismatches.size(),
                mismatches.size());
        assertAll(
                () -> assertEquals(
                        List.of(),
                        mismatches,
                        "every instance must get the gate's verdict from component '" + component + "'"),
                () -> assertTrue(valid >= 1, "the gate's schema must accept at least one instance: " + verdicts),
                () -> assertTrue(invalid >= 1, "the gate's schema must reject at least one instance: " + verdicts));

        // Then: the component keeps no local definitions, and reversing the relocation gives the original.
        JsonObject schemas = componentSchemas();
        JsonObject published = schemas.getJsonObject(component);
        assertNotNull(
                published, () -> "component '" + component + "' must be published; keys: " + schemas.fieldNames());
        assertFalse(
                published.containsKey("$defs"),
                () -> "component '" + component + "' must keep no local definitions: " + published.encode());
        assertEquals(
                original,
                reverseRelocation(schemas, component),
                "moving the relocated definitions back and reversing the rewritten references must give the gate's"
                        + " schema");

        // Then: the whole document is a valid OpenAPI 3.1 document.
        assertTrue(toolchainVerdict.valid(), () -> "Invalid OpenAPI 3.1 document: " + toolchainVerdict.problems());
    }

    // --- The two verdicts of one instance ---

    /**
     * The verdicts of one instance.
     *
     * @param instance  the instance's JSON text
     * @param original  the original's verdict, or {@code null} when evaluating it failed
     * @param published the published component's verdict, or {@code null} when evaluating it failed
     * @param failure   the evaluation error, or {@code null}
     */
    private record InstanceVerdict(String instance, Boolean original, Boolean published, String failure) {

        boolean matches() {
            return failure == null && original != null && original.equals(published);
        }

        @Override
        public String toString() {
            return instance + " -> original " + original + ", published " + published
                    + (failure == null ? "" : ", failed: " + failure);
        }
    }

    /**
     * Validates one instance against the published component, resolved within the whole dereferenced
     * document, and against the original compiled with the gate's options.
     */
    private static InstanceVerdict verdictsFor(String component, JsonObject original, String instance) {
        Boolean originalVerdict = null;
        Boolean publishedVerdict = null;
        try {
            // The engine writes into what it compiles: hand it a copy of the recorded copy.
            Validator gate = Validator.create(JsonSchema.of(original.copy()), GATE_OPTIONS);
            originalVerdict = Boolean.TRUE.equals(
                    gate.validate(Json.decodeValue(instance)).getValid());
            Validator embedded =
                    publishedRepository.validator(DOCUMENT_URI + COMPONENTS_PREFIX + pointerEscape(component));
            publishedVerdict = Boolean.TRUE.equals(
                    embedded.validate(Json.decodeValue(instance)).getValid());
            return new InstanceVerdict(instance, originalVerdict, publishedVerdict, null);
        } catch (RuntimeException failed) {
            return new InstanceVerdict(instance, originalVerdict, publishedVerdict, failed.toString());
        }
    }

    // --- Inputs of a fixture ---

    /** Returns the recorded copy of the schema the gate received for the fixture's input. */
    private static JsonObject original(CorpusFixture fixture) {
        RecordingSchemaSource.Recording recording = recordingSource.recording(fixture.operationId());
        if (fixture.queryParameter() != null) {
            return recording.parameterCopy(ParamLocation.QUERY, fixture.queryParameter());
        }
        JsonObject body = recording.bodyCopy();
        assertNotNull(body, () -> "the gate must have received a body schema for " + fixture.operationId());
        return body;
    }

    /**
     * Returns the component key of the fixture's input: {@code <operationId>.request} for a body,
     * {@code <operationId>.query.<name>} for a query parameter, with every character outside
     * {@code [A-Za-z0-9._-]} replaced by {@code _}.
     */
    private static String componentKey(CorpusFixture fixture) {
        String key = fixture.queryParameter() == null
                ? fixture.operationId() + ".request"
                : fixture.operationId() + ".query." + fixture.queryParameter();
        return OUTSIDE_KEY_ALPHABET.matcher(key).replaceAll("_");
    }

    private static JsonObject componentSchemas() {
        JsonObject components = document.getJsonObject("components");
        assertNotNull(components, () -> "the document must have components: " + document.encode());
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, () -> "the document must have component schemas: " + components.encode());
        return schemas;
    }

    // --- Reverse relocation ---

    /**
     * Rebuilds a component's captured form: every {@code <component>.<def>} component goes back under
     * the root's {@code $defs} as {@code <def>}, and every reference is rewritten back:
     * {@code #/components/schemas/<component>} to {@code #}, {@code …/<component>.<def>[/<rest>]} to
     * {@code #/$defs/<def>[/<rest>]}, and {@code …/<component>/<pointer>} to {@code #/<pointer>}. A
     * reference to any other component is left as it is, so it never equals the original.
     */
    private static JsonObject reverseRelocation(JsonObject schemas, String component) {
        JsonObject root = schemas.getJsonObject(component).copy();
        String prefix = component + ".";
        JsonObject definitions = new JsonObject();
        for (String key : schemas.fieldNames()) {
            if (key.startsWith(prefix)) {
                Object definition = schemas.getValue(key);
                definitions.put(
                        key.substring(prefix.length()),
                        definition instanceof JsonObject object ? object.copy() : definition);
            }
        }
        if (!definitions.isEmpty()) {
            root.put("$defs", definitions);
        }
        reverseReferences(root, COMPONENTS_PREFIX + pointerEscape(component));
        return root;
    }

    /** Rewrites, in place, every {@code $ref} at a schema position of {@code node} back to its local form. */
    private static void reverseReferences(Object node, String componentReference) {
        if (node instanceof JsonObject object) {
            for (String key : List.copyOf(object.fieldNames())) {
                Object value = object.getValue(key);
                if ("$ref".equals(key) && value instanceof String reference) {
                    object.put(key, reverseReference(reference, componentReference));
                } else if (LITERAL_KEYWORDS.contains(key)) {
                    continue;
                } else if (NAMED_SCHEMA_MAPS.contains(key) && value instanceof JsonObject members) {
                    for (String name : members.fieldNames()) {
                        reverseReferences(members.getValue(name), componentReference);
                    }
                } else {
                    reverseReferences(value, componentReference);
                }
            }
        } else if (node instanceof JsonArray array) {
            for (Object element : array) {
                reverseReferences(element, componentReference);
            }
        }
    }

    private static String reverseReference(String reference, String componentReference) {
        if (reference.equals(componentReference)) {
            return "#";
        }
        if (reference.startsWith(componentReference + "/")) {
            return "#" + reference.substring(componentReference.length());
        }
        if (reference.startsWith(componentReference + ".")) {
            String definitionAndRest = reference.substring(componentReference.length() + 1);
            return "#/$defs/" + definitionAndRest;
        }
        return reference;
    }

    /** Escapes a JSON Pointer reference token (RFC 6901). */
    private static String pointerEscape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
