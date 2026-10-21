// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxModule;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/** Exercises real parser and pipeline behavior for textual workflow sources. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionSourceTextTest {

    private static final int DEFAULT_LIMIT = 262_144;

    record ReaderState(String value) {}

    record ReaderStart(String value) {}

    @WorkflowContract
    interface StaticYamlV1 {}

    @WorkflowContract
    interface StaticJsonV1 {}

    @WorkflowContract
    interface StaticJsonLargeV1 {}

    @WorkflowContract
    interface StaticTrailingJsonV1 {}

    @WorkflowContract
    interface ReaderEncodingV1 {}

    @WorkflowContract
    interface ReaderEncodingUnicodeV1 {}

    @WorkflowContract
    interface ExactLimitV1 {}

    @WorkflowContract
    interface OverLimitV1 {}

    @WorkflowContract
    interface PersistedJsonV1 {}

    @WorkflowContract
    interface PersistedJsonV2 {}

    @WorkflowContract
    interface PersistedJsonV3 {}

    @WorkflowContract
    interface PersistedJsonV4 {}

    @WorkflowContract
    interface PersistedJsonV5 {}

    @WorkflowContract
    interface PersistedJsonV6 {}

    @WorkflowContract
    interface PersistedJsonV7 {}

    @WorkflowContract
    interface PersistedJsonV8 {}

    @WorkflowContract
    interface PersistedJsonV9 {}

    @WorkflowContract
    interface PersistedJsonV10 {}

    @WorkflowContract
    interface PersistedYamlV1 {}

    @WorkflowContract
    interface PersistedInvalidTailV1 {}

    @WorkflowContract
    interface PersistedNulV1 {}

    @WorkflowContract
    interface PersistedUnpairedV1 {}

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                WorkflowCoreModule.class,
                WorkflowDefinitionModule.class,
                TestSupportModule.class,
                SourceModule.class
            })
    interface ReaderGraph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder sourceModule(SourceModule module);

            ReaderGraph build();
        }

        WorkflowDefinitionService definitionService();

        WorkflowDefinitionParser parser();

        WorkflowDefinitionMapperFactory mapperFactory();

        WorkflowDefinitionPipeline pipeline();

        WorkflowRegistry workflowRegistry();
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                WorkflowCoreModule.class,
                WorkflowDefinitionModule.class,
                TestSupportModule.class,
                CapturingStoreModule.class
            })
    interface DurableReaderGraph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder capturingStoreModule(CapturingStoreModule module);

            DurableReaderGraph build();
        }

        WorkflowDefinitionService definitionService();

        Set<PersistedDefinitionLoader> persistedDefinitionLoaders();
    }

    @Module
    static class TestSupportModule {

        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "reader.init", ReaderStart.class, ReaderState.class, start -> new ReaderState(start.value())));
        }

        /** Lists the classes a service-loaded document may name; the service resolves names through the catalog only. */
        @Provides
        @IntoSet
        static WorkflowTypeCatalog typeCatalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.TYPE, ReaderState.class.getName(), ReaderState.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, ReaderStart.class.getName(), ReaderStart.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, StaticYamlV1.class.getName(), StaticYamlV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, StaticJsonV1.class.getName(), StaticJsonV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, StaticJsonLargeV1.class.getName(), StaticJsonLargeV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, StaticTrailingJsonV1.class.getName(), StaticTrailingJsonV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, ReaderEncodingV1.class.getName(), ReaderEncodingV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, ReaderEncodingUnicodeV1.class.getName(), ReaderEncodingUnicodeV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, ExactLimitV1.class.getName(), ExactLimitV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, OverLimitV1.class.getName(), OverLimitV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV1.class.getName(), PersistedJsonV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV2.class.getName(), PersistedJsonV2.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV3.class.getName(), PersistedJsonV3.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV4.class.getName(), PersistedJsonV4.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV5.class.getName(), PersistedJsonV5.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV6.class.getName(), PersistedJsonV6.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV7.class.getName(), PersistedJsonV7.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV8.class.getName(), PersistedJsonV8.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedJsonV9.class.getName(), PersistedJsonV9.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, PersistedJsonV10.class.getName(), PersistedJsonV10.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedYamlV1.class.getName(), PersistedYamlV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, PersistedInvalidTailV1.class.getName(), PersistedInvalidTailV1.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PersistedNulV1.class.getName(), PersistedNulV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, PersistedUnpairedV1.class.getName(), PersistedUnpairedV1.class));
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator workflowPlanValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }
    }

    @Module
    static class SourceModule {

        private final List<DefinitionResource> resources;

        SourceModule(List<DefinitionResource> resources) {
            this.resources = List.copyOf(resources);
        }

        @Provides
        @IntoSet
        WorkflowDefinitionSource source() {
            return () -> resources;
        }
    }

    @Module
    static class CapturingStoreModule {

        private final WorkflowDefinitionRegistryStore store;
        private final WorkflowDefinitionsConfig config;

        CapturingStoreModule(WorkflowDefinitionRegistryStore store, WorkflowDefinitionsConfig config) {
            this.store = store;
            this.config = config;
        }

        @Provides
        @Singleton
        WorkflowDefinitionRegistryStore registryStore() {
            return store;
        }

        @Provides
        @Singleton
        WorkflowDefinitionsConfig workflowDefinitionsConfig() {
            return config;
        }
    }

    private record CapturedDocument(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {}

    private static final class CapturingStore {

        private final List<CapturedDocument> documents = new CopyOnWriteArrayList<>();
        private final WorkflowDefinitionRegistryStore store = Mockito.mock(WorkflowDefinitionRegistryStore.class);

        CapturingStore() {
            Mockito.when(store.find(Mockito.any(DefinitionKey.class)))
                    .thenReturn(Future.succeededFuture(java.util.Optional.empty()));
            Mockito.doAnswer(invocation -> {
                        documents.add(new CapturedDocument(invocation.getArgument(0), invocation.getArgument(1)));
                        return Future.succeededFuture();
                    })
                    .when(store)
                    .insertCandidate(
                            Mockito.any(WorkflowDefinitionRecord.class), Mockito.any(WorkflowDefinitionDocument.class));
        }
    }

    @Test
    @DisplayName(
            "real readers preserve supported encodings while raw admission and no-store sources keep their contracts")
    void preservesReaderCharactersAndRawAdmission(Vertx vertx, VertxTestContext context) throws java.io.IOException {
        ReaderGraph graph =
                graph(vertx, List.of(staticYaml(), staticLargeJson(), malformedTailJson(), validTrailingJson()));
        String json = json("reader-encoding-😀", 1, ReaderEncodingUnicodeV1.class);

        assertThat(graph.workflowRegistry().resolvePinned("reader-static-yaml", 1))
                .isNotNull();
        assertThat(graph.workflowRegistry().resolvePinned("reader-static-json", 1))
                .isNotNull();
        assertThat(graph.workflowRegistry().resolvePinned("reader-static-json-large", 1))
                .isNotNull();
        assertThat(graph.workflowRegistry().resolvePinned("reader-valid-trailing-json", 1))
                .isNotNull();

        for (byte[] encoded : jsonEncodings(json)) {
            assertThat(graph.parser().parse(encoded, DocumentFormat.JSON).definitionId())
                    .isEqualTo("reader-encoding-😀");
        }
        assertThat(jsonEncodings(json)).hasSize(10);
        assertThat(graph.parser()
                        .parse(
                                utf8Bom(yaml("reader-static-yaml", 1, StaticYamlV1.class)
                                        .replace("\n", "\r\n")
                                        .getBytes(StandardCharsets.UTF_8)),
                                DocumentFormat.YAML)
                        .definitionId())
                .isEqualTo("reader-static-yaml");
        assertThat(graph.pipeline()
                        .load(new DefinitionResource(
                                json.getBytes(StandardCharsets.UTF_8),
                                DocumentFormat.JSON,
                                metadata("reader-encoding")))
                        .planHash())
                .hasSize(64);

        String escapedIdentifier = "reader-encod" + "\\" + "u0069ng";
        assertThat(graph.parser()
                        .parse(
                                json.replace("reader-encoding", escapedIdentifier)
                                        .getBytes(StandardCharsets.UTF_8),
                                DocumentFormat.JSON)
                        .definitionId())
                .isEqualTo("reader-encoding-😀");
        JsonNode aliasTree = graph.mapperFactory().yamlMapper().readTree(aliasHeavyYaml());
        assertThat(aliasTree.path("anchor").asText()).isEqualTo("expanded-value");
        assertThat(aliasTree.path("aliases").size()).isEqualTo(40);
        for (JsonNode alias : aliasTree.path("aliases")) {
            assertThat(alias.asText()).isEqualTo("source");
        }

        byte[] yamlUtf16 = withBom(
                StandardCharsets.UTF_16BE,
                new byte[] {(byte) 0xfe, (byte) 0xff},
                yaml("reader-static-yaml", 1, StaticYamlV1.class));
        assertThatThrownBy(() -> graph.parser().parse(yamlUtf16, DocumentFormat.YAML))
                .isInstanceOf(WorkflowDefinitionParseException.class);

        Future<CompiledDefinitionRef> exactLimit = graph.definitionService()
                .load(
                        yamlAtByteLength("reader-exact-limit", 1, ExactLimitV1.class, DEFAULT_LIMIT),
                        DocumentFormat.YAML,
                        metadata("exact-limit"),
                        null);
        exactLimit
                .compose(exact -> {
                    assertThat(exact.definitionId()).isEqualTo("reader-exact-limit");
                    byte[] overLimitInvalid = new byte[DEFAULT_LIMIT + 1];
                    java.util.Arrays.fill(overLimitInvalid, (byte) '[');
                    Future<CompiledDefinitionRef> rejected = graph.definitionService()
                            .load(overLimitInvalid, DocumentFormat.YAML, metadata("over-limit"), null);
                    return rejected.transform(result -> {
                        assertThat(result.failed()).isTrue();
                        assertThat(result.cause()).isNotInstanceOf(WorkflowDefinitionParseException.class);
                        return Future.succeededFuture();
                    });
                })
                .onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    @Test
    @DisplayName("durable decoding preserves supported source text and real loaders recompile captured text")
    void persistsAndRestoresNormalizedSource(Vertx vertx, VertxTestContext context) {
        CapturingStore capturingStore = new CapturingStore();
        DurableReaderGraph graph = DaggerDefinitionSourceTextTest_DurableReaderGraph.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .capturingStoreModule(new CapturingStoreModule(
                        capturingStore.store, new WorkflowDefinitionsConfig(30, DEFAULT_LIMIT)))
                .build();

        Future<Void> work = Future.succeededFuture();
        for (EncodedSource source : persistedJsonSources()) {
            work = work.compose(ignored -> graph.definitionService()
                    .load(source.bytes(), DocumentFormat.JSON, metadata(source.uri()), null)
                    .map(compiled -> {
                        assertThat(compiled.planHash()).isNotBlank();
                        assertCapturedAndRecompiled(graph, capturingStore, source, DocumentFormat.JSON, compiled);
                        return (Void) null;
                    }));
        }

        String yamlSource = persistedYamlSource();
        byte[] yamlBytes =
                withBom(StandardCharsets.UTF_8, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, yamlSource);
        EncodedSource yaml = new EncodedSource("yaml", "reader-persisted-yaml", 1, "\uFEFF" + yamlSource, yamlBytes);
        work = work.compose(ignored -> graph.definitionService()
                .load(yaml.bytes(), DocumentFormat.YAML, metadata(yaml.uri()), null)
                .map(compiled -> {
                    assertCapturedAndRecompiled(graph, capturingStore, yaml, DocumentFormat.YAML, compiled);
                    return (Void) null;
                }));

        for (EncodedSource invalid : invalidPersistedSources()) {
            work = work.compose(ignored -> {
                int capturedBefore = capturingStore.documents.size();
                Future<CompiledDefinitionRef> load = graph.definitionService()
                        .load(invalid.bytes(), invalid.format(), metadata(invalid.uri()), null);
                return load.transform(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                    assertThat(result.cause()).isNotInstanceOf(WorkflowDefinitionParseException.class);
                    assertThat(capturingStore.documents).hasSize(capturedBefore);
                    return Future.succeededFuture();
                });
            });
        }

        work.onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    private static List<EncodedSource> persistedJsonSources() {
        List<Class<?>> contracts = List.of(
                PersistedJsonV1.class,
                PersistedJsonV2.class,
                PersistedJsonV3.class,
                PersistedJsonV4.class,
                PersistedJsonV5.class,
                PersistedJsonV6.class,
                PersistedJsonV7.class,
                PersistedJsonV8.class,
                PersistedJsonV9.class,
                PersistedJsonV10.class);
        List<EncodedSource> sources = new java.util.ArrayList<>();
        for (int index = 0; index < contracts.size(); index++) {
            int version = index + 1;
            String source = json("reader-persisted-json", version, contracts.get(index));
            if (version == 10) {
                source += "\ntrailing text";
            }
            sources.add(new EncodedSource(
                    "json-" + version, "reader-persisted-json", version, source, encodeJson(source, index)));
        }
        return List.copyOf(sources);
    }

    private static byte[] encodeJson(String source, int encodingIndex) {
        return switch (encodingIndex) {
            case 0 -> source.getBytes(StandardCharsets.UTF_8);
            case 1 -> utf8Bom(source.getBytes(StandardCharsets.UTF_8));
            case 2 -> withBom(StandardCharsets.UTF_16BE, new byte[] {(byte) 0xfe, (byte) 0xff}, source);
            case 3 -> withBom(StandardCharsets.UTF_16LE, new byte[] {(byte) 0xff, (byte) 0xfe}, source);
            case 4 -> withBom(Charset.forName("UTF-32BE"), new byte[] {0, 0, (byte) 0xfe, (byte) 0xff}, source);
            case 5 -> withBom(Charset.forName("UTF-32LE"), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0}, source);
            case 6 -> withoutBom(StandardCharsets.UTF_16BE, source);
            case 7 -> withoutBom(StandardCharsets.UTF_16LE, source);
            case 8 -> withoutBom(Charset.forName("UTF-32BE"), source);
            case 9 -> withoutBom(Charset.forName("UTF-32LE"), source);
            default -> throw new IllegalArgumentException("Unsupported JSON encoding case: " + encodingIndex);
        };
    }

    private static String persistedYamlSource() {
        return ("""
                definitionId: reader-persisted-yaml
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: reader.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                # Preserve comments, supplementary text 😀, and source line endings.
                """)
                .formatted(ReaderState.class.getName(), PersistedYamlV1.class.getName(), ReaderStart.class.getName())
                .replace("\n", "\r\n");
    }

    private static List<EncodedSource> invalidPersistedSources() {
        String malformedJson = json("reader-persisted-invalid-tail", 1, PersistedInvalidTailV1.class);
        byte[] malformedUtf8 = append(malformedJson.getBytes(StandardCharsets.UTF_8), (byte) 0xff);
        String nulJson = json("reader-persisted-nul", 1, PersistedNulV1.class);
        byte[] decodedNul = append(nulJson.getBytes(StandardCharsets.UTF_8), (byte) 0);
        String unpairedJson = json("reader-persisted-unpaired", 1, PersistedUnpairedV1.class);
        byte[] utf16 = withBom(StandardCharsets.UTF_16BE, new byte[] {(byte) 0xfe, (byte) 0xff}, unpairedJson);
        byte[] unpairedSurrogate = append(utf16, (byte) 0xd8, (byte) 0x00);
        return List.of(
                new EncodedSource("malformed-tail", "reader-persisted-invalid-tail", 1, malformedJson, malformedUtf8),
                new EncodedSource("nul-tail", "reader-persisted-nul", 1, nulJson, decodedNul),
                new EncodedSource(
                        "unpaired-surrogate", "reader-persisted-unpaired", 1, unpairedJson, unpairedSurrogate));
    }

    private static void assertCapturedAndRecompiled(
            DurableReaderGraph graph,
            CapturingStore store,
            EncodedSource source,
            DocumentFormat format,
            CompiledDefinitionRef compiled) {
        assertThat(store.documents).anySatisfy(captured -> {
            assertThat(captured.record().key())
                    .isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, source.definitionId(), source.version()));
            assertThat(captured.document().key()).isEqualTo(captured.record().key());
            assertThat(captured.document().document()).isEqualTo(source.expectedText());
            assertThat(captured.document().documentFormat()).isEqualTo(format.name());
            assertThat(captured.record().planHash()).isEqualTo(compiled.planHash());

            PersistedDefinitionLoader loader = graph.persistedDefinitionLoaders().stream()
                    .filter(candidate -> candidate.supports(format.name()))
                    .findFirst()
                    .orElseThrow();
            var restored = loader.load(captured.record(), captured.document());
            assertThat(loader.planHash(restored)).isEqualTo(captured.record().planHash());
        });
    }

    private static byte[] append(byte[] source, byte... suffix) {
        byte[] result = java.util.Arrays.copyOf(source, source.length + suffix.length);
        System.arraycopy(suffix, 0, result, source.length, suffix.length);
        return result;
    }

    private record EncodedSource(String uri, String definitionId, int version, String expectedText, byte[] bytes) {

        DocumentFormat format() {
            return uri.startsWith("yaml") ? DocumentFormat.YAML : DocumentFormat.JSON;
        }
    }

    private static ReaderGraph graph(Vertx vertx, List<DefinitionResource> sources) {
        return DaggerDefinitionSourceTextTest_ReaderGraph.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .sourceModule(new SourceModule(sources))
                .build();
    }

    private static DefinitionResource staticYaml() {
        String padded = yaml("reader-static-yaml", 1, StaticYamlV1.class)
                + "\n# multi-byte static source beyond the raw byte limit "
                + "é".repeat(131_100);
        byte[] bytes = padded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= DEFAULT_LIMIT || padded.codePointCount(0, padded.length()) > DEFAULT_LIMIT) {
            throw new AssertionError(
                    "The no-store YAML fixture must exceed L in bytes but remain within L code points");
        }
        return new DefinitionResource(bytes, DocumentFormat.YAML, metadata("static-yaml"));
    }

    private static DefinitionResource malformedTailJson() {
        byte[] valid = json("reader-static-json", 1, StaticJsonV1.class).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(valid, valid.length + 1);
        bytes[bytes.length - 1] = (byte) 0xff;
        return new DefinitionResource(bytes, DocumentFormat.JSON, metadata("static-json"));
    }

    private static DefinitionResource staticLargeJson() {
        String document = json("reader-static-json-large", 1, StaticJsonLargeV1.class) + " ".repeat(DEFAULT_LIMIT);
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= DEFAULT_LIMIT) {
            throw new AssertionError("The no-store JSON fixture must exceed the raw byte limit");
        }
        return new DefinitionResource(bytes, DocumentFormat.JSON, metadata("static-json-large"));
    }

    private static DefinitionResource validTrailingJson() {
        String source = json("reader-valid-trailing-json", 1, StaticTrailingJsonV1.class) + "\ntrailing text";
        return new DefinitionResource(
                source.getBytes(StandardCharsets.UTF_8), DocumentFormat.JSON, metadata("trailing-json"));
    }

    private static byte[] aliasHeavyYaml() {
        StringBuilder source = new StringBuilder("""
                anchor: &source expanded-value
                aliases:
                """);
        for (int alias = 0; alias < 40; alias++) {
            source.append("  - *source\n");
        }
        return source.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] yamlAtByteLength(String id, long version, Class<?> contract, int targetLength) {
        String base = yaml(id, version, contract);
        int padding = targetLength - base.getBytes(StandardCharsets.UTF_8).length - 2;
        if (padding < 0) {
            throw new IllegalArgumentException("The target limit is shorter than the workflow source");
        }
        return (base + "\n#" + "x".repeat(padding)).getBytes(StandardCharsets.UTF_8);
    }

    private static String yaml(String id, long version, Class<?> contract) {
        return ("""
                definitionId: %s
                definitionVersion: %d
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: reader.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """)
                .formatted(id, version, ReaderState.class.getName(), contract.getName(), ReaderStart.class.getName());
    }

    private static String json(String id, long version, Class<?> contract) {
        return ("""
                {"definitionId":"%s","definitionVersion":%d,"stateType":"%s","contract":"%s",
                 "startPayloadType":"%s","initialStateMapper":"reader.init","initialStep":"done",
                 "steps":[{"id":"done","type":"complete"}]}
                """)
                .formatted(id, version, ReaderState.class.getName(), contract.getName(), ReaderStart.class.getName());
    }

    private static List<byte[]> jsonEncodings(String json) {
        return List.of(
                json.getBytes(StandardCharsets.UTF_8),
                utf8Bom(json.getBytes(StandardCharsets.UTF_8)),
                withBom(StandardCharsets.UTF_16BE, new byte[] {(byte) 0xfe, (byte) 0xff}, json),
                withBom(StandardCharsets.UTF_16LE, new byte[] {(byte) 0xff, (byte) 0xfe}, json),
                withBom(Charset.forName("UTF-32BE"), new byte[] {0, 0, (byte) 0xfe, (byte) 0xff}, json),
                withBom(Charset.forName("UTF-32LE"), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0}, json),
                withoutBom(StandardCharsets.UTF_16BE, json),
                withoutBom(StandardCharsets.UTF_16LE, json),
                withoutBom(Charset.forName("UTF-32BE"), json),
                withoutBom(Charset.forName("UTF-32LE"), json));
    }

    private static byte[] utf8Bom(byte[] bytes) {
        byte[] result = new byte[bytes.length + 3];
        result[0] = (byte) 0xef;
        result[1] = (byte) 0xbb;
        result[2] = (byte) 0xbf;
        System.arraycopy(bytes, 0, result, 3, bytes.length);
        return result;
    }

    private static byte[] withBom(Charset charset, byte[] bom, String value) {
        byte[] content = value.getBytes(charset);
        return ByteBuffer.allocate(bom.length + content.length)
                .put(bom)
                .put(content)
                .array();
    }

    private static byte[] withoutBom(Charset charset, String value) {
        return value.getBytes(charset);
    }

    private static SourceMetadata metadata(String uri) {
        return new SourceMetadata("reader-test", uri, "revision-1", null, null, Instant.now());
    }
}
