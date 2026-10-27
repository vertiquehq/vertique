// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * INTERNAL: reads of definition rows made on the transaction handle of the operation that needs them, so that no
 * operation takes a second pooled connection for definition data.
 *
 * <p>The public {@code WorkflowDefinitionRegistryStore} owns its transactions, and every read of it takes a pooled
 * connection of its own. An operation that already holds a connection reads through this interface instead. The
 * PostgreSQL module implements it for {@code SqlClient}; the engine does not depend on a dialect.
 *
 * @param <C> the transaction handle type of the dialect
 */
public interface TransactionalDefinitionReads<C> {

    /**
     * A stored document row: its record and its text, read together on one connection.
     *
     * @param record   the row's metadata
     * @param document the row's text
     */
    record StoredDocument(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {}

    /**
     * Reads the record and the text of a document row on the caller's transaction.
     *
     * @param key the scope, definition id and version
     * @param tx  the caller's transaction handle; the read neither commits nor releases it
     * @return a future of the row, or empty when there is no document row for the key
     */
    Future<Optional<StoredDocument>> findDocument(DefinitionKey key, C tx);

    /**
     * One stored version of a definition id as a start sees it: the columns a start selects by and validates with, and
     * nothing of the document text, provenance or name binding.
     *
     * @param key           the scope, definition id and version
     * @param kind          whether the version comes from code or from a document
     * @param contractName  the logical name of the contract the version implements
     * @param stateTypeName the logical name of the version's state type
     * @param planHash      the plan hash the version's instances are pinned with
     * @param status        the lifecycle status
     * @param failure       why the version is marked failing, or {@code null}; it refuses nothing at record time
     * @param signalNames   the signal names the version declares, or {@code null} for a row written without them
     * @param startSchema   the JSON Schema of the start payload, or {@code null} for a row written without one
     */
    record StartVersion(
            DefinitionKey key,
            DefinitionKind kind,
            String contractName,
            String stateTypeName,
            String planHash,
            DefinitionStatus status,
            @Nullable DefinitionFailure failure,
            @Nullable JsonObject signalNames,
            @Nullable JsonObject startSchema) {}

    /**
     * What a start needs to choose the version it runs: the stored versions of one definition id in the start scope
     * and the default scope, and the activation records of the id in the same two scopes.
     *
     * @param versions    every stored version of the id in the start scope and the default scope, in any status;
     *                    empty when the database holds no row of the id in either scope
     * @param activations the activation records of the id in the start scope and the default scope; at most two
     */
    record StartSelection(List<StartVersion> versions, List<DefinitionActivation> activations) {

        /** Copies the lists defensively. */
        public StartSelection {
            versions = List.copyOf(versions);
            activations = List.copyOf(activations);
        }
    }

    /**
     * Reads, in one statement on the caller's transaction, the rows and the activation records a start selects its
     * version from. The statement runs on the given handle, so under {@code READ COMMITTED} it sees every activation
     * committed before it ran, and under a stronger isolation level it sees the caller's snapshot. The read takes no
     * lock, sets no timeout and changes nothing.
     *
     * @param startScope   the scope the start runs in; the default scope is always read as well
     * @param definitionId the definition id the start names
     * @param tx           the caller's transaction handle; the read neither commits nor releases it
     * @return a future of the selection data
     */
    Future<StartSelection> selectionRead(String startScope, String definitionId, C tx);
}
