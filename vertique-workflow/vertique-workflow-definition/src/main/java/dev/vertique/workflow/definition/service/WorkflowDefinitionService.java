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
    /** Returns an immutable, source-free metadata snapshot in the selected scope. */
    Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter);
    /** Returns the source attribution of a definition without reading its source body. */
    Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key);
}
