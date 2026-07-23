// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.di;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Module;
import org.junit.jupiter.api.Test;

/**
 * Slice A sentinel: verifies the new {@code workflow-definition} module is on the classpath,
 * its base Dagger module is reachable, and the package-info compiles. Real Dagger wiring is
 * added in later slices alongside the bindings being declared.
 */
class WorkflowDefinitionModuleSmokeTest {

    @Test
    void moduleClassIsAnnotatedWithDaggerModule() {
        assertThat(WorkflowDefinitionModule.class.isAnnotationPresent(Module.class))
                .as("WorkflowDefinitionModule must carry @dagger.Module so apps can install it")
                .isTrue();
    }
}
