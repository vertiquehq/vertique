// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.compose;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup validator that asserts the application's Dagger graph is correctly composed for
 * workflow event publishing:
 *
 * <ol>
 *   <li>The app-supplied {@link WorkflowEventOutboxBinding} is non-null (effectively guaranteed by
 *       the record's compact constructor, which rejects null at construction time; documented here
 *       for completeness).</li>
 *   <li>The binding's {@link WorkflowEventOutboxBinding#destinationType()} has at least one
 *       registered {@link OutboxDestinationHandler} whose
 *       {@link OutboxDestinationHandler#destinationType()} matches. Without it, workflow
 *       {@code WORKFLOW_EVENT} intents are written to the outbox but the relay silently skips them
 *       because no handler is registered to deliver them.</li>
 * </ol>
 *
 * <h2>How validation runs</h2>
 *
 * <p>The validator is enforced through the recorder constructor:
 * {@link dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder} takes this
 * validator as a required dependency, so Dagger must construct the validator before the recorder.
 * Because the recorder participates in the {@code @WorkflowRecorders} multibinding that
 * {@code RecorderRouter} consumes inside {@code PgWorkflowEngine}, requesting the engine forces
 * the entire chain — including this validator — to be built. Validation runs at Dagger graph
 * construction time; if any check fails the constructor throws {@link IllegalStateException} with
 * a message identifying the chosen destination type and the registered handler types, aborting
 * startup.
 *
 * <p>Applications MAY additionally expose
 * {@code WorkflowEventsComposeValidator workflowEventsComposeValidator()} on their
 * {@code AppComponent} and call it during {@code MainVerticle.start()} for an even earlier
 * fail-fast (e.g., before any verticle is deployed). This is optional belt-and-suspenders, not a
 * requirement for validation to run.
 *
 * <p>This validator implements {@link ComposeValidator} so the framework can materialize it in
 * the {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase,
 * forcing its construction-time checks without any app code referencing it directly.
 */
@Singleton
public final class WorkflowEventsComposeValidator implements ComposeValidator {

    /**
     * Validates the application's Dagger graph for workflow event delivery.
     *
     * @param handlers the full set of registered {@link OutboxDestinationHandler} instances
     * @param binding  the app-supplied outbox destination binding for workflow events
     * @throws IllegalStateException if no registered handler's {@code destinationType()} matches
     *                               the binding's chosen destination type
     */
    @Inject
    public WorkflowEventsComposeValidator(Set<OutboxDestinationHandler> handlers, WorkflowEventOutboxBinding binding) {
        validateHandlerPresent(handlers, binding.destinationType());
    }

    /**
     * Asserts that at least one registered handler matches the chosen destination type.
     *
     * @param handlers        all registered {@link OutboxDestinationHandler} instances
     * @param destinationType the destination type declared in the app's binding
     * @throws IllegalStateException if no handler matches
     */
    private static void validateHandlerPresent(
            Set<OutboxDestinationHandler> handlers, DestinationType destinationType) {
        boolean hasMatch = handlers.stream().anyMatch(h -> h.destinationType().equals(destinationType));
        if (!hasMatch) {
            String registered = handlers.stream()
                    .map(h -> h.destinationType().id())
                    .sorted()
                    .collect(Collectors.joining(", ", "[", "]"));
            throw new IllegalStateException(
                    "vertique-workflow-events requires an OutboxDestinationHandler with destinationType="
                            + destinationType.id()
                            + " in the application's Dagger graph, but no such handler is registered. "
                            + "Registered destination types: "
                            + registered
                            + ". Add the appropriate adapter module (e.g., vertique-inbox-outbox-kafka for KAFKA, "
                            + "vertique-inbox-outbox-services for SERVICE) to your AppComponent. Without it, "
                            + "workflow WORKFLOW_EVENT intents are written to the outbox but the OutboxRelay "
                            + "silently skips them.");
        }
    }
}
