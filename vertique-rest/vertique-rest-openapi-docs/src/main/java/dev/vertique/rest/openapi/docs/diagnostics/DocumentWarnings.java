// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.diagnostics;

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
 * name, a mount path, and, for a served contract, its contract location and the location it resolved
 * to. The guard is thread-safe: compositions validated concurrently log each
 * warning once.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
@Singleton
public final class DocumentWarnings {

    // The logger name is documented application-facing surface (module.md); it keeps the name the
    // class had in the module's root package rather than following the class into this package.
    private static final Logger LOG = LoggerFactory.getLogger("dev.vertique.rest.openapi.docs.DocumentWarnings");

    /** The notice kind naming where an enabled document comes from. */
    public static final String SOURCE = "source";

    /** The warning kind of a relative contract location whose working-directory file shadows a classpath resource. */
    public static final String CONTRACT_SHADOWED = "contract-shadowed";

    /** The warning kind of a served contract whose {@code servers} do not start with the mount path. */
    public static final String CONTRACT_SERVERS = "contract-servers";

    /** The warning kinds and documents already warned about, as {@code kind + '\0' + name}. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Inject
    public DocumentWarnings() {}

    /**
     * Logs a warning unless a warning of the same kind was already logged for the same document.
     *
     * @param kind the warning kind, a fixed identifier chosen by the caller
     * @param documentName the document's application name
     * @param message the complete warning message, starting with the document's configuration path
     * @return {@code true} when the warning was logged, {@code false} when it had been logged before
     */
    public boolean warnOnce(String kind, String documentName, String message) {
        if (!firstOccurrence(kind, documentName)) {
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
    public boolean infoOnce(String kind, String documentName, String message) {
        if (!firstOccurrence(kind, documentName)) {
            return false;
        }
        LOG.info("{}", message);
        return true;
    }

    /**
     * Returns the source notice of a generated document.
     *
     * @param name the document's application name
     * @return the notice
     */
    public static String generatedSource(String name) {
        return "apidocs.documents." + name + ": the document of application '" + name + "' is generated";
    }

    /**
     * Returns the source notice of a served contract. The resolved location ends the notice.
     *
     * @param name the document's application name
     * @param location the resolved location: an absolute file path or a classpath resource URL
     * @return the notice
     */
    public static String servedSource(String name, String location) {
        return "apidocs.documents." + name + ": the document of application '" + name + "' is served from " + location;
    }

    /**
     * Returns the warning that a working-directory file shadows a classpath resource of the same
     * relative name.
     *
     * @param name the document's application name
     * @param relativePath the application's relative contract location
     * @return the warning
     */
    public static String contractShadowed(String name, String relativePath) {
        return "apidocs.documents." + name + ": the contract location '" + relativePath + "' of application '"
                + name + "' is relative, and the working-directory file of that name shadows the classpath"
                + " resource of the same name; the working-directory file is served and validated against";
    }

    /**
     * Returns the warning that a served contract's first server is not the application's mount path.
     *
     * @param name the document's application name
     * @param mountPath the application's mount path without its trailing {@code /*}, {@code /} for
     *     the root mount
     * @return the warning
     */
    public static String contractServers(String name, String mountPath) {
        return "apidocs.documents." + name + ": the contract of application '" + name
                + "' does not list the mount path '" + mountPath
                + "' as the url of its first server; the served document is not rewritten";
    }

    /** Records the kind and document, reporting whether this is the first time they are recorded. */
    private boolean firstOccurrence(String kind, String documentName) {
        return warned.add(kind + '\0' + documentName);
    }
}
