// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/** Asynchronous management of document candidates and their executable activation. */
public interface WorkflowDefinitionService {
    /** Parses and compiles a candidate without publishing it; null tenant denotes global scope. */
    Future<CompiledDefinitionRef> load(
            byte[] content, DocumentFormat format, SourceMetadata metadata, @Nullable String tenantKey);
    /** Activates an existing candidate, preserving the prior publication when activation fails. */
    Future<Void> activate(DefinitionKey key);
    /**
     * Deprecates an active or superseded definition so it is skipped by current resolution and refused for new
     * explicit starts and migration targets, while instances already pinned to it keep running. The status is
     * committed durably before it is projected into the local cache and registry. A durable registry is
     * required; without one the returned future fails.
     */
    Future<Void> deprecate(DefinitionKey key);
    /** Returns an immutable, source-free metadata snapshot in the selected scope. */
    Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter);
    /** Returns the source attribution of a definition without reading its source body. */
    Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key);
}
