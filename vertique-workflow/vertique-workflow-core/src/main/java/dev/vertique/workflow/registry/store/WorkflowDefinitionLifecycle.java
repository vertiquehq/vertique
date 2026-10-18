// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.Future;

/** Durable lifecycle operations, supplied by the workflow engine. */
public interface WorkflowDefinitionLifecycle {
    /** Compiles the stored candidate, verifies its hash and publishes a fenced activation. */
    Future<Void> activate(DefinitionKey key);
}
