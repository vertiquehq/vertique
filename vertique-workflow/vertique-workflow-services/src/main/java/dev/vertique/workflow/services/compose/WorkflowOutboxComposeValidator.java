// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Per-plan validator that asserts a workflow plan is correctly composed for service dispatch:
 *
 * <ol>
 *   <li>A plan that dispatches to a service (it has a {@link ServiceDispatchNode} or a
 *       {@link CompensationNode}) needs a {@link DestinationType#SERVICE}
 *       {@link OutboxDestinationHandler} in the application's graph; without it, workflow
 *       {@code SERVICE} intents are written to the outbox but the relay silently skips them. A
 *       plan that dispatches to no service passes without one.</li>
 *   <li>Every {@link ServiceDispatchNode} and {@link CompensationNode} of the plan resolves to a
 *       known service target with a valid shape (not {@code @OneWay}, exactly one payload parameter,
 *       and a return type matching the reply wait, or {@code Future<Void>} without a reply). This catches mistyped target ids and
 *       contract drift when the plan is built rather than at the first dispatch attempt — the
 *       runtime check in {@link dev.vertique.workflow.services.recorder.OutboxSideEffectRecorder}
 *       still applies as defense in depth.</li>
 * </ol>
 *
 * <h2>When validation runs</h2>
 *
 * <p>The workflow registry runs every contributed {@link WorkflowDefinitionValidator} once for each
 * plan it builds, for code definitions at startup and for definitions registered later alike. A
 * code plan that fails a check aborts application startup with an {@link IllegalStateException}
 * naming the definition, step and target; a plan registered at runtime that fails one is refused
 * and never published. The validator holds no registry reference.
 */
@Singleton
public final class WorkflowOutboxComposeValidator implements WorkflowDefinitionValidator {

    private final Set<OutboxDestinationHandler> handlers;
    private final ServiceTargetResolver targetResolver;
    private final @Nullable WorkflowTypeNames typeNames;

    /**
     * Creates a validator for plans without replies. Reply plans require the constructor with a type index.
     *
     * @param handlers       the full set of registered {@link OutboxDestinationHandler} instances
     * @param targetResolver the service target resolver, used to look up each service step's
     *                       target id and shape
     */
    public WorkflowOutboxComposeValidator(
            Set<OutboxDestinationHandler> handlers, ServiceTargetResolver targetResolver) {
        this(handlers, targetResolver, null);
    }

    /** Creates a validator using the workflow type index to resolve reply types. */
    @Inject
    public WorkflowOutboxComposeValidator(
            Set<OutboxDestinationHandler> handlers,
            ServiceTargetResolver targetResolver,
            @Nullable WorkflowTypeNames typeNames) {
        this.handlers = Set.copyOf(handlers);
        this.targetResolver = targetResolver;
        this.typeNames = typeNames;
    }

    /**
     * Validates one candidate plan's service dispatch.
     *
     * @param definition the candidate definition whose plan is checked
     * @throws IllegalStateException if the plan dispatches to a service and no handler with
     *                               {@link DestinationType#SERVICE} is registered, or if the plan
     *                               references an unknown or wrong-shape service target
     */
    @Override
    public void validate(RegisteredWorkflowDefinition definition) {
        String defId = definition.plan().definitionId();
        long defVersion = definition.plan().definitionVersion();
        boolean handlerChecked = false;
        for (WorkflowNode node : definition.plan().nodes()) {
            String targetId;
            if (node instanceof ServiceDispatchNode sdn) {
                targetId = sdn.targetId();
            } else if (node instanceof CompensationNode cn) {
                targetId = cn.targetId();
            } else {
                continue;
            }
            if (!handlerChecked) {
                validateServiceHandlerPresent(handlers);
                handlerChecked = true;
            }
            WaitSignalNode reply = null;
            if (node instanceof ServiceDispatchNode dispatch) {
                reply = definition.plan().nodes().stream()
                        .filter(n -> n.stepId().equals(dispatch.nextStepId()))
                        .filter(WaitSignalNode.class::isInstance)
                        .map(WaitSignalNode.class::cast)
                        .filter(wait -> wait.reply() != null
                                && wait.reply().callStepId().equals(dispatch.stepId()))
                        .findFirst()
                        .orElse(null);
            }
            validateTarget(defId, defVersion, node.stepId(), targetId, targetResolver, reply, typeNames);
        }
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

    private static void validateTarget(
            String defId,
            long defVersion,
            String stepId,
            String targetId,
            ServiceTargetResolver targetResolver,
            WaitSignalNode reply,
            @Nullable WorkflowTypeNames typeNames) {
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
        boolean isVoid = Void.class.equals(rt) || void.class.equals(rt);
        if (reply != null && typeNames == null) {
            throw new IllegalStateException(
                    "Reply validation requires the workflow type index for step '" + stepId + "'");
        }
        boolean matches = reply == null
                || typeNames.find(reply.payloadTypeName()).filter(rt::equals).isPresent();
        if (reply != null && !reply.reply().completesLater() && !matches) {
            throw new IllegalStateException(
                    "Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId + "' reply type "
                            + reply.payloadTypeName() + " differs from service return type " + rt.getTypeName());
        }
        if (reply != null && reply.reply().completesLater() && !isVoid) {
            throw new IllegalStateException("Workflow definition '" + defId + "' v" + defVersion + " step '" + stepId
                    + "' completes later and must return Future<Void>; got " + rt.getTypeName());
        }
        if (reply == null && !isVoid) {
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
