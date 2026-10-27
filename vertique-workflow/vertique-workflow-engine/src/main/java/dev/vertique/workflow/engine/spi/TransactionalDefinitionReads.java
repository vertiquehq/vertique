// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
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
}
