// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Seeds tenant document rows of any id, including ids outside the tenant's namespace, the way an older release
 * could have stored them.
 *
 * <p>Rows are written through the registry store contract, never through the definition service, so the service's
 * load rules do not apply. The document is compiled by the real definition pipeline of the given graph, so the row
 * carries the plan hash that restoring it later will recompute; the store derives the document digest. Seeding only
 * queues behind the definition id lock like any other store operation, so it can be called while a test holds that
 * lock. The row names the contract of the compiled document, so a scope's row of an id the default scope
 * holds under the same contract is accepted like a load of it.
 */
final class TenantRowSeed {

    private TenantRowSeed() {}

    /**
     * Inserts the document as a {@code CANDIDATE} row of the tenant and completes with its key.
     *
     * <p>The key's id and version are those of the compiled document, so the id may be any the document declares.
     */
    static Future<DefinitionKey> insertCandidate(
            AppComponent app, String scopeKey, byte[] document, DocumentFormat format, SourceMetadata source) {
        CompiledDefinition compiled = app.definitionPipeline().load(new DefinitionResource(document, format, source));
        DefinitionKey key = new DefinitionKey(
                scopeKey,
                compiled.definition().definitionId(),
                compiled.definition().definitionVersion());
        Instant now = Instant.now();
        WorkflowDefinitionRecord record = new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                compiled.contractName(),
                "example.State",
                compiled.planHash(),
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance(
                        source.sourceType(),
                        source.uri(),
                        source.revision(),
                        source.author(),
                        source.uploadId(),
                        source.loadedAt()),
                format.name(),
                now,
                now,
                Map.of(),
                null);
        WorkflowDefinitionDocument text =
                new WorkflowDefinitionDocument(key, new String(document, StandardCharsets.UTF_8), format.name());
        return app.definitionRegistryStore().insertCandidate(record, text).map(key);
    }

    /**
     * Seeds the YAML document as a tenant row and moves it to the target status through the registry store
     * contract, blocking until the row is committed.
     *
     * <p>{@code CANDIDATE} stops after the insert, {@code AVAILABLE} also claims and completes the publication, and
     * {@code DEPRECATED} then moves the available row to deprecated. The row is not registered in the graph's
     * executable registry; a graph started afterwards restores it from the table.
     */
    static DefinitionKey seed(
            AppComponent app, String scopeKey, byte[] document, SourceMetadata source, DefinitionStatus target) {
        if (target != DefinitionStatus.CANDIDATE
                && target != DefinitionStatus.AVAILABLE
                && target != DefinitionStatus.DEPRECATED)
            throw new IllegalArgumentException("A seeded row can be CANDIDATE, AVAILABLE or DEPRECATED, not " + target);
        WorkflowDefinitionRegistryStore store = app.definitionRegistryStore();
        DefinitionKey key =
                DefinitionScenario.await(insertCandidate(app, scopeKey, document, DocumentFormat.YAML, source));
        if (target == DefinitionStatus.CANDIDATE) return key;
        DefinitionScenario.await(store.claim(key).compose(store::complete));
        if (target == DefinitionStatus.DEPRECATED)
            DefinitionScenario.await(store.transition(key, DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED));
        return key;
    }
}
