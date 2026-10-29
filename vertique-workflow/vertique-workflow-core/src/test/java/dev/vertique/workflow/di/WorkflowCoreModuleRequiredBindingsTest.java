// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.di;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsOptionalOf;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies which bindings {@link WorkflowCoreModule} leaves optional and which it requires. */
class WorkflowCoreModuleRequiredBindingsTest {

    private static List<Method> optionalBindings() {
        return Arrays.stream(WorkflowCoreModule.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(BindsOptionalOf.class))
                .toList();
    }

    @Test
    @DisplayName("the definition store and the lifecycle are required bindings; the other optionals remain")
    void storeAndLifecycleAreNotOptionalBindings() {
        // Given the module read by reflection
        // When its optional bindings are read
        List<Method> optionals = optionalBindings();

        // Then neither the store nor the lifecycle is declared optional
        assertThat(optionals)
                .as("optional declarations of the definition store")
                .noneMatch(method -> method.getReturnType() == WorkflowDefinitionRegistryStore.class);
        assertThat(optionals)
                .as("optional declarations of the definition lifecycle")
                .noneMatch(method -> method.getReturnType() == WorkflowDefinitionLifecycle.class);

        // And the state comparison and the definitions configuration stay optional
        assertThat(optionals)
                .extracting(Method::getReturnType)
                .contains(WorkflowStateCompatibility.class, WorkflowDefinitionsConfig.class);
    }
}
