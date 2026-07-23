// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.recorder;

import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.workflow.events.WorkflowEventEnvelope;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * {@link WorkflowSideEffectRecorder} for {@link IntentKind#WORKFLOW_EVENT} intents.
 *
 * <p>When the workflow engine emits a lifecycle event, it creates a
 * {@link WorkflowSideEffectIntent} with {@code kind=WORKFLOW_EVENT} and a
 * {@link WorkflowEventIntent} payload, then delegates to this recorder via the
 * {@code RecorderRouter}. This recorder:
 * <ol>
 *   <li>Performs defensive kind and payload-type checks; returns a failed {@link Future} on
 *       violation without touching the outbox.</li>
 *   <li>Maps the {@link WorkflowEventIntent} to a {@link WorkflowEventEnvelope} at
 *       {@code schemaVersion=1}. The {@code eventType} field is stored as a plain {@link String}
 *       (populated from {@link dev.vertique.workflow.events.WorkflowEventType#name()}), not the
 *       enum constant, so consumers that receive a new event type string they do not recognise can
 *       skip it gracefully rather than failing deserialization.</li>
 *   <li>Writes an {@link OutboxEntry} inside the caller's transaction via
 *       {@link OutboxService#publish}. The relay delivers the entry after commit, guaranteeing
 *       at-least-once delivery to the configured destination.</li>
 * </ol>
 *
 * <h2>Wire contract stability</h2>
 * <p>{@code schemaVersion=1} is the stable cycle-4 contract. The version is incremented only
 * when the envelope's field set changes in a breaking way. Additive field additions (new nullable
 * fields) are non-breaking and do NOT require a version bump; consumers must already tolerate
 * unknown fields via {@code @JsonIgnoreProperties(ignoreUnknown=true)}.
 *
 * <h2>Forced-construction pattern</h2>
 * <p>The constructor takes {@link WorkflowEventsComposeValidator} as a required (otherwise unused)
 * parameter so that the validator's startup checks run whenever this recorder is instantiated.
 * Because the recorder participates in the {@code @WorkflowRecorders} multibinding consumed by
 * {@code RecorderRouter} → {@code PgWorkflowEngine}, Dagger constructs the validator before
 * the recorder, making validation happen at graph-construction time rather than as an opt-in
 * step at application boot. Mirrors the pattern established by
 * {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder} in cycle 1.
 */
@Singleton
public final class WorkflowEventSideEffectRecorder implements WorkflowSideEffectRecorder<SqlClient> {

    private final OutboxService outboxService;
    private final WorkflowEventOutboxBinding binding;

    /**
     * Creates a new recorder.
     *
     * <p>Takes {@link WorkflowEventsComposeValidator} as a required constructor parameter purely
     * for its construction side-effect: when Dagger instantiates this recorder (which happens
     * eagerly because it participates in the {@code @WorkflowRecorders}
     * {@code Set<WorkflowSideEffectRecorder<SqlClient>>} multibinding consumed by
     * {@code RecorderRouter} → {@code PgWorkflowEngine}), it must first instantiate the validator,
     * which performs the destination-handler-presence check. Apps can no longer silently bypass
     * that check by forgetting to expose an explicit accessor on their {@code AppComponent}; the
     * recorder cannot exist without the validator having run.
     *
     * @param outboxService    outbox service used to persist the outbox entry within the caller's
     *                         transaction
     * @param binding          the app-supplied outbox destination binding; declares where
     *                         {@code WORKFLOW_EVENT} intents are delivered
     * @param composeValidator the startup compose validator; participates as a required dependency
     *                         to ensure it runs before any workflow event can be recorded
     */
    @Inject
    public WorkflowEventSideEffectRecorder(
            OutboxService outboxService,
            WorkflowEventOutboxBinding binding,
            WorkflowEventsComposeValidator composeValidator) {
        this.outboxService = outboxService;
        this.binding = binding;
        // composeValidator is intentionally unused after construction; injection here forces the
        // validator's checks to run during Dagger graph instantiation, not opt-in at app boot.
    }

    /**
     * Returns {@link IntentKind#WORKFLOW_EVENT}.
     *
     * @return the intent kind handled by this recorder
     */
    @Override
    public IntentKind kind() {
        return IntentKind.WORKFLOW_EVENT;
    }

    /**
     * Records a {@link WorkflowSideEffectIntent} as a transactional outbox entry.
     *
     * <p>The method maps the intent's {@link WorkflowEventIntent} payload to a
     * {@link WorkflowEventEnvelope} and writes it to the outbox within {@code tx}. Any validation
     * failure (wrong kind or wrong payload type) causes the returned {@link Future} to fail
     * immediately without touching the outbox, rolling back the caller's transaction.
     *
     * @param intent the side-effect intent to record; must have {@code kind=WORKFLOW_EVENT} and a
     *               {@link WorkflowEventIntent} payload
     * @param tx     the open database transaction to use for the outbox insert
     * @return a {@link Future} that completes with {@link RecorderResult#empty()} on success, or
     *     fails with an {@link IllegalStateException} if the intent kind or payload type is wrong,
     *     or with any exception propagated from {@link OutboxService#publish}
     */
    @Override
    public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
        // --- Defensive kind check ---
        if (intent.kind() != IntentKind.WORKFLOW_EVENT) {
            return Future.failedFuture(
                    new IllegalStateException("WorkflowEventSideEffectRecorder received intent with kind "
                            + intent.kind() + "; expected WORKFLOW_EVENT"));
        }

        // --- Defensive payload type check ---
        if (!(intent.payload() instanceof WorkflowEventIntent eventIntent)) {
            return Future.failedFuture(new IllegalStateException(
                    "WorkflowEventSideEffectRecorder requires a WorkflowEventIntent payload; got "
                            + (intent.payload() == null
                                    ? "null"
                                    : intent.payload().getClass().getName())));
        }

        // --- Envelope construction ---
        WorkflowEventEnvelope envelope = WorkflowEventEnvelope.builder()
                .schemaVersion(1)
                .eventType(eventIntent.eventType().name())
                .workflowId(eventIntent.workflowId().value())
                .definitionId(eventIntent.definitionId())
                .definitionVersion(eventIntent.definitionVersion())
                .subjectRef(eventIntent.subjectRef())
                .businessKey(eventIntent.businessKey())
                .occurredAt(eventIntent.occurredAt())
                .sequence(eventIntent.sequence())
                .taskId(eventIntent.taskId())
                .stepId(eventIntent.stepId())
                .attributes(eventIntent.attributes())
                .correlationId(eventIntent.correlationId())
                .causationId(eventIntent.causationId())
                .build();

        // --- Outbox entry construction ---
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(binding.destinationType())
                .destination(binding.destination())
                .aggregateType("workflow")
                .aggregateId(eventIntent.workflowId().value().toString())
                .eventType(eventIntent.eventType().name())
                .payload(envelope)
                .headers(intent.headers() == null ? Map.of() : intent.headers())
                .build();

        return outboxService.publish(tx, entry).map(rowId -> RecorderResult.empty());
    }
}
