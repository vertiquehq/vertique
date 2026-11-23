// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** INTERNAL: captures attribution from the caller's context before a workflow action is queued. */
public final class WorkflowActorCapture {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActorCapture.class);

    private WorkflowActorCapture() {}

    public static WorkflowAttribution capture(ContextHolder holder, WorkflowActorMapper mapper) {
        SecurityIdentity identity = holder.current(SecurityContext.class)
                .map(SecurityContext::identity)
                .orElse(null);
        if (identity == null || identity.actor().type() == PrincipalType.ANONYMOUS) {
            return WorkflowAttribution.absent();
        }
        WorkflowActor actor = Objects.requireNonNull(mapper.toWorkflowActor(identity), "mapped actor");
        PrincipalRef caller = identity.actor();
        PrincipalRef subject = identity.subject()
                .filter(ref -> ref.type() != caller.type() || !ref.id().equals(caller.id()))
                .orElse(null);
        WorkflowAttribution.Status status =
                holder.current(SnapshotDegradationMarker.class).isPresent()
                        ? WorkflowAttribution.Status.DEGRADED
                        : WorkflowAttribution.Status.VERIFIED;
        return new WorkflowAttribution(actor, subject, status);
    }

    public static WorkflowAttribution bestEffort(Supplier<WorkflowAttribution> capture) {
        try {
            return capture.get();
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not capture workflow attribution ({})",
                    failure.getClass().getName());
            return WorkflowAttribution.unavailable();
        }
    }
}
