// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.registry.store.DefinitionKey;
import java.util.Objects;

/** Typed, source-free diagnostic for a durable definition lifecycle refusal. */
public class WorkflowDefinitionLifecycleException extends WorkflowDefinitionException {
    public enum Reason {
        INVALID_TRANSITION,
        STALE_CLAIM,
        PINNED_INSTANCES,
        DRIFT,
        LOADER_MISSING,
        CODE_UNAVAILABLE,
        DEPRECATED
    }

    private final Reason reason;
    private final DefinitionKey key;

    public WorkflowDefinitionLifecycleException(Reason reason, DefinitionKey key, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.key = Objects.requireNonNull(key, "key");
    }

    public Reason reason() {
        return reason;
    }

    public DefinitionKey key() {
        return key;
    }
}
