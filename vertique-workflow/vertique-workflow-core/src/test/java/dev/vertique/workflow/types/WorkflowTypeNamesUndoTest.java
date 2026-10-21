// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowTypeNameConflictException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the shared type-name index gives names back when a registration fails after indexing, keeps a name
 * that another registration or a catalog still holds, and indexes a definition's classes as one unit.
 */
class WorkflowTypeNamesUndoTest {

    // --- Fixture types ---

    record Start(String id) {}

    @WorkflowType("extra-start")
    record ExtraStart(String id) {}

    @WorkflowType("order")
    record Alpha(String id) {}

    @WorkflowType("order")
    record Beta(String id) {}

    @WorkflowType("fresh")
    record Fresh(String id) {}

    @WorkflowContract(value = "orders", definitionId = "ledger-one", definitionVersion = 1)
    interface LedgerOneContract {}

    @WorkflowContract(value = "orders", definitionId = "ledger-two", definitionVersion = 1)
    interface LedgerTwoContract {}

    @WorkflowContract(definitionId = "unnamed", definitionVersion = 1)
    interface UnnamedContract {}

    private static final RuntimeException REJECTED = new IllegalStateException("rejected by a validator");

    // --- A registration that fails after indexing ---

    @Test
    @DisplayName("a validator that rejects a definition releases its names so another class can claim them")
    void rejectedRegistrationReleasesItsNames() {
        // Given: a registry whose only validator rejects the definition "rejected-saga"
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(rejecting("rejected-saga")), names);

        // When: the rejected definition tries to register, naming a state class order and a contract orders
        assertThatThrownBy(() -> registry.register(
                        definition("rejected-saga", LedgerOneContract.class, Alpha.class, Start.class)))
                .isSameAs(REJECTED);

        // Then: none of its names or binary names is left in the index
        assertThat(names.find("order")).isEmpty();
        assertThat(names.find(Alpha.class.getName())).isEmpty();
        assertThat(names.findContract("orders")).isEmpty();
        assertThat(names.findContract(LedgerOneContract.class.getName())).isEmpty();

        // And: a different state class and contract can now claim the same names
        registry.register(definition("accepted-saga", LedgerTwoContract.class, Beta.class, Start.class));
        assertThat(names.find("order")).hasValue(Beta.class);
        assertThat(names.findContract("orders")).hasValue(LedgerTwoContract.class);
    }

    @Test
    @DisplayName("a duplicate registration keeps the names of the first and releases only the ones it added")
    void duplicateRegistrationKeepsTheFirstRegistrationsNames() {
        // Given: a registered definition x v1 over Alpha and LedgerOneContract
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(), names);
        registry.register(definition("x", LedgerOneContract.class, Alpha.class, Start.class));

        // When: x v1 registers again with the same classes and one more class only the duplicate carries
        assertThatThrownBy(() ->
                        registry.register(definition("x", LedgerOneContract.class, Alpha.class, ExtraStart.class)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("already registered");

        // Then: the names the first registration holds still resolve
        assertThat(names.find("order")).hasValue(Alpha.class);
        assertThat(names.find(Alpha.class.getName())).hasValue(Alpha.class);
        assertThat(names.findContract("orders")).hasValue(LedgerOneContract.class);

        // And: the name only the failed duplicate added is gone
        assertThat(names.find("extra-start")).isEmpty();
        assertThat(names.find(ExtraStart.class.getName())).isEmpty();

        // And: the first registration's names are still protected against another class
        assertThatThrownBy(() -> registry.register(definition("y", LedgerTwoContract.class, Beta.class, Start.class)))
                .isInstanceOf(WorkflowTypeNameConflictException.class);
    }

    @Test
    @DisplayName("a name from a contributed catalog survives the release of a definition that listed the same class")
    void catalogNameSurvivesTheReleaseOfADefinitionThatListedTheSameClass() {
        // Given: an index filled from a catalog that lists Alpha and LedgerOneContract, and a rejecting validator
        WorkflowTypeCatalog catalog = () -> List.of(
                new WorkflowTypeCatalogEntry(WorkflowTypeCatalogEntry.Kind.TYPE, "order", Alpha.class),
                new WorkflowTypeCatalogEntry(
                        WorkflowTypeCatalogEntry.Kind.CONTRACT, "orders", LedgerOneContract.class));
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames(List.of(catalog));
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(rejecting("rejected-saga")), names);

        // When: a definition over the same classes is rejected after indexing
        assertThatThrownBy(() -> registry.register(
                        definition("rejected-saga", LedgerOneContract.class, Alpha.class, Start.class)))
                .isSameAs(REJECTED);

        // Then: the catalog's names, logical and binary, still resolve
        assertThat(names.find("order")).hasValue(Alpha.class);
        assertThat(names.find(Alpha.class.getName())).hasValue(Alpha.class);
        assertThat(names.findContract("orders")).hasValue(LedgerOneContract.class);
        assertThat(names.findContract(LedgerOneContract.class.getName())).hasValue(LedgerOneContract.class);
    }

    // --- Indexing a definition's classes as one unit ---

    @Test
    @DisplayName("a conflict midway through one add leaves nothing of that add behind")
    void conflictMidwayThroughOneAddLeavesNothingBehind() {
        // Given: an index that already holds Alpha under order
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        names.add(null, List.of(Alpha.class));

        // When: one add lists a contract, a free class and then Beta, whose name order is taken
        assertThatThrownBy(() -> names.add(LedgerOneContract.class, List.of(Fresh.class, Beta.class)))
                .isInstanceOf(WorkflowTypeNameConflictException.class)
                .hasMessageContaining("order");

        // Then: the contract and the free class listed before the conflict are not indexed
        assertThat(names.findContract("orders")).isEmpty();
        assertThat(names.findContract(LedgerOneContract.class.getName())).isEmpty();
        assertThat(names.find("fresh")).isEmpty();
        assertThat(names.find(Fresh.class.getName())).isEmpty();

        // And: the earlier holder of order is untouched
        assertThat(names.find("order")).hasValue(Alpha.class);
        assertThat(names.find(Beta.class.getName())).isEmpty();
    }

    @Test
    @DisplayName("a definition whose state class conflicts leaves its contract name unclaimed")
    void conflictingDefinitionLeavesItsContractNameUnclaimed() {
        // Given: a registry that holds a definition over Alpha
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(null, Set.of(), names);
        registry.register(definition("first-saga", LedgerOneContract.class, Alpha.class, Start.class));

        // When: a definition with a free contract and a state class that conflicts with Alpha registers
        assertThatThrownBy(() ->
                        registry.register(definition("second-saga", UnnamedContract.class, Beta.class, Start.class)))
                .isInstanceOf(WorkflowTypeNameConflictException.class);

        // Then: the failed definition's contract is not indexed, and the first definition still is
        assertThat(names.findContract(UnnamedContract.class.getName())).isEmpty();
        assertThat(names.findContract("orders")).hasValue(LedgerOneContract.class);
        assertThat(names.find(Beta.class.getName())).isEmpty();
    }

    // --- Helpers ---

    /** A validator that rejects the definition with the given id and accepts every other one. */
    private static WorkflowDefinitionValidator rejecting(String definitionId) {
        return candidate -> {
            if (candidate.plan().definitionId().equals(definitionId)) {
                throw REJECTED;
            }
        };
    }

    /** A definition with the given id, contract, state and start payload classes that completes at once. */
    private static <S, C> WorkflowDefinition<S, C> definition(
            String id, Class<C> contract, Class<S> state, Class<?> startPayload) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public Class<S> stateType() {
                return state;
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
            @SuppressWarnings({"rawtypes", "unchecked"})
            public void define(WorkflowBuilder<S> wf) {
                ((WorkflowBuilder) wf)
                        .init(startPayload, start -> null)
                        .initialStep("done")
                        .complete("done");
            }
        };
    }
}
