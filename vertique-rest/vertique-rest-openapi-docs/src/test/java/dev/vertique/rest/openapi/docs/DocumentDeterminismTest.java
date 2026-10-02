// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.openapi.docs.DeterminismTestComponents.CaptureComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.CompleteEntries;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.RefEntriesApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.determinism.ByteDifferences;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.Deployments;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/**
 * Assembles the complete-feature application's document from one publication captured from a real
 * mount build, under variants that change only iteration order, and checks that the JSON bytes, the
 * YAML bytes, and both entity tags never change.
 *
 * <p>The publication is captured once: a composition without the documentation module, so it binds
 * no documentation sink, deploys the application {@code ref} on {@code 127.0.0.1} port {@code 0} with
 * a sink that wants detail and keeps the attached publication. Each variant is then assembled as the
 * documentation sink does: the per-operation descriptor facts are taken, the publication is detached,
 * and the document is assembled as the public document of {@code ref}, with the composition's schema
 * source, profile registry, response producer bindings and security scheme handlers, and a fresh
 * warning guard.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DocumentDeterminismTest {

    /** The document's top-level members, in the order the document writer emits them. */
    private static final List<String> TOP_LEVEL_MEMBERS = List.of(
            "openapi", "info", "jsonSchemaDialect", "servers", "paths", "components", "tags", "x-vertique-validation");

    /** The operation ids of the captured mount, the hidden one included, in any order. */
    private static final Set<String> CAPTURED_OPERATION_IDS = Set.of(
            CompleteEntries.GET_ENTRY,
            CompleteEntries.CREATE_ENTRY,
            CompleteEntries.ARCHIVE_ENTRY,
            CompleteEntries.EXPORT_ENTRIES,
            CompleteEntries.LIVE_STATUS);

    /** The longest the capture's Vert.x instance is awaited while it closes. */
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Vertx vertx;

    private static Inputs captured;

    private static Optional<OperationSchemaSource> schemaSource;

    private static JsonMapperProfileRegistry profiles;

    @BeforeAll
    static void captureThePublication() throws Exception {
        vertx = Vertx.vertx();
        CaptureComponent component =
                DaggerDeterminismTestComponents_CaptureComponent.factory().create(vertx, webValidationConfig());
        List<String> deploymentIds = new ArrayList<>();
        try {
            Deployments.deployAndReadPort(vertx, component::httpVerticle, new DeploymentOptions(), deploymentIds);
        } finally {
            Deployments.undeployAll(vertx, deploymentIds);
        }
        MountPublication attached = component.capture().publication(RefEntriesApi.NAME);
        assertNotNull(attached, "the mount of application 'ref' must have been published to the capturing sink");
        captured = new Inputs(
                attached,
                new LinkedHashSet<>(component.securitySchemeHandlers()),
                new LinkedHashSet<>(component.producerBindings()));
        schemaSource = component.schemaSource();
        profiles = component.profiles();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName(
            "Reversing the operations and the iteration order of the handler and producer sets changes no byte and no entity tag")
    void bytesAndEntityTagsIndependentOfIterationOrder() throws Exception {
        // Given: the captured publication of 'ref', with five operations carrying detail, two scheme
        // handlers and two producer bindings, and the variants that change only iteration order
        assertCapturedFixture(captured);
        List<Variant> variants = List.of(
                variant("identity", inputs -> inputs),
                variant("operations reversed", DocumentDeterminismTest::operationsReversed),
                variant("handler and producer sets reversed", DocumentDeterminismTest::setsReversed),
                variant("operations and sets reversed", inputs -> setsReversed(operationsReversed(inputs))));

        // When: each variant is assembled and rendered
        List<Assembled> assembled = new ArrayList<>();
        for (Variant variant : variants) {
            assembled.add(assemble(variant.name(), variant.transformation().apply(captured)));
        }

        // Then: the variants really iterate differently, and every variant's JSON bytes, YAML bytes and
        // both entity tags equal the first's; the JSON's top-level members keep the writer's order
        Assembled first = assembled.get(0);
        assertVariantsDiffer(first, assembled);
        List<Executable> checks = new ArrayList<>();
        for (Assembled other : assembled.subList(1, assembled.size())) {
            checks.add(() -> assertSameDocument(first, other));
        }
        checks.add(() -> assertEquals(
                TOP_LEVEL_MEMBERS,
                topLevelMembers(first.document().json()),
                "the JSON's top-level members, in written order"));
        assertAll("every variant renders the identity variant's document", checks);
    }

    // ---------------------------------------------------------------------------------------------
    // Variants
    // ---------------------------------------------------------------------------------------------

    /**
     * The inputs one variant assembles from.
     *
     * @param attached the attached publication, descriptors included
     * @param handlers the security scheme handlers, in the order the variant iterates them
     * @param producers the response producer bindings, in the order the variant iterates them
     */
    private record Inputs(
            MountPublication attached,
            Set<SecuritySchemeHandler> handlers,
            Set<ResponseProducerBinding<?>> producers) {}

    /** One named transformation of the captured inputs. */
    private record Variant(String name, UnaryOperator<Inputs> transformation) {}

    private static Variant variant(String name, UnaryOperator<Inputs> transformation) {
        return new Variant(name, transformation);
    }

    /**
     * One variant's document and the iteration orders of the inputs it handed to the assembly. The
     * assembly context copies both sets with {@code Set.copyOf}, whose iteration order is fixed per
     * JVM by element hashes, so the handed-in set order is recorded, not the order the assembler
     * finally iterates.
     */
    private record Assembled(
            String name,
            PublishedDocument document,
            List<String> operationOrder,
            List<SecuritySchemeHandler> handlerOrder,
            List<ResponseProducerBinding<?>> producerOrder) {}

    private static Inputs operationsReversed(Inputs inputs) {
        List<OperationPublication> operations =
                new ArrayList<>(inputs.attached().operations());
        Collections.reverse(operations);
        return new Inputs(withOperations(inputs.attached(), operations), inputs.handlers(), inputs.producers());
    }

    private static Inputs setsReversed(Inputs inputs) {
        List<SecuritySchemeHandler> handlers = new ArrayList<>(inputs.handlers());
        Collections.reverse(handlers);
        List<ResponseProducerBinding<?>> producers = new ArrayList<>(inputs.producers());
        Collections.reverse(producers);
        return new Inputs(inputs.attached(), new LinkedHashSet<>(handlers), new LinkedHashSet<>(producers));
    }

    private static MountPublication withOperations(
            MountPublication publication, List<OperationPublication> operations) {
        return new MountPublication(
                publication.mountPath(),
                publication.mountId(),
                publication.applicationName(),
                publication.declaringType(),
                publication.strategyId(),
                operations);
    }

    // ---------------------------------------------------------------------------------------------
    // Assembly and assertions
    // ---------------------------------------------------------------------------------------------

    /** Assembles one variant as the documentation sink does, with a fresh warning guard. */
    private static Assembled assemble(String name, Inputs inputs) {
        Map<String, OperationFacts> facts = DocsPublicationSink.operationFacts(inputs.attached());
        MountPublication detached = DocsPublicationSink.detach(inputs.attached());
        EnabledDocuments.EnabledDocument document =
                MetadataDocuments.document(inputs.attached(), ApiDocs.Access.PUBLIC);
        AssemblyContext context = new AssemblyContext(
                schemaSource, profiles, new DocumentWarnings(), inputs.producers(), inputs.handlers());
        PublishedDocument published = DocumentAssembler.assemble(document, detached, facts, context);
        return new Assembled(
                name,
                published,
                inputs.attached().operations().stream()
                        .map(OperationPublication::operationId)
                        .toList(),
                List.copyOf(inputs.handlers()),
                List.copyOf(inputs.producers()));
    }

    /** Checks that the capture holds what the variants need to reorder. */
    private static void assertCapturedFixture(Inputs inputs) {
        List<OperationPublication> operations = inputs.attached().operations();
        Set<String> ids = new HashSet<>();
        operations.forEach(operation -> ids.add(operation.operationId()));
        OperationPublication create = operations.stream()
                .filter(operation -> CompleteEntries.CREATE_ENTRY.equals(operation.operationId()))
                .findFirst()
                .orElse(null);
        assertAll(
                "the captured publication",
                () -> assertEquals(CAPTURED_OPERATION_IDS, ids, "the captured operation ids"),
                () -> assertTrue(
                        operations.stream().allMatch(operation -> operation.detail() != null),
                        "every captured operation carries detail"),
                () -> assertNotNull(create, "createEntry is captured"),
                () -> assertNotNull(
                        create == null ? null : create.detail().schemas().body(),
                        "createEntry carries a captured body schema"),
                () -> assertTrue(
                        operations.stream().anyMatch(operation -> !operation
                                .detail()
                                .schemas()
                                .parameters()
                                .isEmpty()),
                        "some operation carries captured parameter schemas"),
                () -> assertEquals(2, inputs.handlers().size(), "two security scheme handlers"),
                () -> assertEquals(2, inputs.producers().size(), "two response producer bindings"));
    }

    /**
     * Checks that the reordering variants hand the assembly a different order than the identity
     * variant does, so that equal output is not vacuous.
     */
    private static void assertVariantsDiffer(Assembled identity, List<Assembled> assembled) {
        Assembled operations = byName(assembled, "operations reversed");
        Assembled sets = byName(assembled, "handler and producer sets reversed");
        Assembled both = byName(assembled, "operations and sets reversed");
        assertAll(
                "the variants iterate differently",
                () -> assertNotEquals(
                        identity.operationOrder(), operations.operationOrder(), "operations reversed: operation order"),
                () -> assertEquals(
                        reversed(identity.handlerOrder()),
                        sets.handlerOrder(),
                        "sets reversed: the handlers are handed in reverse"),
                () -> assertEquals(
                        reversed(identity.producerOrder()),
                        sets.producerOrder(),
                        "sets reversed: the producers are handed in reverse"),
                () -> assertNotEquals(identity.operationOrder(), both.operationOrder(), "both: operation order"),
                () -> assertEquals(reversed(identity.handlerOrder()), both.handlerOrder(), "both: handler order"),
                () -> assertEquals(reversed(identity.producerOrder()), both.producerOrder(), "both: producer order"));
    }

    private static Assembled byName(List<Assembled> assembled, String name) {
        return assembled.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no variant named " + name));
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    /** Asserts that two variants rendered the same JSON bytes, YAML bytes and entity tags. */
    private static void assertSameDocument(Assembled expected, Assembled actual) {
        PublishedDocument first = expected.document();
        PublishedDocument other = actual.document();
        assertAll(
                "variant '" + actual.name() + "' against '" + expected.name() + "'",
                () -> ByteDifferences.first(first.json(), other.json())
                        .ifPresent(difference -> fail(actual.name() + ": JSON bytes differ: " + difference)),
                () -> ByteDifferences.first(first.yaml(), other.yaml())
                        .ifPresent(difference -> fail(actual.name() + ": YAML bytes differ: " + difference)),
                () -> assertEquals(first.jsonTag(), other.jsonTag(), actual.name() + ": JSON entity tag"),
                () -> assertEquals(first.yamlTag(), other.yamlTag(), actual.name() + ": YAML entity tag"));
    }

    /** Returns the top-level member names of a JSON document, in written order. */
    private static List<String> topLevelMembers(byte[] json) throws IOException {
        JsonNode root = JSON.readTree(json);
        List<String> names = new ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The loopback configuration with the {@code web-validation} strategy, so schemas are captured. */
    private static JsonObject webValidationConfig() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        return config;
    }
}
