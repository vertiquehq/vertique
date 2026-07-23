// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Startup validator that asserts the application's Dagger graph is correctly composed for
 * workflow service dispatch:
 *
 * <ol>
 *   <li>A {@link DestinationType#SERVICE} {@link OutboxDestinationHandler} is registered (without
 *       it, workflow {@code SERVICE} intents are written to the outbox but the relay silently
 *       skips them).</li>
 *   <li>Every {@link ServiceDispatchNode} and {@link CompensationNode} in every registered
 *       workflow plan resolves to a known service target with a valid shape (not {@code @OneWay},
 *       returns {@code Future<Void>}, exactly one payload parameter). This catches mistyped target
 *       ids and contract drift at application boot rather than at the first dispatch attempt — at
 *       which point the runtime check in
 *       {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder} still applies as
 *       defense in depth.</li>
 * </ol>
 *
 * <h2>How validation runs</h2>
 *
 * <p>The validator is enforced through the recorder constructor:
 * {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder} takes this validator
 * as a required dependency, so Dagger must construct the validator before the recorder. Because
 * the recorder participates in the {@code @WorkflowRecorders} multibinding that
 * {@code RecorderRouter} consumes inside {@code PgWorkflowEngine}, requesting the engine forces
 * the entire chain — including this validator — to be built. Validation runs at Dagger graph
 * construction time; if any check fails the constructor throws
 * {@link IllegalStateException} with a message identifying the offending definition, step, and
 * target, aborting startup.
 *
 * <p>Applications MAY additionally expose
 * {@code WorkflowOutboxComposeValidator workflowComposeValidator()} on their
 * {@code AppComponent} and call it during {@code MainVerticle.start()} for an even earlier fail-
 * fast (e.g., before any verticle is deployed). This is now optional belt-and-suspenders, not a
 * requirement for validation to run.
 *
 * <p>This validator implements {@link ComposeValidator} so the framework can materialize it in the
 * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing its
 * construction, hence its validation) without any app code referencing it. The marker adds no
 * methods and does not change how validation runs.
 */
@Singleton
public final class WorkflowOutboxComposeValidator implements ComposeValidator {

    /**
     * Validates the application's Dagger graph for workflow service dispatch.
     *
     * @param handlers       the full set of registered {@link OutboxDestinationHandler} instances
     * @param registry       the workflow registry, used to walk every registered plan
     * @param targetResolver the service target resolver, used to look up each service step's
     *                       target id and shape
     * @throws IllegalStateException if no handler with {@link DestinationType#SERVICE} is
     *                               registered, or if any registered plan references an unknown
     *                               or wrong-shape service target
     */
    @Inject
    public WorkflowOutboxComposeValidator(
            Set<OutboxDestinationHandler> handlers, WorkflowRegistry registry, ServiceTargetResolver targetResolver) {
        validateServiceHandlerPresent(handlers);
        validateAllRegisteredTargets(registry, targetResolver);
    }

    private static void validateServiceHandlerPresent(Set<OutboxDestinationHandler> handlers) {
        boolean hasService = handlers.stream().anyMatch(h -> h.destinationType().equals(DestinationType.SERVICE));
        if (!hasService) {
            throw new IllegalStateException(
                    "vertique-workflow-services requires a SERVICE OutboxDestinationHandler in the application's "
                            + "Dagger graph. Add TransactionalMessagingServiceModule from vertique-inbox-outbox-services to "
                            + "your AppComponent. Without it, workflow SERVICE intents are written to the outbox but the "
                            + "OutboxRelay silently skips them (no SERVICE handler is registered to relay them).");
        }
    }

    private static void validateAllRegisteredTargets(WorkflowRegistry registry, ServiceTargetResolver targetResolver) {
        for (RuntimeWorkflow rw : registry.allRegistered()) {
            String defId = rw.plan().definitionId();
            long defVersion = rw.plan().definitionVersion();
            for (WorkflowNode node : rw.plan().nodes()) {
                String targetId;
                if (node instanceof ServiceDispatchNode sdn) {
                    targetId = sdn.targetId();
                } else if (node instanceof CompensationNode cn) {
                    targetId = cn.targetId();
                } else {
                    continue;
                }
                validateTarget(defId, defVersion, node.stepId(), targetId, targetResolver);
            }
        }
    }

    private static void validateTarget(
            String defId, long defVersion, String stepId, String targetId, ServiceTargetResolver targetResolver) {
        ResolvedServiceTarget target;
        try {
            target = targetResolver.resolve(targetId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId
                            + "' references unknown service target id '" + targetId + "': " + ex.getMessage(),
                    ex);
        }
        ServiceMethodMeta meta = target.meta();
        if (meta.oneWay()) {
            throw new IllegalStateException("Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId
                    + "' references service target '" + targetId
                    + "' which is @OneWay; cycle 1 only supports request/reply targets that"
                    + " return Future<Void>.");
        }
        Class<?> rt = meta.returnType();
        if (!Void.class.equals(rt) && !void.class.equals(rt)) {
            throw new IllegalStateException("Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId
                    + "' references service target '" + targetId + "' which must return Future<Void>; got "
                    + rt.getTypeName());
        }
        long payloadParams = meta.params().stream()
                .filter(p -> p.source() == ParamSource.PAYLOAD)
                .count();
        if (payloadParams != 1) {
            throw new IllegalStateException("Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId
                    + "' references service target '" + targetId
                    + "' which must have exactly one payload parameter; got " + payloadParams);
        }
    }
}
