// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.compiler.DecisionRouteCompiler;
import dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.ProductionParserFixture;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.definition.validator.Violation;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link WorkflowDefinitionBootstrap}:
 * <ul>
 *   <li>One source with one good resource registers into the registry.</li>
 *   <li>One source with two good resources (different ids) both register.</li>
 *   <li>One source with duplicate {@code (id, version)}: bootstrap throws a structured
 *       {@link WorkflowDefinitionLoadException}; registry untouched.</li>
 *   <li>One source with one bad + one good resource: bootstrap collects ALL violations into one
 *       exception; registry untouched (no partial registration).</li>
 *   <li>Two sources providing the same {@code (id, version)}: hard error, registry untouched.</li>
 * </ul>
 */
class WorkflowDefinitionBootstrapTest {

    // --- Test types ---

    record TaskState(String id) {}

    record StartTask(String id) {}

    @WorkflowContract
    interface BootAlphaV1Contract {}

    @WorkflowContract
    interface BootBetaV1Contract {}

    @WorkflowContract
    interface BootGammaV1Contract {}

    /** One contract shared by several documents; its annotation attributes name an id and version nobody has. */
    @WorkflowContract
    interface ParityContract {}

    private static final String SIXTY_FOUR_CODE_POINTS =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OVER_LONG_ID =
            SIXTY_FOUR_CODE_POINTS + SIXTY_FOUR_CODE_POINTS + SIXTY_FOUR_CODE_POINTS + SIXTY_FOUR_CODE_POINTS;

    @WorkflowContract
    interface OverLongIdContract {}

    // --- Infrastructure ---

    private WorkflowDefinitionPipeline pipeline;
    private WorkflowDefinitionDocumentValidator validator;
    private WorkflowDefinitionCompiler compiler;
    private WorkflowDefinitionStore store;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory mapperFactory =
                Mockito.mock(dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());

        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "task.init", StartTask.class, TaskState.class, cmd -> new TaskState(cmd.id())))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", TaskState.class, s -> s);
        Mockito.when(payloadMappers.lookup(Mockito.anyString())).thenReturn(rawMapper);

        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(Set.of());
        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);

        WorkflowDefinitionParser parser = ServiceTestHelpers.realParser();
        validator = new WorkflowDefinitionDocumentValidator(lookup);
        pipeline = new WorkflowDefinitionPipeline(parser, validator, compiler);
        store = new WorkflowDefinitionStore();
    }

    // --- Helpers ---

    private byte[] yamlFor(String defId, long version, Class<?> contract) {
        String yaml =
                """
                definitionId: %s
                definitionVersion: %d
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: task.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(defId, version, TaskState.class.getName(), contract.getName(), StartTask.class.getName());
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionResource resourceFor(String defId, long version, Class<?> contract) {
        SourceMetadata meta =
                new SourceMetadata("test", "test://" + defId + "/" + version, null, null, null, Instant.now());
        return new DefinitionResource(yamlFor(defId, version, contract), DocumentFormat.YAML, meta);
    }

    private WorkflowDefinitionBootstrap bootstrap(Set<WorkflowDefinitionSource> sources) {
        return new WorkflowDefinitionBootstrap(pipeline, store, sources);
    }

    private DefaultWorkflowRegistry freshRegistry() {
        return new DefaultWorkflowRegistry();
    }

    // --- Happy path: one source, one resource ---

    @Nested
    @DisplayName("one source, one good resource")
    class OneGoodResource {

        @Test
        @DisplayName("bootstrap registers the definition and it resolves")
        void bootstrapsOneDefinition() {
            WorkflowDefinitionBootstrap bs =
                    bootstrap(Set.of(() -> List.of(resourceFor("boot-alpha", 1, BootAlphaV1Contract.class))));
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(reg.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-alpha", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("boot-alpha");
            assertThat(store.list()).hasSize(1);
            assertThat(store.list().iterator().next().status()).isEqualTo(ActivationStatus.AVAILABLE);
        }
    }

    // --- Packaged documents and the default scope ---

    @Test
    @DisplayName("packaged documents load into the default scope and a scope field is rejected")
    void packagedDocumentsLoadIntoTheDefaultScopeAndRejectAScopeField() {
        // Given: the production parser, one valid packaged document and a second that also names a scope
        WorkflowDefinitionPipeline strict =
                new WorkflowDefinitionPipeline(ProductionParserFixture.parser(), validator, compiler);
        DefinitionResource valid = resourceFor("boot-alpha", 1, BootAlphaV1Contract.class);
        byte[] withScopeField = (new String(yamlFor("boot-alpha", 1, BootAlphaV1Contract.class), StandardCharsets.UTF_8)
                        + "scope: acme\n")
                .getBytes(StandardCharsets.UTF_8);
        DefinitionResource scoped = new DefinitionResource(
                withScopeField,
                DocumentFormat.YAML,
                new SourceMetadata("test", "test://boot-alpha/scoped", null, null, null, Instant.now()));

        // When: the bootstrap contributes the valid document
        WorkflowDefinitionStore acceptingStore = new WorkflowDefinitionStore();
        DefaultWorkflowRegistry accepting = freshRegistry();
        new WorkflowDefinitionBootstrap(strict, acceptingStore, Set.of(() -> List.of(valid))).contribute(accepting);

        // Then: it is registered under the default scope and under no other
        assertThat(accepting.find(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-alpha", 1L)))
                .isPresent();
        assertThat(accepting.find(new DefinitionKey("acme", "boot-alpha", 1L))).isEmpty();
        assertThat(accepting.allRegistered()).hasSize(1);

        // When: the bootstrap contributes the document that carries a scope field
        WorkflowDefinitionStore rejectingStore = new WorkflowDefinitionStore();
        DefaultWorkflowRegistry rejecting = freshRegistry();
        WorkflowDefinitionBootstrap withScope =
                new WorkflowDefinitionBootstrap(strict, rejectingStore, Set.of(() -> List.of(scoped)));

        // Then: it fails as a parse failure attributed to the resource, and nothing is registered or stored
        assertThatThrownBy(() -> withScope.contribute(rejecting))
                .isInstanceOf(WorkflowDefinitionLoadException.class)
                .satisfies(ex -> {
                    List<Violation> violations =
                            ((WorkflowDefinitionLoadException) ex).violations().toList();
                    assertThat(violations).extracting(Violation::code).containsExactly("DOCUMENT_PARSE_FAILURE");
                    assertThat(violations.get(0).message()).contains("test://boot-alpha/scoped");
                });
        assertThat(rejecting.allRegistered()).isEmpty();
        assertThat(rejecting.find(new DefinitionKey("acme", "boot-alpha", 1L))).isEmpty();
        assertThat(rejectingStore.list()).isEmpty();
    }

    // --- Two good resources from one source ---

    @Nested
    @DisplayName("one source, two good resources with different ids")
    class TwoGoodResources {

        @Test
        @DisplayName("both definitions are registered")
        void bootstrapsTwoDefinitions() {
            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(
                    resourceFor("boot-alpha", 1, BootAlphaV1Contract.class),
                    resourceFor("boot-beta", 1, BootBetaV1Contract.class))));
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(reg.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-alpha", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("boot-alpha");
            assertThat(reg.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-beta", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("boot-beta");
            assertThat(store.list()).hasSize(2);
        }
    }

    // --- Duplicate (id, version) within one source ---

    @Nested
    @DisplayName("duplicate (id, version) within one source")
    class DuplicateWithinSource {

        @Test
        @DisplayName("bootstrap throws structured WorkflowDefinitionLoadException; registry is untouched")
        void duplicateWithinSourceThrows() {
            // Same contract for the duplicate (store collision happens at storeCandidate)
            // Use different contract but same (id, version) → store collision
            @WorkflowContract
            interface BootAlphaDupContract {}

            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(
                    resourceFor("boot-alpha", 1, BootAlphaV1Contract.class),
                    // This one has the same (id=boot-alpha, version=1) — store must reject it
                    new DefinitionResource(
                            yamlFor("boot-alpha", 1, BootAlphaDupContract.class),
                            DocumentFormat.YAML,
                            new SourceMetadata("test", "test://boot-alpha/1-dup", null, null, null, Instant.now())))));

            DefaultWorkflowRegistry reg = freshRegistry();

            assertThatThrownBy(() -> bs.contribute(reg))
                    .isInstanceOf(WorkflowDefinitionLoadException.class)
                    .satisfies(ex -> {
                        WorkflowDefinitionLoadException wex = (WorkflowDefinitionLoadException) ex;
                        assertThat(wex.violations().toList())
                                .anyMatch(v -> v.code().equals("DUPLICATE_DEFINITION"));
                    });

            // Registry must be untouched
            assertThat(reg.allRegistered()).isEmpty();
        }
    }

    // --- Documents sharing one contract ---

    @Test
    @DisplayName("both register, and only an identical (id, version) is a duplicate")
    void packagedDocumentsSharingOneContractBothRegister() {
        // Given: two packaged documents naming one contract, and a third repeating the first's id and version
        DefinitionResource first = resourceFor("parity-doc", 1, ParityContract.class);
        DefinitionResource variant = resourceFor("parity-variant", 1, ParityContract.class);
        DefinitionResource repeated = resourceFor("parity-doc", 1, ParityContract.class);

        // When: the bootstrap contributes the first two
        DefaultWorkflowRegistry registry = freshRegistry();
        bootstrap(Set.of(() -> List.of(first, variant))).contribute(registry);

        // Then: both are registered and name the same contract
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "parity-doc", 1L))
                        .contractName())
                .isEqualTo(ParityContract.class.getName());
        assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "parity-variant", 1L))
                        .contractName())
                .isEqualTo(ParityContract.class.getName());

        // When: the bootstrap contributes all three, the repeat coming from another source
        DefaultWorkflowRegistry other = freshRegistry();
        WorkflowDefinitionBootstrap withRepeat = new WorkflowDefinitionBootstrap(
                pipeline,
                new WorkflowDefinitionStore(),
                Set.of(() -> List.of(first, variant), () -> List.of(repeated)));

        // Then: exactly one violation is reported, the duplicate (id, version), and nothing is registered
        assertThatThrownBy(() -> withRepeat.contribute(other))
                .isInstanceOf(WorkflowDefinitionLoadException.class)
                .satisfies(ex -> {
                    List<Violation> violations =
                            ((WorkflowDefinitionLoadException) ex).violations().toList();
                    assertThat(violations).extracting(Violation::code).containsExactly("DUPLICATE_DEFINITION");
                    assertThat(violations.get(0).message()).contains("'parity-doc' v1");
                    assertThat(violations).extracting(Violation::code).doesNotContain("DUPLICATE_CONTRACT");
                });
        assertThat(other.allRegistered()).isEmpty();
    }

    // --- One bad (validation) + one good resource: all-or-nothing ---

    @Nested
    @DisplayName("one bad + one good resource: all-or-nothing")
    class OneBadOneGoodResource {

        @Test
        @DisplayName("both resources fail atomically; registry stays empty; store stays empty")
        void oneBadResourceCausesFullRollback() {
            byte[] badYaml = """
                    definitionId: ""
                    definitionVersion: 1
                    stateType: %s
                    contract: %s
                    startPayloadType: %s
                    initialStateMapper: task.init
                    initialStep: done
                    steps:
                      - id: done
                        type: complete
                    """.formatted(
                            TaskState.class.getName(), BootAlphaV1Contract.class.getName(), StartTask.class.getName())
                    .getBytes(StandardCharsets.UTF_8);

            SourceMetadata badMeta = new SourceMetadata("test", "test://bad", null, null, null, Instant.now());
            DefinitionResource badResource = new DefinitionResource(badYaml, DocumentFormat.YAML, badMeta);
            DefinitionResource goodResource = resourceFor("boot-beta", 1, BootBetaV1Contract.class);

            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(badResource, goodResource)));
            DefaultWorkflowRegistry reg = freshRegistry();

            assertThatThrownBy(() -> bs.contribute(reg)).isInstanceOf(WorkflowDefinitionLoadException.class);

            // Registry must be untouched — the good resource was not registered
            assertThat(reg.allRegistered()).isEmpty();
            assertThatThrownBy(() -> reg.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-beta", 1L)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);

            // Store must also be untouched — no candidates persisted from the partial run
            assertThat(store.list()).isEmpty();
        }
    }

    // --- One bad (parse failure) + one good resource: store stays clean ---

    @Nested
    @DisplayName("parse failure followed by good resource: store stays clean")
    class ParseFailureThenGoodResource {

        @Test
        @DisplayName("a genuine YAML parse error leaves store and registry both empty")
        void parseFailureLeavesStoreAndRegistryEmpty() {
            // Genuinely unparseable YAML for the workflow schema
            byte[] unparseableYaml = "{{{{ not valid yaml at all: [[".getBytes(StandardCharsets.UTF_8);
            SourceMetadata badMeta = new SourceMetadata("test", "test://bad-parse", null, null, null, Instant.now());
            DefinitionResource badResource = new DefinitionResource(unparseableYaml, DocumentFormat.YAML, badMeta);
            DefinitionResource goodResource = resourceFor("boot-gamma", 1, BootGammaV1Contract.class);

            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(goodResource, badResource)));
            DefaultWorkflowRegistry reg = freshRegistry();

            assertThatThrownBy(() -> bs.contribute(reg)).isInstanceOf(WorkflowDefinitionLoadException.class);

            // Store must be completely empty — the good resource must NOT have been stored
            assertThat(store.list()).isEmpty();
            // Registry must be untouched
            assertThat(reg.allRegistered()).isEmpty();
        }
    }

    // --- Two sources providing the same (id, version) ---

    @Nested
    @DisplayName("two sources providing the same (id, version)")
    class TwoSourcesWithDuplicate {

        @Test
        @DisplayName("bootstrap throws structured exception; registry untouched")
        void twoSourcesDuplicateFails() {
            // Source A contributes boot-alpha v1
            WorkflowDefinitionSource sourceA = () -> List.of(resourceFor("boot-alpha", 1, BootAlphaV1Contract.class));

            // Source B also contributes boot-alpha v1 — must be detected as duplicate
            @WorkflowContract
            interface BootAlphaSource2Contract {}

            WorkflowDefinitionSource sourceB = () -> List.of(new DefinitionResource(
                    yamlFor("boot-alpha", 1, BootAlphaSource2Contract.class),
                    DocumentFormat.YAML,
                    new SourceMetadata("test", "test://source-b/boot-alpha/1", null, null, null, Instant.now())));

            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(sourceA, sourceB));
            DefaultWorkflowRegistry reg = freshRegistry();

            assertThatThrownBy(() -> bs.contribute(reg))
                    .isInstanceOf(WorkflowDefinitionLoadException.class)
                    .satisfies(ex -> {
                        WorkflowDefinitionLoadException wex = (WorkflowDefinitionLoadException) ex;
                        assertThat(wex.violations().toList())
                                .anyMatch(v -> v.code().equals("DUPLICATE_DEFINITION"));
                    });

            assertThat(reg.allRegistered()).isEmpty();
        }
    }

    // --- Over-bound metadata or id in a later resource: attributed, all-or-nothing, in every mode ---

    @Nested
    @DisplayName("over-bound identity or provenance in the second resource")
    class OverBoundSecondResource {

        private static final String SECOND_URI = "test://second-over-bound";

        private final Optional<WorkflowDefinitionRegistryStore> absent = Optional.empty();
        private final Optional<WorkflowDefinitionRegistryStore> present =
                Optional.of(Mockito.mock(WorkflowDefinitionRegistryStore.class));

        @Test
        @DisplayName("300-code-point revision, no durable store: failure names the second URI, nothing registered")
        void overLongRevisionWithoutStore() {
            assertAttributedAtomicFailure(absent, secondWithRevision("r".repeat(300)));
        }

        @Test
        @DisplayName("300-code-point revision, durable store: failure names the second URI, nothing registered")
        void overLongRevisionWithStore() {
            assertAttributedAtomicFailure(present, secondWithRevision("r".repeat(300)));
        }

        @Test
        @DisplayName("70-code-point sourceType, no durable store: failure names the second URI, nothing registered")
        void overLongSourceTypeWithoutStore() {
            assertAttributedAtomicFailure(absent, secondWithSourceType("s".repeat(70)));
        }

        @Test
        @DisplayName("70-code-point sourceType, durable store: failure names the second URI, nothing registered")
        void overLongSourceTypeWithStore() {
            assertAttributedAtomicFailure(present, secondWithSourceType("s".repeat(70)));
        }

        @Test
        @DisplayName("256-code-point definition id, no durable store: failure names the second URI, nothing registered")
        void overLongDefinitionIdWithoutStore() {
            assertAttributedAtomicFailure(absent, secondWithOverLongId());
        }

        @Test
        @DisplayName("256-code-point definition id, durable store: failure names the second URI, nothing registered")
        void overLongDefinitionIdWithStore() {
            assertAttributedAtomicFailure(present, secondWithOverLongId());
        }

        private DefinitionResource secondWithRevision(String revision) {
            SourceMetadata meta = new SourceMetadata("test", SECOND_URI, revision, null, null, Instant.now());
            return new DefinitionResource(yamlFor("boot-beta", 1, BootBetaV1Contract.class), DocumentFormat.YAML, meta);
        }

        private DefinitionResource secondWithSourceType(String sourceType) {
            SourceMetadata meta = new SourceMetadata(sourceType, SECOND_URI, null, null, null, Instant.now());
            return new DefinitionResource(yamlFor("boot-beta", 1, BootBetaV1Contract.class), DocumentFormat.YAML, meta);
        }

        private DefinitionResource secondWithOverLongId() {
            SourceMetadata meta = new SourceMetadata("test", SECOND_URI, null, null, null, Instant.now());
            return new DefinitionResource(
                    yamlFor(OVER_LONG_ID, 1, OverLongIdContract.class), DocumentFormat.YAML, meta);
        }

        private void assertAttributedAtomicFailure(
                Optional<WorkflowDefinitionRegistryStore> durable, DefinitionResource invalidSecond) {
            // Given a valid first resource followed by a resource with an over-bound value
            DefinitionResource valid = resourceFor("boot-alpha", 1, BootAlphaV1Contract.class);
            WorkflowDefinitionBootstrap bs = new WorkflowDefinitionBootstrap(
                    pipeline, store, Set.of(() -> List.of(valid, invalidSecond)), durable, Optional.empty());
            DefaultWorkflowRegistry reg = freshRegistry();

            // When the bootstrap contributes
            // Then startup fails with a load exception naming the second resource ...
            assertThatThrownBy(() -> bs.contribute(reg))
                    .isInstanceOf(WorkflowDefinitionLoadException.class)
                    .satisfies(ex -> assertThat(((WorkflowDefinitionLoadException) ex)
                                    .violations()
                                    .toList())
                            .anyMatch(v -> v.message().contains(SECOND_URI)));
            // ... and nothing from the valid first resource was registered
            assertThat(reg.allRegistered()).isEmpty();
        }
    }

    // --- Empty sources ---

    @Nested
    @DisplayName("empty source set")
    class EmptySourceSet {

        @Test
        @DisplayName("bootstrap with empty source set does nothing; registry stays empty")
        void emptySourcesDoNothing() {
            WorkflowDefinitionBootstrap bs = bootstrap(Set.of());
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(reg.allRegistered()).isEmpty();
        }
    }
}
