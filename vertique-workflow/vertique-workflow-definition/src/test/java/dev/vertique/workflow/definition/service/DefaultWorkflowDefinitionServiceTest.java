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
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link DefaultWorkflowDefinitionService}:
 * <ul>
 *   <li>{@link DefaultWorkflowDefinitionService#load} then
 *       {@link DefaultWorkflowDefinitionService#activate} succeeds and makes the definition
 *       resolvable via the registry.</li>
 *   <li>A load failure with invalid YAML leaves the store untouched; a prior active version
 *       remains accessible.</li>
 *   <li>{@link DefaultWorkflowDefinitionService#activate} without prior load throws.</li>
 *   <li>Two distinct definitionIds loaded and activated concurrently both appear in the
 *       registry.</li>
 *   <li>A {@link RuntimeException} from {@code registry.register()} resets the ACTIVATING claim
 *       back to CANDIDATE so the caller can retry.</li>
 * </ul>
 */
class DefaultWorkflowDefinitionServiceTest {

    // --- Test types ---

    record OrderState(String id) {}

    record PlaceOrder(String id) {}

    @WorkflowContract(definitionId = "svc-order", definitionVersion = 1)
    interface SvcOrderV1Contract {}

    @WorkflowContract(definitionId = "svc-order", definitionVersion = 2)
    interface SvcOrderV2Contract {}

    @WorkflowContract(definitionId = "svc-item", definitionVersion = 1)
    interface SvcItemV1Contract {}

    // --- Infrastructure ---

    private DefaultWorkflowRegistry registry;
    private DefaultWorkflowDefinitionService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory mapperFactory =
                Mockito.mock(dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());

        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.init", PlaceOrder.class, OrderState.class, cmd -> new OrderState(cmd.id())))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", OrderState.class, s -> s);
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
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);

        WorkflowDefinitionParser parser = ServiceTestHelpers.realParser();
        WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(lookup);
        WorkflowDefinitionPipeline pipeline = new WorkflowDefinitionPipeline(parser, validator, compiler);

        WorkflowDefinitionStore store = new WorkflowDefinitionStore();
        registry = new DefaultWorkflowRegistry();
        service = new DefaultWorkflowDefinitionService(pipeline, store, () -> registry);
    }

    // --- Helpers ---

    private byte[] yamlFor(String defId, long version, Class<?> contract) {
        String yaml = """
                definitionId: %s
                definitionVersion: %d
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: order.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(
                        defId, version, OrderState.class.getName(), contract.getName(), PlaceOrder.class.getName());
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    private SourceMetadata testMeta() {
        return new SourceMetadata("test", null, null, null, null, Instant.now());
    }

    // --- Helpers (shareable pipeline factory) ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private WorkflowDefinitionPipeline buildPipeline() {
        dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory mapperFactory =
                Mockito.mock(dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());

        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.init", PlaceOrder.class, OrderState.class, cmd -> new OrderState(cmd.id())))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", OrderState.class, s -> s);
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
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);

        WorkflowDefinitionParser parser = ServiceTestHelpers.realParser();
        WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(lookup);
        return new WorkflowDefinitionPipeline(parser, validator, compiler);
    }

    // --- Tests ---

    @Nested
    @DisplayName("load then activate")
    class LoadThenActivate {

        @Test
        @DisplayName("load then activate makes the definition resolvable via the registry")
        void loadThenActivateSucceeds() {
            service.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());
            service.activate("svc-order", 1);

            RuntimeWorkflow rw = registry.resolveCurrent("svc-order");
            assertThat(rw.plan().definitionId()).isEqualTo("svc-order");
            assertThat(rw.plan().definitionVersion()).isEqualTo(1L);

            // list() reflects the activation
            Collection<CompiledDefinitionRef> refs = service.list();
            assertThat(refs).hasSize(1);
            assertThat(refs.iterator().next().status()).isEqualTo(ActivationStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("load failure isolation")
    class LoadFailureIsolation {

        @Test
        @DisplayName("invalid YAML load failure leaves store untouched; prior active v1 remains accessible")
        void invalidYamlDoesNotAffectPriorActive() {
            // Establish v1 as active
            service.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());
            service.activate("svc-order", 1);
            assertThat(registry.resolveCurrent("svc-order").plan().definitionVersion())
                    .isEqualTo(1L);

            // Attempt to load bad YAML for v2 — must fail
            byte[] badYaml = "this: is: not valid yaml: for: workflow".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> service.load(badYaml, DocumentFormat.YAML, testMeta()))
                    .isInstanceOf(WorkflowDefinitionParseException.class);

            // v1 still active in registry
            assertThat(registry.resolveCurrent("svc-order").plan().definitionVersion())
                    .isEqualTo(1L);
            // list still shows only 1 entry
            assertThat(service.list()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("activate without prior load")
    class ActivateWithoutLoad {

        @Test
        @DisplayName("activate without prior load throws WorkflowDefinitionException")
        void activateWithoutLoadThrows() {
            assertThatThrownBy(() -> service.activate("svc-order", 99))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("not found in the store");
        }
    }

    @Nested
    @DisplayName("activate lower version when higher already active")
    class ActivateLowerVersionAfterHigher {

        @Test
        @DisplayName(
                "load+activate v2, then load+activate v1 — registry resolves v2, store shows v2 ACTIVE v1 SUPERSEDED")
        void activateLowerVersionAfterHigherIsSuperseded() {
            service.load(yamlFor("svc-order", 2, SvcOrderV2Contract.class), DocumentFormat.YAML, testMeta());
            service.activate("svc-order", 2);

            service.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());
            service.activate("svc-order", 1);

            // Registry must return the higher (v2) version
            assertThat(registry.resolveCurrent("svc-order").plan().definitionVersion())
                    .isEqualTo(2L);

            // Store must reflect: v2 ACTIVE, v1 SUPERSEDED
            Collection<CompiledDefinitionRef> refs = service.list();
            assertThat(refs).hasSize(2);
            CompiledDefinitionRef v2Ref = refs.stream()
                    .filter(r -> r.definitionVersion() == 2)
                    .findFirst()
                    .orElseThrow();
            CompiledDefinitionRef v1Ref = refs.stream()
                    .filter(r -> r.definitionVersion() == 1)
                    .findFirst()
                    .orElseThrow();
            assertThat(v2Ref.status()).isEqualTo(ActivationStatus.ACTIVE);
            assertThat(v1Ref.status()).isEqualTo(ActivationStatus.SUPERSEDED);
        }
    }

    @Nested
    @DisplayName("concurrent load and activate of distinct definitionIds")
    class ConcurrentDistinctIds {

        @Test
        @DisplayName("two distinct definitions loaded and activated concurrently both appear in registry")
        void concurrentLoadAndActivateBothSucceed() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Void>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                service.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());
                service.activate("svc-order", 1);
                return null;
            }));
            futures.add(pool.submit(() -> {
                service.load(yamlFor("svc-item", 1, SvcItemV1Contract.class), DocumentFormat.YAML, testMeta());
                service.activate("svc-item", 1);
                return null;
            }));

            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            for (Future<Void> f : futures) {
                f.get(); // rethrow if any thread threw
            }

            assertThat(registry.resolveCurrent("svc-order").plan().definitionId())
                    .isEqualTo("svc-order");
            assertThat(registry.resolveCurrent("svc-item").plan().definitionId())
                    .isEqualTo("svc-item");
            assertThat(service.list()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("concurrent activate of the same (definitionId, version) — W1")
    class ConcurrentSameKeyActivation {

        @Test
        @DisplayName(
                "8 threads calling activate for same key — exactly one wins; final state is ACTIVE in registry and store")
        void concurrentActivateSameKeySerialized() throws Exception {
            // Load the candidate once
            service.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());

            int numThreads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(numThreads);
            List<Future<String>> outcomes = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                outcomes.add(pool.submit(() -> {
                    try {
                        service.activate("svc-order", 1);
                        return "ok";
                    } catch (Exception ex) {
                        return "err:" + ex.getMessage();
                    }
                }));
            }

            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // Collect results — no thread should have seen "already registered" from the registry
            long okCount = 0;
            for (Future<String> f : outcomes) {
                String result = f.get();
                if ("ok".equals(result)) {
                    okCount++;
                } else {
                    // Error messages are allowed (concurrent ACTIVATING detection) but NEVER
                    // the registry-level "already registered" duplicate error
                    assertThat(result).doesNotContain("already registered");
                }
            }

            // At least one thread must have succeeded
            assertThat(okCount).isGreaterThanOrEqualTo(1);

            // Final state: exactly one ACTIVE in store; registry resolves the definition
            assertThat(registry.resolveCurrent("svc-order").plan().definitionId())
                    .isEqualTo("svc-order");
            long activeCount = service.list().stream()
                    .filter(r -> r.status() == ActivationStatus.ACTIVE)
                    .count();
            assertThat(activeCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("activate — ACTIVATING reset on RuntimeException")
    class ActivatingResetOnRuntimeException {

        @Test
        @DisplayName("RuntimeException from registry.register() resets store entry to CANDIDATE")
        void runtimeExceptionInRegistryResetsStoreToCandidateForRetry() throws Exception {
            // Build a registry stub that throws WorkflowDefinitionException on first register() call
            dev.vertique.workflow.registry.WorkflowRegistry throwingRegistry =
                    new dev.vertique.workflow.registry.WorkflowRegistry() {
                        private int callCount = 0;

                        @Override
                        public void register(dev.vertique.workflow.dsl.WorkflowDefinition<?, ?> def) {
                            if (callCount++ == 0) {
                                throw new WorkflowDefinitionException("simulated registration failure");
                            }
                        }

                        @Override
                        public dev.vertique.workflow.registry.RuntimeWorkflow resolveCurrent(String id) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public dev.vertique.workflow.registry.RuntimeWorkflow resolvePinned(String id, long v) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public dev.vertique.workflow.contract.WorkflowContractMetadata contractMetadata(Class<?> c) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public java.util.Collection<dev.vertique.workflow.registry.RuntimeWorkflow> allRegistered() {
                            return java.util.List.of();
                        }
                    };

            WorkflowDefinitionStore errorStore = new WorkflowDefinitionStore();
            DefaultWorkflowDefinitionService svc =
                    new DefaultWorkflowDefinitionService(buildPipeline(), errorStore, () -> throwingRegistry);

            svc.load(yamlFor("svc-order", 1, SvcOrderV1Contract.class), DocumentFormat.YAML, testMeta());

            // activate() must propagate the RuntimeException (not swallow it)
            assertThatThrownBy(() -> svc.activate("svc-order", 1))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("simulated registration failure");

            // The store entry must have been reset to CANDIDATE (not stuck in ACTIVATING)
            ActivationStatus status = errorStore.currentStatus("svc-order", 1);
            assertThat(status)
                    .as("store entry must be CANDIDATE after RuntimeException so the activation can be retried")
                    .isEqualTo(ActivationStatus.CANDIDATE);
        }
    }

    @Nested
    @DisplayName("load() size cap — A1")
    class LoadSizeCap {

        @Test
        @DisplayName("document exceeding 256 KiB is rejected before parsing")
        void oversizedDocumentIsRejected() {
            byte[] oversized = new byte[DefaultWorkflowDefinitionService.MAX_DEFINITION_SIZE + 1];
            assertThatThrownBy(() -> service.load(oversized, DocumentFormat.YAML, testMeta()))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("size cap");
        }

        @Test
        @DisplayName("document exactly at the size cap is not rejected by the guard (may fail for other reasons)")
        void documentAtCapNotRejectedBySizeGuard() {
            byte[] atCap = new byte[DefaultWorkflowDefinitionService.MAX_DEFINITION_SIZE];
            // Must not throw WorkflowDefinitionException with "size cap" — may throw for other reasons
            try {
                service.load(atCap, DocumentFormat.YAML, testMeta());
            } catch (WorkflowDefinitionException ex) {
                assertThat(ex.getMessage()).doesNotContain("size cap");
            } catch (Exception ignored) {
                // parse/validation failure is expected for all-zero content
            }
        }
    }
}
