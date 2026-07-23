// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.security.SecurityIdentity;

/**
 * Functional seam that resolves the executing {@link SecurityIdentity} to use as the actor when
 * {@link IdentitySnapshotReconstructionInitializer} mints a service-only or reconstructed
 * {@link dev.vertique.security.SecurityContext} for a deferred dispatch, from the
 * deferred-execution provenance that proved the dispatch is deferred.
 *
 * <p>The {@link DeferredExecutionOrigin} carries the provenance {@code kind} (delayed-job, cron, or
 * outbox-relay) and a {@code reference} — the specific job-type / cron-name / event-type — which is
 * the audit reason attributed to the resolved identity. The frozen serviceIdentity source per
 * PRD-ID-002 §14.3 "Frozen binding-precedence rule" is per execution path (delayed job →
 * {@code SystemIdentities.scheduledJob(reason=jobType)}, inbox → per-handler configured service
 * identity, cron → {@code SystemIdentities.scheduledJob(cronName)}) — this interface does not
 * hardcode any of those; an application binds its own resolver instance (or a per-handler configured
 * one) so the choice of identity stays with the boundary that knows its own semantics.
 */
@FunctionalInterface
public interface ServiceIdentityResolver {

    /**
     * Resolves the executing service {@link SecurityIdentity} from the deferred-execution
     * provenance.
     *
     * @param origin the deferred-execution provenance whose {@link DeferredExecutionOrigin#reference()}
     *               is the audit reason; never {@code null}
     * @return the {@link SecurityIdentity} to use as the actor; never {@code null}
     */
    SecurityIdentity resolve(DeferredExecutionOrigin origin);
}
