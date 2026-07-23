// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

/**
 * Lifecycle status of a document-backed workflow definition as tracked by
 * {@link WorkflowDefinitionStore}.
 *
 * <p>This status is used by management and audit surfaces to communicate the lifecycle of a
 * definition through the store. It <strong>never</strong> overrides registry semantics — the
 * runtime engine always resolves via the {@link dev.vertique.workflow.registry.WorkflowRegistry},
 * which uses version-ordering to determine the "current" definition.
 *
 * <p>In particular, {@code SUPERSEDED} versions remain in the registry and stay resolvable by
 * pinned version for in-flight instances (FR-WF-DEF-061). The store's status field is purely
 * informational.
 */
public enum ActivationStatus {

    /**
     * The definition has been loaded and compiled but not yet activated.
     * It is stored in the {@link WorkflowDefinitionStore} as a candidate and has not been
     * registered into the {@link dev.vertique.workflow.registry.WorkflowRegistry}.
     */
    CANDIDATE,

    /**
     * An activation call has claimed this entry and is in progress (CAS intermediate state).
     *
     * <p>This status is held only while the registry registration is executing. On success the
     * status transitions to {@link #ACTIVE} or {@link #SUPERSEDED}; on failure it is reset to
     * {@link #CANDIDATE} so that a retry can succeed.
     *
     * <p>External callers observing this status should treat the definition as not yet active.
     * The status is visible via {@link WorkflowDefinitionStore#list()} snapshots taken mid-activation
     * but transitions quickly; it is never persisted as a terminal state.
     */
    ACTIVATING,

    /**
     * The definition has been registered into the
     * {@link dev.vertique.workflow.registry.WorkflowRegistry} and is available for new workflow
     * instances. If a higher version is later activated for the same {@code definitionId}, this
     * version transitions to {@link #SUPERSEDED}.
     */
    ACTIVE,

    /**
     * A higher version of the same {@code definitionId} has been activated, making this version
     * superseded. The definition is still registered in the registry and can be resolved by pinned
     * version for in-flight instances (FR-WF-DEF-061), but new instances will use the higher
     * version.
     */
    SUPERSEDED
}
