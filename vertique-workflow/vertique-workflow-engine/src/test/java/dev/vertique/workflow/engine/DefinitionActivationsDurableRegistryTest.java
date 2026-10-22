// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the view reports whether a durable registry is bound. */
class DefinitionActivationsDurableRegistryTest {

    @Test
    @DisplayName("a view with a registry store reports a durable registry")
    void storeBound() {
        assertThat(new DefinitionActivations(Optional.of(mock(WorkflowDefinitionRegistryStore.class)))
                        .hasDurableRegistry())
                .isTrue();
    }

    @Test
    @DisplayName("a view without a registry store reports no durable registry")
    void noStore() {
        assertThat(new DefinitionActivations().hasDurableRegistry()).isFalse();
        assertThat(new DefinitionActivations(Optional.empty()).hasDurableRegistry())
                .isFalse();
    }
}
