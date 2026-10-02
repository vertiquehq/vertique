// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Checks the parsed contract an application serves as its document against the routes of its mount. */
final class ServedContractChecks {

    private ServedContractChecks() {}

    /**
     * Checks a served contract.
     *
     * @param application the application's name
     * @param contract the parsed contract
     * @param mountPath the application's normalized mount path
     * @param routed the operations the mount routes
     */
    static void check(String application, JsonNode contract, String mountPath, List<RoutedOperation> routed) {
        // Checks nothing yet.
    }
}
