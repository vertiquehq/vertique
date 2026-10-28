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
 *   <li>A pointer resolves into an array by index, for example {@code /cards/1/number}.</li>
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
