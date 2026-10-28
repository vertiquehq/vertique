// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.recorder;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link WorkflowSideEffectRecorder} for {@link IntentKind#SERVICE} intents.
 *
 * <p>When the workflow engine reaches a {@code ServiceDispatchNode}, it creates a
 * {@link WorkflowSideEffectIntent} with {@code kind=SERVICE} and calls the engine-side recorder
 * router, which delegates here.
 * This recorder:
 * <ol>
 *   <li>Resolves the target via {@link ServiceTargetResolver#resolve(String)}; returns a failed
 *       {@link Future} with {@link IllegalArgumentException} if the target id is unknown.</li>
 *   <li>Validates the target shape: not {@code @OneWay}, returns {@code Future<Void>}, has exactly
 *       one payload parameter. Any violation fails the {@link Future}, rolling back the workflow
 *       transaction before any state is mutated.</li>
 *   <li>Writes an {@link OutboxEntry} with {@link DestinationType#SERVICE} inside the caller's
 *       transaction via {@link OutboxService#publish}. The relay delivers the entry after commit,
 *       guaranteeing at-least-once delivery.</li>
 * </ol>
 *
 * <p>The recorder is intentionally side-effect-free beyond the outbox write: it never makes live
 * downstream calls, ensuring the workflow transaction stays bounded and roll-backable.
 */
@Singleton
public final class OutboxSideEffectRecorder implements WorkflowSideEffectRecorder<SqlClient> {

    private final OutboxService outboxService;
    private final ServiceTargetResolver targetResolver;

    /**
     * Creates a new recorder.
     *
     * @param outboxService outbox service used to persist the outbox entry within the caller's
     *     transaction
     * @param targetResolver resolver used to translate stable target ids into operation metadata
     */
    @Inject
    public OutboxSideEffectRecorder(OutboxService outboxService, ServiceTargetResolver targetResolver) {
        this.outboxService = outboxService;
        this.targetResolver = targetResolver;
    }

    /**
     * Returns {@link IntentKind#SERVICE}.
     *
     * @return the intent kind handled by this recorder
     */
    @Override
    public IntentKind kind() {
        return IntentKind.SERVICE;
    }

    /**
     * Records a {@link WorkflowSideEffectIntent} as a transactional outbox entry.
     *
     * <p>The method resolves the intent's {@link WorkflowSideEffectIntent#targetId() targetId},
     * validates the resolved operation shape, and writes an {@link OutboxEntry} inside {@code tx}.
     * Any validation failure or unknown target id causes the returned {@link Future} to fail,
     * rolling back the caller's transaction.
     *
     * <p>A {@code null} {@link WorkflowSideEffectIntent#headers() headers} map means no intent
     * headers. A {@code null} header key or value fails the returned {@link Future} with an
     * {@link IllegalArgumentException} that names the key (never the value); nothing is thrown
     * synchronously and nothing is published.
     *
     * <p>An intent a workflow segment writes arrives with the attribution headers
     * {@code x-workflow-actor-kind}, {@code x-workflow-actor-id}, {@code x-workflow-actor-reason},
     * {@code x-workflow-on-behalf-of} and {@code x-workflow-attribution} (absent values omitted), added by the engine
     * from the actor of the command the segment executes. They are copied to the outbox entry as they are. They are
     * unauthenticated and are not an identity; nothing here reads them.
     *
     * @param intent the side-effect intent to record; must have {@code kind=SERVICE}
     * @param tx     the open database transaction to use for the outbox insert
     * @return a {@link Future} that completes with {@link RecorderResult#empty()} on success, or
     *     fails with an {@link IllegalArgumentException} (unknown target, {@code null} header key
     *     or value) or
     *     {@link IllegalStateException} (invalid target shape)
     */
    @Override
    public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
        // --- Target resolution ---
        ResolvedServiceTarget target;
        try {
            target = targetResolver.resolve(intent.targetId());
        } catch (IllegalArgumentException ex) {
            return Future.failedFuture(ex);
        }

        // --- Target shape validation ---
        ServiceMethodMeta meta = target.meta();
        if (meta.oneWay()) {
            return Future.failedFuture(new IllegalStateException("Workflow service-dispatch target '"
                    + intent.targetId()
                    + "' is @OneWay; cycle 1 only supports request/reply targets that return Future<Void>."));
        }
        if (!isVoidReturn(meta)) {
            return Future.failedFuture(new IllegalStateException("Workflow service-dispatch target '"
                    + intent.targetId()
                    + "' must return Future<Void>; got "
                    + meta.returnType().getTypeName()));
        }
        long payloadParams = meta.params().stream()
                .filter(p -> p.source() == ParamSource.PAYLOAD)
                .count();
        if (payloadParams != 1) {
            return Future.failedFuture(new IllegalStateException("Workflow service-dispatch target '"
                    + intent.targetId()
                    + "' must have exactly one payload parameter; got "
                    + payloadParams));
        }

        // --- Header validation ---
        Map<String, String> intentHeaders = intent.headers() == null ? Map.of() : intent.headers();
        for (Map.Entry<String, String> header : intentHeaders.entrySet()) {
            if (header.getKey() == null) {
                return Future.failedFuture(new IllegalArgumentException("Workflow service-dispatch intent for target '"
                        + intent.targetId()
                        + "' has a null header key"));
            }
            if (header.getValue() == null) {
                return Future.failedFuture(new IllegalArgumentException("Workflow service-dispatch intent for target '"
                        + intent.targetId()
                        + "' has a null value for header '"
                        + header.getKey()
                        + "'"));
            }
        }

        // --- Outbox entry construction ---
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(target.targetId())
                .eventType(target.operation())
                .aggregateType("WorkflowInstance")
                .aggregateId(intent.correlation().workflowId().value().toString())
                .payload(intent.payload())
                .headers(mergeHeaders(intentHeaders, intent.correlation()))
                .build();

        return outboxService.publish(tx, entry).map(v -> RecorderResult.empty());
    }

    // --- Helpers ---

    /**
     * Returns {@code true} if the operation's return type is {@code Void} or {@code void}.
     *
     * @param meta the operation metadata to check
     * @return {@code true} when the return type is {@code Void} or {@code void}
     */
    private static boolean isVoidReturn(ServiceMethodMeta meta) {
        Class<?> rt = meta.returnType();
        return Void.class.equals(rt) || void.class.equals(rt);
    }

    /**
     * Merges the intent's explicit headers with workflow-correlation headers.
     *
     * <p>The correlation headers ({@code x-workflow-id}, {@code x-workflow-step-sequence},
     * {@code x-workflow-definition-id}, {@code x-workflow-definition-scope},
     * {@code x-workflow-scope}) are added on top of the base map from the intent.
     * Correlation headers take precedence if the intent supplies a key with the same name.
     *
     * @param base        headers from the intent (may be empty, must not be {@code null} and must not
     *                    hold a {@code null} key or value — {@code record} checks both first)
     * @param correlation workflow correlation providing the header values
     * @return an unmodifiable copy of the merged header map
     */
    private static Map<String, String> mergeHeaders(
            Map<String, String> base, WorkflowSideEffectIntent.Correlation correlation) {
        Map<String, String> merged = new HashMap<>(base);
        merged.put("x-workflow-id", correlation.workflowId().value().toString());
        merged.put("x-workflow-step-sequence", Long.toString(correlation.sequence()));
        merged.put("x-workflow-definition-id", correlation.definitionId());
        merged.put("x-workflow-definition-scope", correlation.definitionScopeKey());
        merged.put("x-workflow-scope", correlation.scopeKey());
        merged.put("x-workflow-step-id", correlation.stepId());
        // PRD-WF-002 D8a: branch-identity headers when the intent originates from a fan-out branch.
        if (correlation.branchTokenId() != null) {
            merged.put("x-workflow-branch-token-id", correlation.branchTokenId().toString());
        }
        if (correlation.forkStepId() != null) {
            merged.put("x-workflow-fork-step-id", correlation.forkStepId());
        }
        if (correlation.branchId() != null) {
            merged.put("x-workflow-branch-id", correlation.branchId());
        }
        return Map.copyOf(merged);
    }
}
