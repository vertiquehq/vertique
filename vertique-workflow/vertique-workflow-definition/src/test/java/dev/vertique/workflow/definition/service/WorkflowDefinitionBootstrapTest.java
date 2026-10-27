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
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
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
 *   <li>One source with one good resource is compiled and retained as an origin; nothing is registered.</li>
 *   <li>One source with two good resources (different ids) retains both.</li>
 *   <li>One source with duplicate {@code (id, version)}: bootstrap throws a structured
 *       {@link WorkflowDefinitionLoadException}; no origin is retained.</li>
 *   <li>One source with one bad + one good resource: bootstrap collects ALL violations into one
 *       exception; no origin is retained.</li>
 *   <li>Two sources providing the same {@code (id, version)}: hard error, no origin is retained.</li>
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
        return new WorkflowDefinitionBootstrap(
                pipeline, sources, new InMemoryWorkflowDefinitionRegistryStore(), Optional.empty());
    }

    private DefaultWorkflowRegistry freshRegistry() {
        return new DefaultWorkflowRegistry();
    }

    // --- Happy path: one source, one resource ---

    @Nested
    @DisplayName("one source, one good resource")
    class OneGoodResource {

        @Test
        @DisplayName("bootstrap retains the compiled document as an origin and registers nothing")
        void bootstrapsOneDefinition() {
            WorkflowDefinitionBootstrap bs =
                    bootstrap(Set.of(() -> List.of(resourceFor("boot-alpha", 1, BootAlphaV1Contract.class))));
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(bs.documentOrigins()).singleElement().satisfies(origin -> {
                assertThat(origin.key()).isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-alpha", 1L));
                assertThat(origin.contractName()).isEqualTo(BootAlphaV1Contract.class.getName());
                assertThat(origin.activateOnDeploy()).isTrue();
                assertThat(origin.nameBinding()).containsKey(TaskState.class.getName());
            });
            assertThat(reg.codeDefinitions()).isEmpty();
            assertThat(reg.find(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-alpha", 1L)))
                    .isEmpty();
        }
    }

    // --- Packaged documents and the default scope ---

    @Test
    @DisplayName("packaged documents are retained for the default scope and a scope field is rejected")
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
        DefaultWorkflowRegistry accepting = freshRegistry();
        WorkflowDefinitionBootstrap acceptingBootstrap = new WorkflowDefinitionBootstrap(
                strict, Set.of(() -> List.of(valid)), new InMemoryWorkflowDefinitionRegistryStore(), Optional.empty());
        acceptingBootstrap.contribute(accepting);

        // Then: its origin names the default scope and no other, and the registry holds nothing
        assertThat(acceptingBootstrap.documentOrigins())
                .extracting(origin -> origin.key().scopeKey())
                .containsExactly(WorkflowScopes.DEFAULT);
        assertThat(accepting.codeDefinitions()).isEmpty();

        // When: the bootstrap contributes the document that carries a scope field
        DefaultWorkflowRegistry rejecting = freshRegistry();
        WorkflowDefinitionBootstrap withScope = new WorkflowDefinitionBootstrap(
                strict, Set.of(() -> List.of(scoped)), new InMemoryWorkflowDefinitionRegistryStore(), Optional.empty());

        // Then: it fails as a parse failure attributed to the resource, and no origin is retained
        assertThatThrownBy(() -> withScope.contribute(rejecting))
                .isInstanceOf(WorkflowDefinitionLoadException.class)
                .satisfies(ex -> {
                    List<Violation> violations =
                            ((WorkflowDefinitionLoadException) ex).violations().toList();
                    assertThat(violations).extracting(Violation::code).containsExactly("DOCUMENT_PARSE_FAILURE");
                    assertThat(violations.get(0).message()).contains("test://boot-alpha/scoped");
                });
        assertThat(withScope.documentOrigins()).isEmpty();
        assertThat(rejecting.find(new DefinitionKey("acme", "boot-alpha", 1L))).isEmpty();
    }

    // --- Two good resources from one source ---

    @Nested
    @DisplayName("one source, two good resources with different ids")
    class TwoGoodResources {

        @Test
        @DisplayName("both definitions are retained")
        void bootstrapsTwoDefinitions() {
            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(
                    resourceFor("boot-alpha", 1, BootAlphaV1Contract.class),
                    resourceFor("boot-beta", 1, BootBetaV1Contract.class))));
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(bs.documentOrigins())
                    .extracting(origin -> origin.key().definitionId())
                    .containsExactlyInAnyOrder("boot-alpha", "boot-beta");
            assertThat(reg.codeDefinitions()).isEmpty();
        }
    }

    // --- Duplicate (id, version) within one source ---

    @Nested
    @DisplayName("duplicate (id, version) within one source")
    class DuplicateWithinSource {

        @Test
        @DisplayName("bootstrap throws structured WorkflowDefinitionLoadException; no origin is retained")
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

            // Nothing must be retained
            assertThat(bs.documentOrigins()).isEmpty();
        }
    }

    // --- Documents sharing one contract ---

    @Test
    @DisplayName("both are retained, and only an identical (id, version) is a duplicate")
    void packagedDocumentsSharingOneContractBothRegister() {
        // Given: two packaged documents naming one contract, and a third repeating the first's id and version
        DefinitionResource first = resourceFor("parity-doc", 1, ParityContract.class);
        DefinitionResource variant = resourceFor("parity-variant", 1, ParityContract.class);
        DefinitionResource repeated = resourceFor("parity-doc", 1, ParityContract.class);

        // When: the bootstrap contributes the first two
        WorkflowDefinitionBootstrap shared = bootstrap(Set.of(() -> List.of(first, variant)));
        shared.contribute(freshRegistry());

        // Then: both are retained and name the same contract
        assertThat(shared.documentOrigins())
                .extracting(origin -> origin.key().definitionId(), origin -> origin.contractName())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("parity-doc", ParityContract.class.getName()),
                        org.assertj.core.groups.Tuple.tuple("parity-variant", ParityContract.class.getName()));

        // When: the bootstrap contributes all three, the repeat coming from another source
        DefaultWorkflowRegistry other = freshRegistry();
        WorkflowDefinitionBootstrap withRepeat = new WorkflowDefinitionBootstrap(
                pipeline,
                Set.of(() -> List.of(first, variant), () -> List.of(repeated)),
                new InMemoryWorkflowDefinitionRegistryStore(),
                Optional.empty());

        // Then: exactly one violation is reported, the duplicate (id, version), and nothing is retained
        assertThatThrownBy(() -> withRepeat.contribute(other))
                .isInstanceOf(WorkflowDefinitionLoadException.class)
                .satisfies(ex -> {
                    List<Violation> violations =
                            ((WorkflowDefinitionLoadException) ex).violations().toList();
                    assertThat(violations).extracting(Violation::code).containsExactly("DUPLICATE_DEFINITION");
                    assertThat(violations.get(0).message()).contains("'parity-doc' v1");
                    assertThat(violations).extracting(Violation::code).doesNotContain("DUPLICATE_CONTRACT");
                });
        assertThat(withRepeat.documentOrigins()).isEmpty();
    }

    // --- One bad (validation) + one good resource: all-or-nothing ---

    @Nested
    @DisplayName("one bad + one good resource: all-or-nothing")
    class OneBadOneGoodResource {

        @Test
        @DisplayName("both resources fail atomically; nothing is retained")
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

            // Nothing is retained — the good resource did not survive the failure of the other
            assertThat(bs.documentOrigins()).isEmpty();
            assertThatThrownBy(() -> reg.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "boot-beta", 1L)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        }
    }

    // --- One bad (parse failure) + one good resource: store stays clean ---

    @Nested
    @DisplayName("parse failure followed by good resource: nothing is retained")
    class ParseFailureThenGoodResource {

        @Test
        @DisplayName("a genuine YAML parse error retains no origin and registers nothing")
        void parseFailureRetainsNothing() {
            // Genuinely unparseable YAML for the workflow schema
            byte[] unparseableYaml = "{{{{ not valid yaml at all: [[".getBytes(StandardCharsets.UTF_8);
            SourceMetadata badMeta = new SourceMetadata("test", "test://bad-parse", null, null, null, Instant.now());
            DefinitionResource badResource = new DefinitionResource(unparseableYaml, DocumentFormat.YAML, badMeta);
            DefinitionResource goodResource = resourceFor("boot-gamma", 1, BootGammaV1Contract.class);

            WorkflowDefinitionBootstrap bs = bootstrap(Set.of(() -> List.of(goodResource, badResource)));
            DefaultWorkflowRegistry reg = freshRegistry();

            assertThatThrownBy(() -> bs.contribute(reg)).isInstanceOf(WorkflowDefinitionLoadException.class);

            // The good resource must NOT have been retained
            assertThat(bs.documentOrigins()).isEmpty();
            assertThat(reg.codeDefinitions()).isEmpty();
        }
    }

    // --- Two sources providing the same (id, version) ---

    @Nested
    @DisplayName("two sources providing the same (id, version)")
    class TwoSourcesWithDuplicate {

        @Test
        @DisplayName("bootstrap throws structured exception; no origin is retained")
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

            assertThat(bs.documentOrigins()).isEmpty();
        }
    }

    // --- Over-bound metadata or id in a later resource: attributed, all-or-nothing, in every mode ---

    @Nested
    @DisplayName("over-bound identity or provenance in the second resource")
    class OverBoundSecondResource {

        private static final String SECOND_URI = "test://second-over-bound";

        private final WorkflowDefinitionRegistryStore durable = new InMemoryWorkflowDefinitionRegistryStore();

        @Test
        @DisplayName("300-code-point revision: failure names the second URI, nothing retained")
        void overLongRevisionWithStore() {
            assertAttributedAtomicFailure(durable, secondWithRevision("r".repeat(300)));
        }

        @Test
        @DisplayName("70-code-point sourceType: failure names the second URI, nothing retained")
        void overLongSourceTypeWithStore() {
            assertAttributedAtomicFailure(durable, secondWithSourceType("s".repeat(70)));
        }

        @Test
        @DisplayName("256-code-point definition id: failure names the second URI, nothing retained")
        void overLongDefinitionIdWithStore() {
            assertAttributedAtomicFailure(durable, secondWithOverLongId());
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
                WorkflowDefinitionRegistryStore durable, DefinitionResource invalidSecond) {
            // Given a valid first resource followed by a resource with an over-bound value
            DefinitionResource valid = resourceFor("boot-alpha", 1, BootAlphaV1Contract.class);
            WorkflowDefinitionBootstrap bs = new WorkflowDefinitionBootstrap(
                    pipeline, Set.of(() -> List.of(valid, invalidSecond)), durable, Optional.empty());
            DefaultWorkflowRegistry reg = freshRegistry();

            // When the bootstrap contributes
            // Then startup fails with a load exception naming the second resource ...
            assertThatThrownBy(() -> bs.contribute(reg))
                    .isInstanceOf(WorkflowDefinitionLoadException.class)
                    .satisfies(ex -> assertThat(((WorkflowDefinitionLoadException) ex)
                                    .violations()
                                    .toList())
                            .anyMatch(v -> v.message().contains(SECOND_URI)));
            // ... and nothing from the valid first resource was retained
            assertThat(bs.documentOrigins()).isEmpty();
        }
    }

    // --- Empty sources ---

    @Nested
    @DisplayName("empty source set")
    class EmptySourceSet {

        @Test
        @DisplayName("bootstrap with empty source set does nothing; no origin is retained")
        void emptySourcesDoNothing() {
            WorkflowDefinitionBootstrap bs = bootstrap(Set.of());
            DefaultWorkflowRegistry reg = freshRegistry();
            bs.contribute(reg);

            assertThat(bs.documentOrigins()).isEmpty();
            assertThat(reg.codeDefinitions()).isEmpty();
        }
    }
}
