// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultPayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.compiler.DecisionRouteCompiler;
import dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowType;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that a YAML or JSON document is registered through the same rules as a Java definition: a
 * document names its contract and types by logical name, is not compared with the contract annotation, may
 * share its contract with other definitions, and yields the same plan and plan hash whichever source it is
 * loaded from.
 */
class DocumentRegistrationParityTest {

    // --- Fixture types ---

    /**
     * The contract of every definition in this test. Its annotation attributes name an id and a version that
     * no definition has, and it declares one signal.
     */
    @WorkflowContract("parity-orders")
    interface ParityOrders {

        @WorkflowSignal("approve")
        Future<Void> approve(WorkflowInstanceId id, ParityApproval payload, @SignalDedupKey String dedupKey);
    }

    @WorkflowType("parity.state")
    record ParityState(String id) {}

    @WorkflowType("parity.start")
    record ParityStart(String id) {}

    @WorkflowType("parity.approval")
    record ParityApproval(String by) {}

    @WorkflowType("parity.decision")
    record ParityDecision(String note) {}

    @WorkflowType("parity.refund")
    record ParityRefund(String reason) {}

    /** An unannotated state class that no catalog lists. */
    record Legacy(String id) {}

    private static final String CODE_DEFINITION = "parity-code";
    private static final String CODE_DEFINITION_BAD = "parity-code-bad";
    private static final String DOCUMENT_DEFINITION = "parity-doc";
    private static final String DOCUMENT_DEFINITION_BAD = "parity-doc-bad";
    private static final String CONTRACT_NAME = "parity-orders";
    private static final String STATE_NAME = "parity.state";
    private static final String REFUSAL_FRAGMENT = "waits for signals that contract";

    private static final DefinitionKey KEY = new DefinitionKey(null, DOCUMENT_DEFINITION, 1);

    // --- Fixture definitions ---

    /** A Java definition of {@link ParityOrders} that waits, in turn, for each signal it is given. */
    static final class ParityCodeDefinition implements WorkflowDefinition<ParityState, ParityOrders> {

        private final String id;
        private final List<String> signals;

        ParityCodeDefinition(String id, String... signals) {
            this.id = id;
            this.signals = List.of(signals);
        }

        @Override
        public Class<ParityOrders> contract() {
            return ParityOrders.class;
        }

        @Override
        public Class<ParityState> stateType() {
            return ParityState.class;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ParityState> wf) {
            wf.init(ParityStart.class, start -> new ParityState(start.id()))
                    .initialStep(signals.isEmpty() ? "done" : "wait-0");
            for (int i = 0; i < signals.size(); i++) {
                String next = i + 1 < signals.size() ? "wait-" + (i + 1) : "done";
                wf.waitForSignal("wait-" + i, signals.get(i), ParityRefund.class)
                        .onSignal((state, payload) -> state)
                        .toStepOnSignal(next)
                        .build();
            }
            wf.complete("done");
        }
    }

    // --- Fixture documents ---

    /** A document using every kind of type name: contract, state, start, signal and decision payloads. */
    private static String fullDocument(String stateType) {
        return """
                definitionId: parity-doc
                definitionVersion: 1
                contract: parity-orders
                stateType: %s
                startPayloadType: parity.start
                initialStateMapper: parity.init
                initialStep: wait-approval
                steps:
                  - id: wait-approval
                    type: wait-signal
                    signal: approve
                    payloadType: parity.approval
                    stateReducer: parity.apply
                    next: review
                  - id: review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewer
                    decisions:
                      - name: accept
                        payloadType: parity.decision
                        applicator: parity.apply
                        next: done
                  - id: done
                    type: complete
                """.formatted(stateType);
    }

    /** A document whose state is an unannotated class named by its binary name. */
    private static final String LEGACY_DOCUMENT = """
            definitionId: legacy-doc
            definitionVersion: 1
            contract: parity-orders
            stateType: %s
            startPayloadType: parity.start
            initialStateMapper: legacy.init
            initialStep: done
            steps:
              - id: done
                type: complete
            """.formatted(Legacy.class.getName());

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixtureBytes(String name) {
        try (InputStream in = DocumentRegistrationParityTest.class.getResourceAsStream("/definitions/parity/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SourceMetadata metadata(String uri) {
        return new SourceMetadata("test", uri, null, null, null, Instant.now());
    }

    private static DefinitionResource resource(byte[] content, String uri) {
        return new DefinitionResource(content, DocumentFormat.YAML, metadata(uri));
    }

    /** Lists the contract and every annotated parity class under its logical name. */
    private static final WorkflowTypeCatalog PARITY_CATALOG = () -> List.of(
            new WorkflowTypeCatalogEntry(Kind.CONTRACT, CONTRACT_NAME, ParityOrders.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, STATE_NAME, ParityState.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, "parity.start", ParityStart.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, "parity.approval", ParityApproval.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, "parity.decision", ParityDecision.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, "parity.refund", ParityRefund.class));

    // --- Fixture modules and components ---

    /** Lists the parity classes and registers the callbacks the parity documents use. */
    @Module
    static class ParityModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog catalog() {
            return PARITY_CATALOG;
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor startMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "parity.init", ParityStart.class, ParityState.class, start -> new ParityState(start.id())));
        }

        @Provides
        @IntoSet
        static StateReducerContributor reducer() {
            return builder -> builder.register(new NamedStateReducer<>("parity.apply", ParityState.class, (s, e) -> s));
        }
    }

    /** Offers the document that names its state type by logical name as a packaged resource. */
    @Module
    static class LogicalPackagedSourceModule {

        @Provides
        @IntoSet
        static WorkflowDefinitionSource source() {
            return () -> List.of(resource(bytes(fullDocument(STATE_NAME)), "test://parity/logical"));
        }
    }

    /** Offers the document that names its state type by binary name as a packaged resource. */
    @Module
    static class BinaryPackagedSourceModule {

        @Provides
        @IntoSet
        static WorkflowDefinitionSource source() {
            return () -> List.of(resource(bytes(fullDocument(ParityState.class.getName())), "test://parity/binary"));
        }
    }

    /** Lists the contract and start payload, but not the unannotated state class. */
    @Module
    static class LegacyModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog catalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, CONTRACT_NAME, ParityOrders.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "parity.start", ParityStart.class));
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor startMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "legacy.init", ParityStart.class, Legacy.class, start -> new Legacy(start.id())));
        }
    }

    /** Offers the legacy document as a packaged resource. */
    @Module
    static class LegacyPackagedSourceModule {

        @Provides
        @IntoSet
        static WorkflowDefinitionSource source() {
            return () -> List.of(resource(bytes(LEGACY_DOCUMENT), "test://parity/legacy"));
        }
    }

    /** What a test reads from a component that registers a packaged document at startup. */
    interface PackagedView {

        WorkflowRegistry workflowRegistry();

        WorkflowDefinitionStore workflowDefinitionStore();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                ParityModule.class,
                LogicalPackagedSourceModule.class
            })
    interface LogicalPackagedComponent extends PackagedView {}

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                ParityModule.class,
                BinaryPackagedSourceModule.class
            })
    interface BinaryPackagedComponent extends PackagedView {}

    /** A component with no packaged source: documents reach it through the service or the durable store. */
    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, ParityModule.class})
    interface ServiceComponent {

        WorkflowDefinitionService workflowDefinitionService();

        WorkflowDefinitionStore workflowDefinitionStore();

        DocumentDefinitionLoader documentDefinitionLoader();

        WorkflowTypeNames workflowTypeNames();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                LegacyModule.class,
                LegacyPackagedSourceModule.class
            })
    interface LegacyPackagedComponent {

        WorkflowRegistry workflowRegistry();
    }

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, LegacyModule.class})
    interface LegacyServiceComponent {

        WorkflowDefinitionService workflowDefinitionService();
    }

    // --- Hand-built registration path (one shared type-name index) ---

    /** Records the definitions that reach the contributed validators of the registry. */
    private static final class RecordingValidator implements WorkflowDefinitionValidator {

        private final List<String> seen = new ArrayList<>();

        @Override
        public void validate(RegisteredWorkflowDefinition definition) {
            seen.add(definition.plan().definitionId() + " v" + definition.plan().definitionVersion());
        }
    }

    /** The pieces that register documents and Java definitions into one registry. */
    private static final class Registration {

        final RecordingValidator recorder = new RecordingValidator();
        final DefaultWorkflowRegistry registry;
        private final WorkflowDefinitionPipeline pipeline;
        private final DefaultWorkflowTypeNames typeNames;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Registration() {
            typeNames = new DefaultWorkflowTypeNames(List.of(PARITY_CATALOG));
            RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                    new DefaultPayloadMapperRegistry(Set.of()),
                    new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                            "parity.init",
                            ParityStart.class,
                            ParityState.class,
                            start -> new ParityState(start.id()))))),
                    new DefaultStateReducerRegistry(Set.of(
                            b -> b.register(new NamedStateReducer<>("parity.apply", ParityState.class, (s, e) -> s)))),
                    new DefaultStateMutatorRegistry(Set.of()),
                    new DefaultTimerResolverRegistry(Set.of()),
                    new DefaultFailMessageFactoryRegistry(Set.of()),
                    new DefaultSubjectResolverRegistry(Set.of()),
                    new DefaultTaskAssignmentResolverRegistry(Set.of()),
                    new DefaultBranchResultReducerRegistry(Set.of()),
                    new DefaultNamedConditionRegistry(Set.of()));
            WorkflowDefinitionMapperFactory mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
            Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
            ExpressionProfile profile = new CelExpressionProfile();
            DecisionRouteCompiler routes =
                    new DecisionRouteCompiler(profile, new DefaultNamedConditionRegistry(Set.of()), mapperFactory);
            pipeline = new WorkflowDefinitionPipeline(
                    ServiceTestHelpers.realParser(),
                    new WorkflowDefinitionDocumentValidator(lookup, profile, typeNames),
                    new WorkflowDefinitionCompiler(lookup, profile, routes, typeNames),
                    typeNames);
            registry = new DefaultWorkflowRegistry(null, Set.of(recorder), typeNames);
        }

        /** Runs one bootstrap over one packaged source holding the given documents. */
        void bootstrap(DefinitionResource... resources) {
            WorkflowDefinitionSource source = () -> List.of(resources);
            new WorkflowDefinitionBootstrap(
                            pipeline,
                            new WorkflowDefinitionStore(),
                            Set.of(source),
                            Optional.empty(),
                            Optional.empty(),
                            typeNames)
                    .contribute(registry);
        }
    }

    // --- Compiled results of one document from one source ---

    private record Outcome(WorkflowPlan plan, String planHash, String contractName, String stateTypeName) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorkflowPlan planOf(WorkflowDefinition<?, ?> definition, WorkflowTypeNames typeNames) {
        WorkflowBuilder builder = new WorkflowBuilder();
        ((WorkflowDefinition) definition).define(builder);
        return builder.build(
                definition.definitionId(), definition.definitionVersion(), typeNames.nameOf(definition.stateType()));
    }

    private static Outcome viaPackagedSource(PackagedView component) {
        WorkflowPlan plan = component
                .workflowRegistry()
                .resolvePinned(DOCUMENT_DEFINITION, 1L)
                .plan();
        CompiledDefinition compiled =
                component.workflowDefinitionStore().get(KEY).orElseThrow().compiled();
        return new Outcome(plan, compiled.planHash(), compiled.contractName(), compiled.stateTypeName());
    }

    private static Outcome viaStore(String document) {
        ServiceComponent component = DaggerDocumentRegistrationParityTest_ServiceComponent.create();
        Instant now = Instant.now();
        WorkflowDefinitionRecord stored = new WorkflowDefinitionRecord(
                KEY,
                DefinitionKind.DOCUMENT,
                "a".repeat(64),
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance("test", "test://parity/store", null, null, null, now),
                "YAML",
                now,
                now);
        WorkflowDefinitionDocument decoded = DefinitionSourceText.decode(
                KEY, bytes(document), DocumentFormat.YAML, WorkflowDefinitionsConfig.defaults());

        WorkflowDefinition<?, ?> restored = component.documentDefinitionLoader().load(stored, decoded);
        component.documentDefinitionLoader().onRestored(stored, restored);

        CompiledDefinition cached =
                component.workflowDefinitionStore().get(KEY).orElseThrow().compiled();
        return new Outcome(
                planOf(restored, component.workflowTypeNames()),
                component.documentDefinitionLoader().planHash(restored),
                cached.contractName(),
                cached.stateTypeName());
    }

    private static Outcome viaService(String document) {
        ServiceComponent component = DaggerDocumentRegistrationParityTest_ServiceComponent.create();

        CompiledDefinitionRef loaded = ServiceTestHelpers.await(component
                .workflowDefinitionService()
                .load(bytes(document), DocumentFormat.YAML, metadata("test://parity/service"), null));

        CompiledDefinition compiled =
                component.workflowDefinitionStore().get(KEY).orElseThrow().compiled();
        return new Outcome(
                planOf(compiled.definition(), component.workflowTypeNames()),
                loaded.planHash(),
                compiled.contractName(),
                compiled.stateTypeName());
    }

    // --- Tests ---

    @Test
    @DisplayName("a Java definition and a document of one contract pass the same registration rules")
    void javaAndDocumentDefinitionsOfOneContractShareTheRegistrationPath() {
        // Given: a registry that records the definitions reaching its validators, a Java definition of the
        // contract, a document of the same contract, and a second document waiting for an undeclared signal
        Registration registration = new Registration();
        DefaultWorkflowRegistry registry = registration.registry;
        ParityCodeDefinition code = new ParityCodeDefinition(CODE_DEFINITION);
        DefinitionResource good = resource(fixtureBytes("parity-doc.yaml"), "test://parity/good");
        DefinitionResource bad = resource(fixtureBytes("parity-doc-bad.yaml"), "test://parity/bad");
        ParityCodeDefinition badCode = new ParityCodeDefinition(CODE_DEFINITION_BAD, "refund");

        // When: the Java definition registers, then the good document in one bootstrap run
        registry.register(code);
        registration.bootstrap(good);

        // Then: both reach the validators and the registry, each reporting the contract
        assertThat(registration.recorder.seen).containsExactly("parity-code v1", "parity-doc v1");
        assertThat(registry.registeredDefinition(CODE_DEFINITION, 1L)
                        .orElseThrow()
                        .contract())
                .isEqualTo(ParityOrders.class);
        assertThat(registry.registeredDefinition(DOCUMENT_DEFINITION, 1L)
                        .orElseThrow()
                        .contract())
                .isEqualTo(ParityOrders.class);
        assertThat(registry.resolvePinned(CODE_DEFINITION, 1L).contractName()).isEqualTo(CONTRACT_NAME);
        assertThat(registry.resolvePinned(DOCUMENT_DEFINITION, 1L).contractName())
                .isEqualTo(CONTRACT_NAME);

        // When: the bad document is bootstrapped in a second run, and a Java definition awaiting the same signal
        // registers
        Throwable documentRefusal = catchThrowable(() -> registration.bootstrap(bad));
        Throwable codeRefusal = catchThrowable(() -> registry.register(badCode));

        // Then: both are refused alike, naming their own definition and version, before any validator saw them
        assertThat(documentRefusal)
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining(DOCUMENT_DEFINITION_BAD + "' v1")
                .hasMessageContaining(REFUSAL_FRAGMENT)
                .hasMessageContaining(ParityOrders.class.getName())
                .hasMessageContaining("refund");
        assertThat(codeRefusal)
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining(CODE_DEFINITION_BAD + "' v1");
        assertThat(documentRefusal.getClass()).isEqualTo(codeRefusal.getClass());
        assertThat(documentRefusal.getMessage().replace(DOCUMENT_DEFINITION_BAD, "<id>"))
                .isEqualTo(codeRefusal.getMessage().replace(CODE_DEFINITION_BAD, "<id>"));
        assertThat(registration.recorder.seen).containsExactly("parity-code v1", "parity-doc v1");
    }

    @Test
    @DisplayName("one document yields one plan and one plan hash from a packaged source, the store and the service")
    void sameDocumentFromPackagedStoreAndServiceSourcesYieldsOnePlanAndHash() {
        // Given: one document naming everything by logical name, and a copy naming its state by binary name
        String logical = fullDocument(STATE_NAME);

        // When: the logical document is compiled from each source, and the binary-name copy from a packaged one
        Outcome packaged = viaPackagedSource(DaggerDocumentRegistrationParityTest_LogicalPackagedComponent.create());
        Outcome stored = viaStore(logical);
        Outcome served = viaService(logical);
        Outcome binaryName = viaPackagedSource(DaggerDocumentRegistrationParityTest_BinaryPackagedComponent.create());

        // Then: the plans and the plan hashes agree, whichever source the bytes came from
        assertThat(stored.plan()).isEqualTo(packaged.plan());
        assertThat(served.plan()).isEqualTo(packaged.plan());
        assertThat(stored.planHash()).isEqualTo(packaged.planHash());
        assertThat(served.planHash()).isEqualTo(packaged.planHash());
        assertThat(packaged.plan().planHash()).isEqualTo(packaged.planHash());
        // And: naming the state by binary name instead of logical name changes neither plan nor hash
        assertThat(binaryName.plan()).isEqualTo(packaged.plan());
        assertThat(binaryName.planHash()).isEqualTo(packaged.planHash());
        // And: every source reports the logical contract and state names
        assertThat(packaged.contractName()).as("packaged contract name").isEqualTo(CONTRACT_NAME);
        assertThat(packaged.stateTypeName()).as("packaged state type name").isEqualTo(STATE_NAME);
        assertThat(stored.contractName()).as("stored contract name").isEqualTo(CONTRACT_NAME);
        assertThat(stored.stateTypeName()).as("stored state type name").isEqualTo(STATE_NAME);
        assertThat(served.contractName()).as("service contract name").isEqualTo(CONTRACT_NAME);
        assertThat(served.stateTypeName()).as("service state type name").isEqualTo(STATE_NAME);
        assertThat(binaryName.contractName()).as("binary-name contract name").isEqualTo(CONTRACT_NAME);
        assertThat(binaryName.stateTypeName()).as("binary-name state type name").isEqualTo(STATE_NAME);
    }

    @Test
    @DisplayName("a packaged document may name an unannotated class by binary name; a service load may not")
    void packagedBinaryNameRegistersWhileServiceLoadOfUnreferencedClassIsRefused() {
        // Given: component A with the legacy document as a packaged source, and component B with the same
        // catalog and no packaged source, whose class loader is watched
        LegacyPackagedComponent packaged = DaggerDocumentRegistrationParityTest_LegacyPackagedComponent.create();
        LegacyServiceComponent service = DaggerDocumentRegistrationParityTest_LegacyServiceComponent.create();
        RecordingClassLoader recorder = new RecordingClassLoader();

        // When: A starts and provides its registry, and B loads the same bytes through the service
        RuntimeWorkflow registered = packaged.workflowRegistry().resolveCurrent("legacy-doc");
        Future<CompiledDefinitionRef> loading = recorder.callWithContextLoader(() -> service.workflowDefinitionService()
                .load(bytes(LEGACY_DOCUMENT), DocumentFormat.YAML, metadata("test://parity/legacy-service"), null));

        // Then: A registered the document with the unannotated state class
        assertThat(registered.stateType()).isEqualTo(Legacy.class);
        // And: B refuses the name, never asked its class loader for the class, and holds no candidate
        assertThatThrownBy(() -> ServiceTestHelpers.await(loading))
                .isInstanceOf(WorkflowUnknownTypeNameException.class)
                .hasMessageContaining("stateType")
                .hasMessageContaining(Legacy.class.getName());
        assertThat(recorder.requested()).doesNotContain(Legacy.class.getName());
        assertThat(ServiceTestHelpers.await(service.workflowDefinitionService()
                        .list(new DefinitionFilter(DefinitionFilter.TenantScope.GLOBAL, null, null))))
                .isEmpty();
    }
}
