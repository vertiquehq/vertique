// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.registry.store.DefinitionKey;
import java.util.Set;

/**
 * Names the values of a definition's state and command payloads that the instance history must never hold.
 *
 * <p>The engine asks the redactor for the pointers of the definition version an instance is pinned to, and replaces the
 * value at each pointer with the string {@code "[redacted]"} in every form it logs: the copy of a command's payload, the
 * patch of each decision, and every full-state entry. The instance's own state and the command rows keep their values,
 * because the engine needs them to run. A redacted value cannot be recovered from the history.
 *
 * <p>Semantics of the returned pointers:
 *
 * <ul>
 *   <li>A pointer is an RFC 6901 JSON Pointer such as {@code /card/number}. A key that holds {@code /} or {@code ~}
 *       is escaped as {@code ~1} or {@code ~0}.</li>
 *   <li>A pointer resolves into an array by index, for example {@code /cards/1/number}. An index names a position,
 *       not a record: when an element is inserted or removed the same index names another record, so the history of an
 *       array of sensitive records must not be redacted by index.</li>
 *   <li>A segment that is exactly {@code *} matches every element of an array and every member of an object at that
 *       position, whatever its index or key: {@code /cards/*}{@code /number} redacts the number of every card, and
 *       {@code /cards} redacts the whole array. Prefer the wildcard, or the array root, for an array of sensitive
 *       records. A key that is literally {@code *} cannot be named on its own.</li>
 *   <li>One set covers both the state and the command payloads of the definition version: the same pointers are applied
 *       to every logged document.</li>
 *   <li>A pointer that does not resolve in a document, or resolves to a JSON {@code null}, is ignored for that
 *       document. A pointer that names a subtree redacts the whole subtree.</li>
 *   <li>A pointer that is not a valid JSON Pointer, or an exception from this method, fails the segment as a transient
 *       failure: nothing is logged unredacted, and the instance shows as stuck until the redactor is fixed.</li>
 *   <li>Across a migration the pointers of the source version cover the migration command and every entry before it,
 *       and those of the target version cover the migrated state and every later entry; both cover the migration's own
 *       patch.</li>
 * </ul>
 *
 * <p>Limits to know:
 *
 * <ul>
 *   <li>Redaction is not retroactive: rows written before a redactor was bound, or before a pointer was added, keep the
 *       values they hold.</li>
 *   <li>The copy of a command's payload is matched on the JSON the client sent, as the engine bound it, so a value
 *       reachable under an alias or under a differently cased key is not matched by a pointer written for the
 *       definition's own field names.</li>
 * </ul>
 *
 * <p>The engine calls this method at most once per definition key in a segment, on the executor's event loop, so
 * implementations must not block.
 *
 * <p>The redactor is bound optionally; when none is bound nothing is redacted.
 */
@FunctionalInterface
public interface WorkflowHistoryRedactor {

    /**
     * The JSON Pointers into the state and command payloads of a definition version whose values are redacted from the
     * history.
     *
     * @param key the scope, id and version of the definition the instance is pinned to
     * @return the pointers; empty for none. The returned set is read, never modified
     */
    Set<String> redactedPointers(DefinitionKey key);
}
