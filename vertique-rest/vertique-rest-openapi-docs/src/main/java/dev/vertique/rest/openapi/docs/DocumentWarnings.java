// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The documentation module's startup warnings and notices. A warning is logged at {@code WARN} and a
 * notice at {@code INFO}, both on the logger named after this class, and each at most once per kind
 * and document within one component, so a second composition or verticle instance of the same
 * component never repeats it. Warnings and notices share one guard, so their kinds are distinct.
 *
 * <p>A message starts with the document's configuration path,
 * {@code apidocs.documents.<name>}, and carries no configuration value other than the document
 * name and a mount path. The guard is thread-safe: compositions validated concurrently log each
 * warning once.
 */
@Singleton
final class DocumentWarnings {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentWarnings.class);

    /** The warning kinds and documents already warned about, as {@code kind + '\0' + name}. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Inject
    DocumentWarnings() {}

    /**
     * Logs a warning unless a warning of the same kind was already logged for the same document.
     *
     * @param kind the warning kind, a fixed identifier chosen by the caller
     * @param documentName the document's application name
     * @param message the complete warning message, starting with the document's configuration path
     * @return {@code true} when the warning was logged, {@code false} when it had been logged before
     */
    boolean warnOnce(String kind, String documentName, String message) {
        if (!warned.add(kind + '\0' + documentName)) {
            return false;
        }
        LOG.warn("{}", message);
        return true;
    }

    /**
     * Logs a notice at {@code INFO} unless a message of the same kind was already logged for the same
     * document.
     *
     * @param kind the notice kind, a fixed identifier chosen by the caller
     * @param documentName the document's application name
     * @param message the complete notice, starting with the document's configuration path
     * @return {@code true} when the notice was logged, {@code false} when it had been logged before
     */
    boolean infoOnce(String kind, String documentName, String message) {
        if (!warned.add(kind + '\0' + documentName)) {
            return false;
        }
        LOG.info("{}", message);
        return true;
    }
}
