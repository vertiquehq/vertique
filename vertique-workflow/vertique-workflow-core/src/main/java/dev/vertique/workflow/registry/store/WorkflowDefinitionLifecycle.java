// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;

/** Durable lifecycle operations, supplied by the workflow engine. */
public interface WorkflowDefinitionLifecycle {
    /** Compiles the stored candidate, verifies its hash and publishes a fenced activation. */
    Future<Void> activate(DefinitionKey key);

    /**
     * Deprecates an active or superseded definition. The durable status is committed first; only after that
     * commit does the local registry mark the version deprecated, so current resolution skips it and explicit
     * new starts and migration targets are refused while pinned instances keep running. A definition that is
     * already deprecated is accepted, which re-applies the local mark. Any other status fails typed.
     * The local mark is a no-op when the version is not registered on this node, and a registry that does
     * not support deprecation fails the call after the durable commit, leaving the committed status in
     * place. Deprecating the active version does not promote another version.
     */
    Future<Void> deprecate(DefinitionKey key);

    /**
     * Retires a candidate, or a deprecated definition once it has cooled and no instance is pinned to it. The
     * durable status is committed first, atomically with the cooling and pin checks; a refusal fails the future
     * typed and changes nothing, durably or locally. Only after the commit does the local registry drop the
     * version and its contract mapping, and only when the registration there is the one this lifecycle published
     * for the same owner. A registration that the lifecycle did not publish, or that a direct registry call has
     * since replaced, makes the call fail as an invalid transition before any durable change, and is never
     * removed. A registration published for a different owner is left in place.
     */
    Future<Void> retire(DefinitionKey key);
}
