// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowMigrationStateTypeMismatchException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import java.util.Optional;
import java.util.Set;
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
 *   <li>Source version unregistered at construction time → constructor succeeds (deferred);
 *       {@code find()} returns empty.</li>
 *   <li>Target version unregistered at construction time → constructor succeeds (deferred);
 *       {@code find()} returns empty.</li>
 *   <li>Handler state-type mismatch against the registered plan → {@code find()} throws
 *       {@link WorkflowDefinitionException} (deferred check).</li>
 *   <li>{@code fromVersion >= targetVersion} → constructor throws {@link WorkflowDefinitionException}.</li>
 *   <li>Multiple non-overlapping handlers → all registered successfully.</li>
 *   <li>Handler registered with only v1 → construction succeeds; {@code find()} returns empty
 *       until v2 is dynamically activated; returns handler after activation.</li>
 * </ul>
 */
class WorkflowMigrationRegistryTest {

    // --- Fixture state types ---

    record StateV1(String id) {}

    record StateV2(String id, int count) {}

    record StartCmd(String id) {}

    // --- Contract interfaces ---

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 1)
    interface OrderSagaContractV1 {}

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 2)
    interface OrderSagaContractV2 {}

    @WorkflowContract(definitionId = "order-saga", definitionVersion = 3)
    interface OrderSagaContractV3 {}

    @WorkflowContract(definitionId = "payment-flow", definitionVersion = 1)
    interface PaymentFlowContractV1 {}

    @WorkflowContract(definitionId = "payment-flow", definitionVersion = 2)
    interface PaymentFlowContractV2 {}

    // --- Workflow definitions ---

    static WorkflowDefinition<StateV1, OrderSagaContractV1> orderSagaV1Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContractV1> contract() {
                return OrderSagaContractV1.class;
            }

            @Override
            public Class<StateV1> stateType() {
                return StateV1.class;
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
            public void define(WorkflowBuilder<StateV1> wf) {
                wf.init(StartCmd.class, cmd -> new StateV1(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, OrderSagaContractV2> orderSagaV2Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContractV2> contract() {
                return OrderSagaContractV2.class;
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

    static WorkflowDefinition<StateV2, OrderSagaContractV3> orderSagaV3Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<OrderSagaContractV3> contract() {
                return OrderSagaContractV3.class;
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

    static WorkflowDefinition<StateV1, PaymentFlowContractV1> paymentFlowV1Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<PaymentFlowContractV1> contract() {
                return PaymentFlowContractV1.class;
            }

            @Override
            public Class<StateV1> stateType() {
                return StateV1.class;
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
            public void define(WorkflowBuilder<StateV1> wf) {
                wf.init(StartCmd.class, cmd -> new StateV1(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    static WorkflowDefinition<StateV2, PaymentFlowContractV2> paymentFlowV2Def() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<PaymentFlowContractV2> contract() {
                return PaymentFlowContractV2.class;
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

    static WorkflowMigrationHandler<StateV1, StateV2> orderSagaV1toV2Handler() {
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
            public Class<StateV1> sourceStateType() {
                return StateV1.class;
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

    static WorkflowMigrationHandler<StateV1, StateV2> paymentFlowV1toV2Handler() {
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
            public Class<StateV1> sourceStateType() {
                return StateV1.class;
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
            assertThat(reg.find("order-saga", 1L, 2L)).isEmpty();
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
            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            Optional<WorkflowMigrationHandler<?, ?>> found = reg.find("order-saga", 1L, 2L);
            assertThat(found).isPresent().containsSame(handler);
        }

        @Test
        @DisplayName("find(...) returns Optional.empty() for non-matching key")
        void findReturnsEmptyForMismatch() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            assertThat(reg.find("order-saga", 1L, 3L)).isEmpty();
            assertThat(reg.find("other-def", 1L, 2L)).isEmpty();
        }

        @Test
        @DisplayName("all() returns a collection with the single handler")
        void allReturnsSingleElement() {
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());
            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();
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
            WorkflowMigrationHandler<StateV1, StateV2> h1 = orderSagaV1toV2Handler();
            WorkflowMigrationHandler<StateV1, StateV2> h2 = orderSagaV1toV2Handler();

            assertThatThrownBy(() -> new DefaultWorkflowMigrationRegistry(Set.of(h1, h2), wfReg))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("1")
                    .hasMessageContaining("2");
        }
    }

    @Nested
    @DisplayName("Unregistered version validation — deferred to find()")
    class UnregisteredVersionValidation {

        @Test
        @DisplayName("source version not in registry → construction succeeds; find() returns empty")
        void sourceVersionUnregisteredDoesNotThrowAtConstructionButFindReturnsEmpty() {
            // Only v2 registered; handler migrates from v1 → v2; v1 is absent
            WorkflowRegistry wfReg = registryWith(orderSagaV2Def());
            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version check is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns empty because source version v1 is not registered
            assertThat(reg.find("order-saga", 1L, 2L)).isEmpty();
        }

        @Test
        @DisplayName("target version not in registry → construction succeeds; find() returns empty")
        void targetVersionUnregisteredDoesNotThrowAtConstructionButFindReturnsEmpty() {
            // Only v1 registered; handler migrates from v1 → v2; v2 is absent
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def());
            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version check is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns empty because target version v2 is not registered
            assertThat(reg.find("order-saga", 1L, 2L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("State-type mismatch validation — deferred to find()")
    class StateTypeMismatchValidation {

        @Test
        @DisplayName(
                "handler sourceStateType differs from plan stateType → find() throws WorkflowMigrationStateTypeMismatchException")
        void sourceStateTypeMismatchThrowsAtFindTime() {
            // orderSagaV1Def has stateType=StateV1, but this handler claims StateV2 as source type
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV2, StateV2> badHandler = new WorkflowMigrationHandler<>() {
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
                    return StateV2.class; // mismatch: plan v1 has StateV1
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

            // Construction must NOT throw — type checking is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() must throw typed WorkflowMigrationStateTypeMismatchException
            assertThatThrownBy(() -> reg.find("order-saga", 1L, 2L))
                    .isInstanceOf(WorkflowMigrationStateTypeMismatchException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("sourceStateType")
                    .satisfies(ex -> {
                        WorkflowMigrationStateTypeMismatchException typed =
                                (WorkflowMigrationStateTypeMismatchException) ex;
                        assertThat(typed.definitionId()).isEqualTo("order-saga");
                        assertThat(typed.fromVersion()).isEqualTo(1L);
                        assertThat(typed.targetVersion()).isEqualTo(2L);
                        assertThat(typed.expectedStateType()).isEqualTo(StateV2.class);
                        assertThat(typed.actualStateType()).isEqualTo(StateV1.class);
                    });
        }

        @Test
        @DisplayName(
                "handler targetStateType differs from plan stateType → find() throws WorkflowMigrationStateTypeMismatchException")
        void targetStateTypeMismatchThrowsAtFindTime() {
            // orderSagaV2Def has stateType=StateV2, but this handler claims StateV1 as target type
            WorkflowRegistry wfReg = registryWith(orderSagaV1Def(), orderSagaV2Def());

            WorkflowMigrationHandler<StateV1, StateV1> badHandler = new WorkflowMigrationHandler<>() {
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
                    return StateV1.class;
                }

                @Override
                public Class<StateV1> targetStateType() {
                    return StateV1.class; // mismatch: plan v2 has StateV2
                }

                @Override
                public MigrationResult<StateV1> migrate(StateV1 sourceState, MigrationContext ctx) {
                    return MigrationResult.anchorAtInitial(sourceState);
                }
            };

            // Construction must NOT throw — type checking is deferred
            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() must throw typed WorkflowMigrationStateTypeMismatchException
            assertThatThrownBy(() -> reg.find("order-saga", 1L, 2L))
                    .isInstanceOf(WorkflowMigrationStateTypeMismatchException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("targetStateType")
                    .satisfies(ex -> {
                        WorkflowMigrationStateTypeMismatchException typed =
                                (WorkflowMigrationStateTypeMismatchException) ex;
                        assertThat(typed.definitionId()).isEqualTo("order-saga");
                        assertThat(typed.fromVersion()).isEqualTo(1L);
                        assertThat(typed.targetVersion()).isEqualTo(2L);
                        assertThat(typed.expectedStateType()).isEqualTo(StateV1.class);
                        assertThat(typed.actualStateType()).isEqualTo(StateV2.class);
                    });
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

            WorkflowMigrationHandler<StateV1, StateV2> h1 = orderSagaV1toV2Handler();
            WorkflowMigrationHandler<StateV2, StateV2> h2 = orderSagaV2toV3Handler();
            WorkflowMigrationHandler<StateV1, StateV2> h3 = paymentFlowV1toV2Handler();

            DefaultWorkflowMigrationRegistry reg = new DefaultWorkflowMigrationRegistry(Set.of(h1, h2, h3), wfReg);

            assertThat(reg.find("order-saga", 1L, 2L)).isPresent().containsSame(h1);
            assertThat(reg.find("order-saga", 2L, 3L)).isPresent().containsSame(h2);
            assertThat(reg.find("payment-flow", 1L, 2L)).isPresent().containsSame(h3);
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
    @DisplayName("Deferred version validation — dynamic activation (C1)")
    class DeferredVersionValidation {

        @Test
        @DisplayName(
                "handler registered with only v1 in registry does not throw at construction; find returns empty for v1→v2")
        void handlerRegisteredBeforeTargetVersionDoesNotThrowAtConstruction() {
            // Only v1 registered at Dagger-graph-build time; v2 will be loaded later
            DefaultWorkflowRegistry wfReg = new DefaultWorkflowRegistry();
            wfReg.register(orderSagaV1Def());

            WorkflowMigrationHandler<StateV1, StateV2> handler = orderSagaV1toV2Handler();

            // Construction must not throw — version resolution is deferred to find()
            DefaultWorkflowMigrationRegistry migrationReg =
                    new DefaultWorkflowMigrationRegistry(Set.of(handler), wfReg);

            // find() returns empty because v2 is not yet registered
            assertThat(migrationReg.find("order-saga", 1L, 2L)).isEmpty();

            // Now register v2 dynamically (simulating WorkflowDefinitionService.activate())
            wfReg.register(orderSagaV2Def());

            // find() now returns the handler because both versions are registered
            assertThat(migrationReg.find("order-saga", 1L, 2L)).isPresent().containsSame(handler);
        }

        @Test
        @DisplayName("state-type mismatch surfaced at find() time as WorkflowMigrationStateTypeMismatchException")
        void stateTypeMismatchAtFindTimeThrows() {
            // Both versions registered; but the handler declares wrong source state type
            DefaultWorkflowRegistry wfReg = new DefaultWorkflowRegistry();
            wfReg.register(orderSagaV1Def()); // stateType = StateV1
            wfReg.register(orderSagaV2Def()); // stateType = StateV2

            // Handler claims StateV2 as source type, but plan v1 has StateV1
            WorkflowMigrationHandler<StateV2, StateV2> badHandler = new WorkflowMigrationHandler<>() {
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
                    return StateV2.class; // mismatch: plan v1 has StateV1
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

            // Construction must NOT throw — type checking is deferred to find()
            DefaultWorkflowMigrationRegistry migrationReg =
                    new DefaultWorkflowMigrationRegistry(Set.of(badHandler), wfReg);

            // find() must throw typed WorkflowMigrationStateTypeMismatchException
            assertThatThrownBy(() -> migrationReg.find("order-saga", 1L, 2L))
                    .isInstanceOf(WorkflowMigrationStateTypeMismatchException.class)
                    .hasMessageContaining("order-saga")
                    .hasMessageContaining("sourceStateType");
        }
    }
}
