// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

/**
 * Lifecycle status of a document-backed workflow definition as tracked by
 * {@link WorkflowDefinitionStore}.
 *
 * <p>This status is used by management and audit surfaces to communicate the lifecycle of a
 * definition through the store. It <strong>never</strong> overrides registry semantics: the
 * runtime engine always resolves via the {@link dev.vertique.workflow.registry.WorkflowRegistry}.
 *
 * <p>Any number of versions of one {@code definitionId} are {@link #AVAILABLE} at once, and each
 * stays resolvable by pinned version for in-flight instances. Which of them starts by default is
 * chosen separately from this status.
 */
public enum ActivationStatus {

    /**
     * The definition has been loaded and compiled but not yet published.
     * It is stored in the {@link WorkflowDefinitionStore} as a candidate and has not been
     * registered into the {@link dev.vertique.workflow.registry.WorkflowRegistry}.
     */
    CANDIDATE,

    /**
     * A publication call has claimed this entry and is in progress (CAS intermediate state).
     *
     * <p>This status is held only while the registry registration is executing. On success the
     * status transitions to {@link #AVAILABLE}; on failure it is reset to {@link #CANDIDATE} so that
     * a retry can succeed.
     *
     * <p>External callers observing this status should treat the definition as not yet available.
     * The status is visible via {@link WorkflowDefinitionStore#list()} snapshots taken mid-publication
     * but transitions quickly; it is never persisted as a terminal state.
     */
    PUBLISHING,

    /**
     * The definition has been registered into the
     * {@link dev.vertique.workflow.registry.WorkflowRegistry} and can be started and resolved by
     * pinned version. Other versions of the same {@code definitionId} are unaffected.
     */
    AVAILABLE,

    /**
     * Superseded by {@link #PUBLISHING} and {@link #AVAILABLE}; nothing sets it any more.
     * It is removed in the next breaking release.
     */
    ACTIVATING,

    /**
     * Superseded by {@link #AVAILABLE}; nothing sets it any more.
     * It is removed in the next breaking release.
     */
    ACTIVE,

    /**
     * Superseded by {@link #AVAILABLE}; nothing sets it any more.
     * It is removed in the next breaking release.
     */
    SUPERSEDED,

    /** New starts are refused while pinned resumes remain available. */
    DEPRECATED,

    /** Retained metadata with no executable membership. */
    RETIRED
}
