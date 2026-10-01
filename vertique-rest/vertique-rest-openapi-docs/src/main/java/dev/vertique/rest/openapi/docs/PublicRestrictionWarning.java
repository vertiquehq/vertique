// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.List;

/**
 * Holds back the warning that a public document lists operations that restrict callers.
 *
 * <p>An operation restricts callers when its effective security policy is restrictive, when it
 * declares a security requirement set, or when it requires an authorization action. A public
 * document is served without authentication, so it shows anyone what such operations accept; the
 * warning names each of them as {@code <METHOD> <path> (<operationId>)} in document order (path
 * keys in natural order, then methods in Path Item order), and names no role, scope, action, or
 * claim. Publication proceeds. A protected document, or a public one without such operations, is
 * not warned about.
 */
final class PublicRestrictionWarning {

    /** The warning kind, logged at most once per document and component. */
    static final String KIND = "public-restriction";

    private PublicRestrictionWarning() {}

    /**
     * Adds the warning to the document's pending warnings when the document is public and lists an
     * operation that restricts callers.
     *
     * @param document the enabled document
     * @param mountPath the application's mount path as registered
     * @param operations the published operations of the document, in document order
     * @param warnings the document's pending warnings
     */
    static void add(
            EnabledDocuments.EnabledDocument document,
            String mountPath,
            List<DocumentSecurityAssembler.LocatedOperation> operations,
            PendingWarnings warnings) {
        if (document.access() != ApiDocs.Access.PUBLIC) {
            return;
        }
        List<String> entries = operations.stream()
                .filter(operation -> restrictsCallers(operation.publication()))
                .map(operation -> operation.method() + " " + operation.path() + " ("
                        + operation.publication().operationId() + ")")
                .toList();
        if (entries.isEmpty()) {
            return;
        }
        warnings.add(
                KIND,
                "apidocs.documents." + document.name() + ": the public document of application '" + document.name()
                        + "' at mount '" + mountPath + "' lists operations that restrict callers, and it is served"
                        + " without authentication: " + String.join(", ", entries));
    }

    /** Whether the operation's policy is restrictive, it declares a requirement set, or it requires an action. */
    private static boolean restrictsCallers(OperationPublication operation) {
        return operation.effectivePolicy().isRestrictive()
                || !operation.securityRequirementSets().isEmpty()
                || operation.requiresAction();
    }
}
