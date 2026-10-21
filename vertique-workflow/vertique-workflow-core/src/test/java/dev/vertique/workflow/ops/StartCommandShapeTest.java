// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Freezes the shape of {@link StartCommand}: one canonical constructor, nine components, in a fixed order. */
class StartCommandShapeTest {

    @Test
    @DisplayName("StartCommand has the nine components in order and no shorter constructor")
    void hasNineComponentsInOrderAndNoShorterConstructor() {
        // Given the record's declared components and constructors
        RecordComponent[] components = StartCommand.class.getRecordComponents();

        // Then the components are the nine, in order
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .containsExactly(
                        "definitionId",
                        "contractName",
                        "payload",
                        "idempotencyKey",
                        "businessKey",
                        "subjectRef",
                        "requestedDefinitionVersion",
                        "startScope",
                        "requestedTargetScope");

        // And the canonical constructor is the only public one
        assertThat(StartCommand.class.getConstructors()).hasSize(1);
        assertThat(StartCommand.class.getConstructors()[0].getParameterCount()).isEqualTo(9);
    }
}
