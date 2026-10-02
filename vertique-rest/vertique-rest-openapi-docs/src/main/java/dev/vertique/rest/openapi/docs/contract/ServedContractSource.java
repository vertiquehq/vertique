// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.contract;

import dev.vertique.rest.jaxrs.application.RestApplications;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides where an enabled document comes from and which location its contract resolves to.
 *
 * <p>An application whose entry in the {@link RestApplications} view has the contract origin {@link
 * RestApplications.ContractOrigin#CONFIGURATION CONFIGURATION} or {@link
 * RestApplications.ContractOrigin#ANNOTATION ANNOTATION} serves the contract at its effective
 * {@code openapiPath}; one whose origin is {@link RestApplications.ContractOrigin#GLOBAL GLOBAL} gets a
 * generated document. The view is read, never re-derived.
 *
 * <p>The resolved location mirrors how the Vert.x file system resolves the path it reads, without
 * reading its cache: an absolute path names that file; a relative path names the working-directory
 * file of that name when one exists, and otherwise the classpath resource of that name, looked up
 * through the thread's context class loader (or this module's class loader when the thread has none).
 * A file is named by its absolute, normalized path, without resolving symbolic links; a classpath
 * resource by its class-loader URL, never by the copy the file system extracts into its cache. When a
 * relative path names a working-directory file while a classpath resource of the same name also
 * exists, the file shadows the resource.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class ServedContractSource {

    private ServedContractSource() {}

    /**
     * Returns the entry of an application whose document is its own served contract.
     *
     * @param applications the declared applications of the component
     * @param name the application name
     * @return the application's entry, whose effective {@code openapiPath} is the contract location,
     *     when its contract origin is not {@link RestApplications.ContractOrigin#GLOBAL GLOBAL}, whose
     *     location is non-null once provisioning has passed; empty when its document is generated
     */
    public static Optional<RestApplications.Entry> served(RestApplications applications, String name) {
        return applications
                .byName(name)
                .filter(entry -> entry.contractOrigin() != RestApplications.ContractOrigin.GLOBAL);
    }

    /**
     * Names the setting an application's own contract location comes from, never its value.
     *
     * @param application the application, whose contract origin is not {@link
     *     RestApplications.ContractOrigin#GLOBAL GLOBAL}
     * @return {@code @RestApplication.openapiPath on <binary name>} of the declaring interface for a
     *     declared location; otherwise the configuration path {@code
     *     jaxrs.applications.<name>.openapiPath}
     */
    public static String setting(RestApplications.Entry application) {
        if (application.contractOrigin() == RestApplications.ContractOrigin.ANNOTATION) {
            return "@RestApplication.openapiPath on "
                    + application.declaringType().getName();
        }
        return "jaxrs.applications." + application.name() + ".openapiPath";
    }

    /**
     * Resolves a contract location as the file system resolves it.
     *
     * @param path the contract location, as configured or declared
     * @return the resolved location and whether a working-directory file shadows a classpath resource
     */
    static Resolution resolve(String path) {
        File file = new File(path);
        if (file.isAbsolute()) {
            return new Resolution(absolute(path), false);
        }
        URL resource = classLoader().getResource(path);
        if (file.exists()) {
            return new Resolution(absolute(path), resource != null);
        }
        return new Resolution(resource != null ? resource.toString() : path, false);
    }

    /** Returns the absolute, normalized form of a file path, without resolving symbolic links. */
    private static String absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    /** Returns the thread's context class loader, or this module's class loader when it has none. */
    private static ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ServedContractSource.class.getClassLoader();
    }

    /**
     * Where a contract location resolved.
     *
     * @param location the absolute path of the file, or the URL of the classpath resource
     * @param shadowsClasspath whether a relative location named a working-directory file while a
     *     classpath resource of the same name exists
     */
    record Resolution(String location, boolean shadowsClasspath) {}
}
