// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;

/** Durable lifecycle operations, supplied by the workflow engine. */
public interface WorkflowDefinitionLifecycle {
    /** Compiles the stored candidate, verifies its hash and publishes it through a fenced claim. */
    Future<Void> activate(DefinitionKey key);

    /**
     * Compiles the stored candidate, verifies its hash and publishes it through a fenced claim, so that the
     * version becomes available. A version that is already available is accepted without a change.
     */
    default Future<Void> publish(DefinitionKey key) {
        return activate(key);
    }

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
     * version, and only when the registration there is the one this lifecycle published
     * for the same owner. A registration that the lifecycle did not publish, or that a direct registry call has
     * since replaced, makes the call fail as an invalid transition before any durable change, and is never
     * removed. A registration published for a different owner is left in place.
     */
    Future<Void> retire(DefinitionKey key);

    /**
     * Reinstates a retired version when its executable source is available and no other owner holds the id.
     * A code version needs the code the application deployed; a document version is recompiled from its stored
     * text, whose hash must equal the recorded one. The executable registration is published locally first, and
     * then the durable change is committed as one transaction that returns the row to service and settles which
     * version of the owner is active. When the durable change fails, only the registration this call published
     * is removed, and a registration that existed before the call stays. The future fails typed with
     * {@code CODE_UNAVAILABLE} when the code is not deployed, {@code OWNER_CONFLICT} when another owner holds
     * the id, {@code DRIFT} when the source no longer compiles to the recorded hash, and
     * {@code LOADER_MISSING} when no single loader supports a document's format or its stored text is missing,
     * and {@code INVALID_TRANSITION} when the version is not retired or a registration this lifecycle did not
     * publish stands in the way.
     */
    Future<Void> reinstate(DefinitionKey key);
}
