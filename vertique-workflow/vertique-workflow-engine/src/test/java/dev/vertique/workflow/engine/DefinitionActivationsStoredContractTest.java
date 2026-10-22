// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies where the contract stored for a definition comes from: the durable registry row, and nowhere else. */
class DefinitionActivationsStoredContractTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "order", 2);

    @Test
    @DisplayName("the stored contract is the contract name of the registry row of the key")
    void storedContractIsTheRowsContractName() {
        // Given a durable registry whose row for the key names a contract
        WorkflowDefinitionRegistryStore store = mock(WorkflowDefinitionRegistryStore.class);
        WorkflowDefinitionRecord row = mock(WorkflowDefinitionRecord.class);
        when(row.contractName()).thenReturn("orders");
        when(store.find(KEY)).thenReturn(Future.succeededFuture(Optional.of(row)));

        // When the view looks the contract up
        Optional<String> contract = new DefinitionActivations(Optional.of(store))
                .storedContract(KEY)
                .result();

        // Then it is the row's contract name
        assertThat(contract).contains("orders");
    }

    @Test
    @DisplayName("the stored contract is empty when the registry has no row for the key")
    void storedContractIsEmptyWithoutARow() {
        WorkflowDefinitionRegistryStore store = mock(WorkflowDefinitionRegistryStore.class);
        when(store.find(KEY)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertThat(new DefinitionActivations(Optional.of(store))
                        .storedContract(KEY)
                        .result())
                .isEmpty();
    }

    @Test
    @DisplayName("the stored contract is empty without a durable registry")
    void storedContractIsEmptyWithoutAStore() {
        assertThat(new DefinitionActivations().storedContract(KEY).result()).isEmpty();
    }
}
