// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.compose;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkflowEventsComposeValidator}.
 *
 * <p>Verifies the handler-presence check: constructor succeeds when the binding's destination type
 * has a matching registered {@link OutboxDestinationHandler}, and throws
 * {@link IllegalStateException} with a descriptive message when no handler matches.
 */
class WorkflowEventsComposeValidatorTest {

    private static final WorkflowEventOutboxBinding KAFKA_BINDING =
            new WorkflowEventOutboxBinding(DestinationType.KAFKA, "workflow-events-topic");

    // --- Happy paths ---

    @Test
    @DisplayName("matching KAFKA handler registered — constructor succeeds")
    void constructor_matchingKafkaHandler_succeeds() {
        OutboxDestinationHandler kafkaHandler = handlerFor(DestinationType.KAFKA);

        assertThatCode(() -> new WorkflowEventsComposeValidator(Set.of(kafkaHandler), KAFKA_BINDING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("multiple handlers registered, one matches — constructor succeeds")
    void constructor_multipleHandlersOneMatches_succeeds() {
        OutboxDestinationHandler kafkaHandler = handlerFor(DestinationType.KAFKA);
        OutboxDestinationHandler serviceHandler = handlerFor(DestinationType.SERVICE);

        assertThatCode(() -> new WorkflowEventsComposeValidator(Set.of(kafkaHandler, serviceHandler), KAFKA_BINDING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SERVICE binding with matching SERVICE handler — constructor succeeds")
    void constructor_serviceBindingWithServiceHandler_succeeds() {
        WorkflowEventOutboxBinding serviceBinding =
                new WorkflowEventOutboxBinding(DestinationType.SERVICE, "workflow/events/handle");
        OutboxDestinationHandler serviceHandler = handlerFor(DestinationType.SERVICE);

        assertThatCode(() -> new WorkflowEventsComposeValidator(Set.of(serviceHandler), serviceBinding))
                .doesNotThrowAnyException();
    }

    // --- Failure paths ---

    @Test
    @DisplayName("empty handler set — throws IllegalStateException naming the missing type")
    void constructor_emptyHandlerSet_throws() {
        assertThatThrownBy(() -> new WorkflowEventsComposeValidator(Set.of(), KAFKA_BINDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAFKA")
                .hasMessageContaining("vertique-workflow-events requires an OutboxDestinationHandler");
    }

    @Test
    @DisplayName("only DELAYED_JOB handler for a KAFKA binding — throws with descriptive message")
    void constructor_onlyDelayedJobHandler_throws() {
        OutboxDestinationHandler delayedJobHandler = handlerFor(DestinationType.DELAYED_JOB);

        assertThatThrownBy(() -> new WorkflowEventsComposeValidator(Set.of(delayedJobHandler), KAFKA_BINDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destinationType=KAFKA")
                .hasMessageContaining("DELAYED_JOB")
                .hasMessageContaining("vertique-inbox-outbox-kafka");
    }

    @Test
    @DisplayName("handler with wrong type — error message lists all registered types")
    void constructor_wrongHandlerType_errorListsRegisteredTypes() {
        OutboxDestinationHandler serviceHandler = handlerFor(DestinationType.SERVICE);

        assertThatThrownBy(() -> new WorkflowEventsComposeValidator(Set.of(serviceHandler), KAFKA_BINDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destinationType=KAFKA")
                .hasMessageContaining("SERVICE");
    }

    // --- Helpers ---

    /**
     * Creates a Mockito stub {@link OutboxDestinationHandler} returning the given type.
     *
     * @param type the destination type the stub should report
     * @return a mock handler
     */
    private static OutboxDestinationHandler handlerFor(DestinationType type) {
        OutboxDestinationHandler handler = mock(OutboxDestinationHandler.class);
        when(handler.destinationType()).thenReturn(type);
        return handler;
    }
}
