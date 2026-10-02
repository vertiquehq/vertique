// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.publication.OperationPublication;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
 *
 * <p>For a served contract the warning is computed from the mount's operations that are not hidden,
 * each path rendered as a generated document renders it, never from the contract's text, so it names
 * exactly what the generated document of that mount would name.
 */
final class PublicRestrictionWarning {

    /** The warning kind, logged at most once per document and component. */
    static final String KIND = "public-restriction";

    /** The lowercase methods of a Path Item Object, in the order a document lists them. */
    private static final List<String> METHOD_ORDER =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** Orders located operations as a document lists them: path, then Path Item method order. */
    private static final Comparator<DocumentSecurityAssembler.LocatedOperation> DOCUMENT_ORDER = Comparator.comparing(
                    DocumentSecurityAssembler.LocatedOperation::path)
            .thenComparingInt(operation -> methodPosition(operation.method()))
            .thenComparing(DocumentSecurityAssembler.LocatedOperation::method)
            .thenComparing(operation -> operation.publication().operationId());

    private PublicRestrictionWarning() {}

    /**
     * Adds the warning for a served contract's document, computed from the mount's operations.
     *
     * @param document the enabled document
     * @param mountPath the application's mount path as registered
     * @param visible the mount's operations that are not hidden, in any order
     * @param warnings the document's pending warnings
     */
    static void addServed(
            EnabledDocuments.EnabledDocument document,
            String mountPath,
            List<OperationPublication> visible,
            PendingWarnings warnings) {
        List<DocumentSecurityAssembler.LocatedOperation> located = visible.stream()
                .map(operation -> new DocumentSecurityAssembler.LocatedOperation(
                        RenderedPaths.render(operation.jaxRsPathTemplate()), operation))
                .sorted(DOCUMENT_ORDER)
                .toList();
        add(document, mountPath, located, warnings);
    }

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

    /** Returns the position of a method in a Path Item Object, unknown methods last. */
    private static int methodPosition(String method) {
        int position = METHOD_ORDER.indexOf(method.toLowerCase(Locale.ROOT));
        return position < 0 ? METHOD_ORDER.size() : position;
    }

    /** Whether the operation's policy is restrictive, it declares a requirement set, or it requires an action. */
    private static boolean restrictsCallers(OperationPublication operation) {
        return operation.effectivePolicy().isRestrictive()
                || !operation.securityRequirementSets().isEmpty()
                || operation.requiresAction();
    }
}
