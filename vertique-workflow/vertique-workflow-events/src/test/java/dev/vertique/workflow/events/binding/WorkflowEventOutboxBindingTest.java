// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.inboxoutbox.DestinationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowEventOutboxBinding}.
 *
 * <p>Verifies that the compact constructor rejects invalid inputs and that the record's
 * equality and accessor contracts hold for valid inputs.
 */
class WorkflowEventOutboxBindingTest {

    // --- Compact constructor validation ---

    @Test
    @DisplayName("null destinationType — throws NullPointerException")
    void constructor_nullDestinationType_throws() {
        assertThatThrownBy(() -> new WorkflowEventOutboxBinding(null, "workflow-events"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destinationType");
    }

    @Test
    @DisplayName("null destination — throws NullPointerException")
    void constructor_nullDestination_throws() {
        assertThatThrownBy(() -> new WorkflowEventOutboxBinding(DestinationType.KAFKA, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destination");
    }

    @Test
    @DisplayName("blank destination — throws IllegalArgumentException")
    void constructor_blankDestination_throws() {
        assertThatThrownBy(() -> new WorkflowEventOutboxBinding(DestinationType.KAFKA, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must not be blank");
    }

    @Test
    @DisplayName("empty string destination — throws IllegalArgumentException")
    void constructor_emptyDestination_throws() {
        assertThatThrownBy(() -> new WorkflowEventOutboxBinding(DestinationType.KAFKA, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must not be blank");
    }

    // --- Valid construction ---

    @Test
    @DisplayName("valid KAFKA binding — accessors return correct values")
    void constructor_validKafkaBinding_accessorsCorrect() {
        WorkflowEventOutboxBinding binding =
                new WorkflowEventOutboxBinding(DestinationType.KAFKA, "workflow-events-topic");

        assertThat(binding.destinationType()).isEqualTo(DestinationType.KAFKA);
        assertThat(binding.destination()).isEqualTo("workflow-events-topic");
    }

    @Test
    @DisplayName("valid SERVICE binding — accessors return correct values")
    void constructor_validServiceBinding_accessorsCorrect() {
        WorkflowEventOutboxBinding binding =
                new WorkflowEventOutboxBinding(DestinationType.SERVICE, "workflow/events/handle");

        assertThat(binding.destinationType()).isEqualTo(DestinationType.SERVICE);
        assertThat(binding.destination()).isEqualTo("workflow/events/handle");
    }

    // --- Equality / record properties ---

    @Test
    @DisplayName("two bindings with the same fields are equal")
    void equality_sameFields_equal() {
        WorkflowEventOutboxBinding a = new WorkflowEventOutboxBinding(DestinationType.KAFKA, "topic-a");
        WorkflowEventOutboxBinding b = new WorkflowEventOutboxBinding(DestinationType.KAFKA, "topic-a");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("bindings with different destinations are not equal")
    void equality_differentDestination_notEqual() {
        WorkflowEventOutboxBinding a = new WorkflowEventOutboxBinding(DestinationType.KAFKA, "topic-a");
        WorkflowEventOutboxBinding b = new WorkflowEventOutboxBinding(DestinationType.KAFKA, "topic-b");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("bindings with different destination types are not equal")
    void equality_differentDestinationType_notEqual() {
        WorkflowEventOutboxBinding a = new WorkflowEventOutboxBinding(DestinationType.KAFKA, "events");
        WorkflowEventOutboxBinding b = new WorkflowEventOutboxBinding(DestinationType.SERVICE, "events");

        assertThat(a).isNotEqualTo(b);
    }
}
