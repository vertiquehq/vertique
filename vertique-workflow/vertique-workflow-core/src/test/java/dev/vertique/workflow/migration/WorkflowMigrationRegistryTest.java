// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultWorkflowMigrationRegistry}.
 *
 * <p>Verifies the following invariants:
 * <ul>
 *   <li>Empty handler set → {@code find(...)} returns {@link Optional#empty()}, {@code all()} is
 *       empty.</li>
 *   <li>Single valid handler registered → {@code find(...)} returns it.</li>
 *   <li>Duplicate {@code (defId, from, target)} triple → constructor throws
 *       {@link WorkflowDefinitionException}.</li>
 *   <li>Source or target version not resident → constructor succeeds and {@code find()} still
 *       returns the handler: the lookup resolves no plan.</li>
 *   <li>Handler state type that differs from the registered plan → {@code find()} still returns the
 *       handler; the state-type check happens where the migration executes.</li>
 *   <li>{@code fromVersion >= targetVersion} → constructor throws {@link WorkflowDefinitionException}.</li>
 *   <li>Multiple non-overlapping handlers → all registered successfully.</li>
 *   <li>Handler registered with only v1 → construction succeeds; {@code find()} returns the
 *       handler before and after v2 is dynamically activated.</li>
 * </ul>
 */
class WorkflowMigrationRegistryTest {

    // --- Fixture state types ---

    record StateV1(String id) {}

    record StateV2(String id, int count) {}

    record StartCmd(String id) {}

    // --- Contract interfaces ---

    @WorkflowContract
    interface OrderSagaContract {}

    @WorkflowContract
    interface PaymentFlowContract {}

    // --- Workflow definitions ---

    static WorkflowDefinition<StateV2, OrderSagaContract> orderSagaV1Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContract> contract() {
                return OrderSagaContract.class;
            }

            @Override
            public Class<StateV2> stateType() {
                return StateV2.class;
            }

            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<StateV2> wf) {
                wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, OrderSagaContract> orderSagaV2Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContract> contract() {
                return OrderSagaContract.class;
            }

            @Override
            public Class<StateV2> stateType() {
                return StateV2.class;
            }

            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long definitionVersion() {
                return 2L;
            }

            @Override
            public void define(WorkflowBuilder<StateV2> wf) {
                wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, OrderSagaContract> orderSagaV3Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContract> contract() {
                return OrderSagaContract.class;
            }

            @Override
            public Class<StateV2> stateType() {
                return StateV2.class;
            }

            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long definitionVersion() {
                return 3L;
            }

            @Override
            public void define(WorkflowBuilder<StateV2> wf) {
                wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, PaymentFlowContract> paymentFlowV1Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<PaymentFlowContract> contract() {
                return PaymentFlowContract.class;
            }

            @Override
            public Class<StateV2> stateType() {
                return StateV2.class;
            }

            @Override
            public String definitionId() {
                return "payment-flow";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<StateV2> wf) {
                wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, PaymentFlowContract> paymentFlowV2Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<PaymentFlowContract> contract() {
                return PaymentFlowContract.class;
            }

            @Override
            public Class<StateV2> stateType() {
                return StateV2.class;
            }

            @Override
            public String definitionId() {
                return "payment-flow";
            }

            @Override
            public long definitionVersion() {
                return 2L;
            }

            @Override
            public void define(WorkflowBuilder<StateV2> wf) {
                wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    // --- Helper: build a registry with versions registered ---

    private static WorkflowRegistry registryWith(WorkflowDefinition<?, ?>... defs) {
        DefaultWorkflowRegistry reg = new DefaultWorkflowRegistry();
        for (WorkflowDefinition<?, ?> def : defs) {
            reg.register(def);
        }
        return reg;
    }

    // --- Minimal valid handler from StateV1 → StateV2 for "order-saga" ---

    static WorkflowMigrationHandler<StateV2, StateV2> orderSagaV1toV2Handler() {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long fromDefinitionVersion() {
                return 1L;
            }

            @Override
            public long targetDefinitionVersion() {
                return 2L;
            }

            @Override
            public Class<StateV2> sourceStateType() {
                return StateV2.class;
            }

            @Override
            public Class<StateV2> targetStateType() {
                return StateV2.class;
            }

            @Override
            public MigrationResult<StateV2> migrate(StateV2 sourceState, MigrationContext ctx) {
                return MigrationResult.anchorAtInitial(sourceState);
            }
        };
    }

    static WorkflowMigrationHandler<StateV2, StateV2> orderSagaV2toV3Handler() {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return "order-saga";
            }

            @Override
            public long fromDefinitionVersion() {
                return 2L;
            }

            @Override
            public long targetDefinitionVersion() {
                return 3L;
            }

            @Override
            public Class<StateV2> sourceStateType() {
                return StateV2.class;
            }

            @Override
            public Class<StateV2> targetStateType() {
                return StateV2.class;
            }

            @Override
            public MigrationResult<StateV2> migrate(StateV2 sourceState, MigrationContext ctx) {
                return MigrationResult.anchorAtInitial(sourceState);
            }
        };
    }

    static WorkflowMigrationHandler<StateV2, StateV2> paymentFlowV1toV2Handler() {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return "payment-flow";
            }

            @Override
            public long fromDefinitionVersion() {
                return 1L;
            }

            @Override
            public long targetDefinitionVersion() {
                return 2L;
            }

            @Override
            public Class<StateV2> sourceStateType() {
                return StateV2.class;
            }

            @Override
            public Class<StateV2> targetStateType() {
                return StateV2.class;
            }

            @Override
            public MigrationResult<StateV2> migrate(StateV2 sourceState, MigrationContext ctx) {
                return MigrationResult.anchorAtInitial(sourceState);
            }
        };
    }

    // --- Test groups ---

    @Nested
    @DisplayName("Empty handler set")
    class EmptyHandlerSet {

        private WorkflowRegistry workflowRegistry;

        @BeforeEach
        void setUp() {
            workflowRegistry = registryWith(orderSagaV1Def(), orderSagaV2Def());
        }

        @Test
        @DisplayName("find(...) returns Optional.empty() when no handlers registered")
        void findReturnsEmpty() {
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(), workflowRegistry);
            assertThat(reg.find("default", "order-saga", 1L, "default", 2L)).isEmpty();
        }

        @Test
        @DisplayName("all() returns empty collection when no handlers registered")
        void allReturnsEmpty() {
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(), workflowRegistry);
            assertThat(reg.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Single valid handler")
    class SingleValidHandler {

        @Test
        @DisplayName("find(...) returns the handler when key matches")
        void findReturnsSingleHandler() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            Optional<WorkflowMigrationHandler<?, ?>> found = reg.find("default", "order-saga", 1L, "default", 2L);
            assertThat(found).isPresent().containsSame(handler);
        }

        @Test
        @DisplayName("find(...) returns Optional.empty() for non-matching key")
        void findReturnsEmptyForMismatch() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            assertThat(reg.find("default", "order-saga", 1L, "default", 3L)).isEmpty();
            assertThat(reg.find("default", "other-def", 1L, "default", 2L)).isEmpty();
        }

        @Test
        @DisplayName("find(...) returns the handler without resolving either plan when neither version is resident")
        void findReturnsTheHandlerWhenNeitherVersionIsResident() {
            // Given: a handler for order-saga 1 -> 2 and a registry that holds neither version and fails the test
            // if the lookup asks it for a plan
            AtomicInteger planLookups = new AtomicInteger();
            WorkflowRegistry emptyRegistry = (WorkflowRegistry) Proxy.newProxyInstance(
                    WorkflowRegistry.class.getClassLoader(),
                    new Class<?>[] {WorkflowRegistry.class},
                    (proxy, method, args) -> {
                        planLookups.incrementAndGet();
                        throw new AssertionError("find must not ask the registry for a plan: " + method.getName());
                    });
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), emptyRegistry);

            // When: the handler is looked up
            Optional<WorkflowMigrationHandler<?, ?>> found = reg.find("default", "order-saga", 1L, "default", 2L);

            // Then: the handler is returned and no plan was resolved
            assertThat(found).isPresent().containsSame(handler);
            assertThat(planLookups).hasValue(0);
        }

        @Test
        @DisplayName("all() returns a collection with the single handler")
        void allReturnsSingleElement() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            assertThat(reg.all()).containsExactly(handler);
        }
    }

    @Nested
    @DisplayName("Duplicate handler triple")
    class DuplicateHandlerTriple {

        @Test
        @DisplayName("duplicate (defId, from, target) triple throws WorkflowDefinitionException listing both classes")
        void duplicateTripleThrows() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV2, StateV2> h1 = orderSagaV1toV2Handler();
            WorkflowMigrationHandler<StateV2, StateV2> h2 = orderSagaV1toV2Handler();

            assertThatThrownBy(() -> new DefaultWorkflowMigrationRegistry(Set.of(h1, h2), wfReg))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("1")
                    .hasMessageContaining("2");
        }
    }

    @Nested
    @DisplayName("Unregistered versions — find() returns the handler regardless")
    class UnregisteredVersionValidation {

        @Test
        @DisplayName("source version not in registry → construction succeeds; find() returns the handler")
        void sourceVersionUnregisteredDoesNotThrowAtConstructionAndFindReturnsTheHandler() {
            // Only v2 registered; handler migrates from v1 → v2; v1 is absent
            WorkflowRegistry wfReg = registryWith(orderSagaV2Def());
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version check is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns the handler although source version v1 is not registered
            assertThat(reg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(handler);
        }

        @Test
        @DisplayName("target version not in registry → construction succeeds; find() returns the handler")
        void targetVersionUnregisteredDoesNotThrowAtConstructionAndFindReturnsTheHandler() {
            // Only v1 registered; handler migrates from v1 → v2; v2 is absent
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def());
            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version check is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns the handler although target version v2 is not registered
            assertThat(reg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(handler);
        }
    }

    @Nested
    @DisplayName("State-type mismatch — not checked by find()")
    class StateTypeMismatchValidation {

        @Test
        @DisplayName("handler sourceStateType differs from plan stateType → find() still returns the handler")
        void sourceStateTypeMismatchDoesNotFailFind() {
            // orderSagaV1Def has stateType=StateV2, but this handler claims StateV1 as source type
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV1, StateV2> badHandler = new WorkflowMigrationHandler<>() {
                @Override
                public String definitionId() {
                    return "order-saga";
                }

                @Override
                public long fromDefinitionVersion() {
                    return 1L;
                }

                @Override
                public long targetDefinitionVersion() {
                    return 2L;
                }

                @Override
                public Class<StateV1> sourceStateType() {
                    return StateV1.class; // mismatch: plan v1 has StateV2
                }

                @Override
                public Class<StateV2> targetStateType() {
                    return StateV2.class;
                }

                @Override
                public MigrationResult<StateV2> migrate(StateV1 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(new StateV2(sourceState.id(), 0));
                }
            };

            // Construction must NOT throw
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() returns the handler; the state-type check belongs to the migration execution
            assertThat(reg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(badHandler);
        }

        @Test
        @DisplayName("handler targetStateType differs from plan stateType → find() still returns the handler")
        void targetStateTypeMismatchDoesNotFailFind() {
            // orderSagaV2Def has stateType=StateV2, but this handler claims StateV1 as target type
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV2, StateV1> badHandler = new WorkflowMigrationHandler<>() {
                @Override
                public String definitionId() {
                    return "order-saga";
                }

                @Override
                public long fromDefinitionVersion() {
                    return 1L;
                }

                @Override
                public long targetDefinitionVersion() {
                    return 2L;
                }

                @Override
                public Class<StateV2> sourceStateType() {
                    return StateV2.class;
                }

                @Override
                public Class<StateV1> targetStateType() {
                    return StateV1.class; // mismatch: plan v2 has StateV2
                }

                @Override
                public MigrationResult<StateV1> migrate(StateV2 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(new StateV1(sourceState.id()));
                }
            };

            // Construction must NOT throw
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() returns the handler; the state-type check belongs to the migration execution
            assertThat(reg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(badHandler);
        }
    }

    @Nested
    @DisplayName("Version direction validation")
    class VersionDirectionValidation {

        @Test
        @DisplayName("fromVersion == targetVersion → WorkflowDefinitionException")
        void sameVersionThrows() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV1, StateV1> sameVersionHandler = new WorkflowMigrationHandler<>() {
                @Override
                public String definitionId() {
                    return "order-saga";
                }

                @Override
                public long fromDefinitionVersion() {
                    return 1L;
                }

                @Override
                public long targetDefinitionVersion() {
                    return 1L; // same as from — invalid
                }

                @Override
                public Class<StateV1> sourceStateType() {
                    return StateV1.class;
                }

                @Override
                public Class<StateV1> targetStateType() {
                    return StateV1.class;
                }

                @Override
                public MigrationResult<StateV1> migrate(StateV1 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(sourceState);
                }
            };

            assertThatThrownBy(() -> new DefaultWorkflowMigrationRegistry(Set.of(sameVersionHandler), wfReg))
                    .isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("fromVersion > targetVersion (downgrade) → WorkflowDefinitionException")
        void downgradeThrows() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV2, StateV1> downgradeHandler = new WorkflowMigrationHandler<>() {
                @Override
                public String definitionId() {
                    return "order-saga";
                }

                @Override
                public long fromDefinitionVersion() {
                    return 2L;
                }

                @Override
                public long targetDefinitionVersion() {
                    return 1L; // downgrade — invalid
                }

                @Override
                public Class<StateV2> sourceStateType() {
                    return StateV2.class;
                }

                @Override
                public Class<StateV1> targetStateType() {
                    return StateV1.class;
                }

                @Override
                public MigrationResult<StateV1> migrate(StateV2 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(new StateV1(sourceState.id()));
                }
            };

            assertThatThrownBy(() -> new DefaultWorkflowMigrationRegistry(Set.of(downgradeHandler), wfReg))
                    .isInstanceOf(WorkflowDefinitionException.class);
        }
    }

    @Nested
    @DisplayName("Multiple non-overlapping handlers")
    class MultipleNonOverlappingHandlers {

        @Test
        @DisplayName("different defIds and different version pairs register without conflict")
        void multipleHandlersRegisteredSuccessfully() {
            WorkflowRegistry wfReg = registryWith(
                    orderSagaV1Def(), orderSagaV2Def(), orderSagaV3Def(), paymentFlowV1Def(), paymentFlowV2Def());

            WorkflowMigrationHandler<StateV2, StateV2> h1 = orderSagaV1toV2Handler();
            WorkflowMigrationHandler<StateV2, StateV2> h2 = orderSagaV2toV3Handler();
            WorkflowMigrationHandler<StateV2, StateV2> h3 = paymentFlowV1toV2Handler();

            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(h1, h2, h3), wfReg);

            assertThat(reg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(h1);
            assertThat(reg.find("default", "order-saga", 2L, "default", 3L))
                    .isPresent()
                    .containsSame(h2);
            assertThat(reg.find("default", "payment-flow", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(h3);
            assertThat(reg.all()).hasSize(3);
        }

        @Test
        @DisplayName("all() returns an unmodifiable view")
        void allIsUnmodifiable() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            DefaultWorkflowMigrationRegistry reg =
                    new DefaultWorkflowMigrationRegistry(Set.of(orderSagaV1toV2Handler()), wfReg);

            assertThatThrownBy(() -> reg.all().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Dynamic activation — find() does not depend on resident versions")
    class DeferredVersionValidation {

        @Test
        @DisplayName(
                "handler registered with only v1 in registry does not throw at construction; find returns it before and after v2")
        void handlerRegisteredBeforeTargetVersionDoesNotThrowAtConstruction() {
            // Only v1 registered at Dagger-graph-build time; v2 will be loaded later
            DefaultWorkflowRegistry wfReg = new DefaultWorkflowRegistry();
            wfReg.register(orderSagaV1Def());

            WorkflowMigrationHandler<StateV2, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version resolution is deferred to find()
            DefaultWorkflowMigrationRegistry migrationReg =
                    new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns the handler although v2 is not yet registered
            assertThat(migrationReg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(handler);

            // Now register v2 dynamically (simulating WorkflowDefinitionService.activate())
            wfReg.register(orderSagaV2Def());

            // find() still returns the handler once both versions are registered
            assertThat(migrationReg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(handler);
        }

        @Test
        @DisplayName("state-type mismatch with both versions registered does not fail find()")
        void stateTypeMismatchAtFindTimeDoesNotFail() {
            // Both versions registered; but the handler declares wrong source state type
            DefaultWorkflowRegistry wfReg = new DefaultWorkflowRegistry();
            wfReg.register(orderSagaV1Def()); // stateType = StateV2
            wfReg.register(orderSagaV2Def()); // stateType = StateV2

            // Handler claims StateV1 as source type, but plan v1 has StateV2
            WorkflowMigrationHandler<StateV1, StateV2> badHandler = new WorkflowMigrationHandler<>() {
                @Override
                public String definitionId() {
                    return "order-saga";
                }

                @Override
                public long fromDefinitionVersion() {
                    return 1L;
                }

                @Override
                public long targetDefinitionVersion() {
                    return 2L;
                }

                @Override
                public Class<StateV1> sourceStateType() {
                    return StateV1.class; // mismatch: plan v1 has StateV2
                }

                @Override
                public Class<StateV2> targetStateType() {
                    return StateV2.class;
                }

                @Override
                public MigrationResult<StateV2> migrate(StateV1 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(new StateV2(sourceState.id(), 0));
                }
            };

            // Construction must NOT throw
            DefaultWorkflowMigrationRegistry migrationReg =
                    new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() returns the handler; the state-type check belongs to the migration execution
            assertThat(migrationReg.find("default", "order-saga", 1L, "default", 2L))
                    .isPresent()
                    .containsSame(badHandler);
        }
    }

    @Nested
    @DisplayName("Scoped handler keys")
    class ScopedHandlerKeys {

        private static WorkflowDefinition<StateV2, OrderSagaContract> definition(long version) {
            return new WorkflowDefinition<>() {
                @Override
                public Class<OrderSagaContract> contract() {
                    return OrderSagaContract.class;
                }

                @Override
                public Class<StateV2> stateType() {
                    return StateV2.class;
                }

                @Override
                public String definitionId() {
                    return "m-x";
                }

                @Override
                public long definitionVersion() {
                    return version;
                }

                @Override
                public void define(WorkflowBuilder<StateV2> wf) {
                    wf.init(StartCmd.class, cmd -> new StateV2(cmd.id(), 0))
                            .initialStep("done")
                            .complete("done");
                }
            };
        }

        private static WorkflowMigrationHandler<StateV2, StateV2> handler(
                String sourceScope, long from, String targetScope, long to) {
            return new WorkflowMigrationHandler<>() {
                @Override
                public String sourceScope() {
                    return sourceScope;
                }

                @Override
                public String definitionId() {
                    return "m-x";
                }

                @Override
                public long fromDefinitionVersion() {
                    return from;
                }

                @Override
                public String targetScope() {
                    return targetScope;
                }

                @Override
                public long targetDefinitionVersion() {
                    return to;
                }

                @Override
                public Class<StateV2> sourceStateType() {
                    return StateV2.class;
                }

                @Override
                public Class<StateV2> targetStateType() {
                    return StateV2.class;
                }

                @Override
                public MigrationResult<StateV2> migrate(StateV2 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(sourceState);
                }
            };
        }

        @Test
        @DisplayName("handlers are keyed by scopes and ordered only within one scope")
        void handlersAreKeyedByScopesAndOrderedOnlyWithinOneScope() {
            // Given: handlers (default, m-x, 1) -> (default, 2) and (default, m-x, 2) -> (acme, 1), and a
            // third (acme, m-x, 2) -> (acme, 1); the default scope's definitions registered
            DefaultWorkflowRegistry wfReg = new DefaultWorkflowRegistry();
            wfReg.register(definition(1L));
            wfReg.register(definition(2L));
            WorkflowMigrationHandler<StateV2, StateV2> withinDefault = handler("default", 1L, "default", 2L);
            WorkflowMigrationHandler<StateV2, StateV2> intoAcme = handler("default", 2L, "acme", 1L);
            WorkflowMigrationHandler<StateV2, StateV2> downgradeInAcme = handler("acme", 2L, "acme", 1L);

            // When: the registry is built and find is called for each key and for (default, m-x, 1, acme, 1)
            DefaultWorkflowMigrationRegistry reg =
                    new DefaultWorkflowMigrationRegistry(Set.of(withinDefault, intoAcme), wfReg);
            Optional<WorkflowMigrationHandler<?, ?>> first = reg.find("default", "m-x", 1L, "default", 2L);
            Optional<WorkflowMigrationHandler<?, ?>> second = reg.find("default", "m-x", 2L, "acme", 1L);
            Optional<WorkflowMigrationHandler<?, ?>> unkeyed = reg.find("default", "m-x", 1L, "acme", 1L);

            // Then: the first two index
            assertThat(first).containsSame(withinDefault);
            assertThat(second).containsSame(intoAcme);

            // and: the key with no handler is empty
            assertThat(unkeyed).isEmpty();

            // and: a downgrade within one scope fails construction
            assertThatThrownBy(() -> new DefaultWorkflowMigrationRegistry(Set.of(downgradeInAcme), wfReg))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("m-x");
        }
    }
}
