// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.metadata.AnnotatedInfo;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * The documents enabled for one component, ordered by application name, and the path prefix they
 * are served under.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param all the enabled documents
 * @param path the documentation path prefix, without a trailing slash: the configured {@code
 *     apidocs.path}, or {@link #DEFAULT_PATH} when it is not configured or the feature is disabled
 */
public record EnabledDocuments(List<EnabledDocument> all, String path) {

    /** The default documentation path prefix. */
    public static final String DEFAULT_PATH = "/apidocs";

    /**
     * Copies the document list unmodifiably.
     *
     * @param all the enabled documents
     * @param path the documentation path prefix
     */
    public EnabledDocuments {
        all = List.copyOf(all);
    }

    /**
     * Creates the enabled documents under the default path prefix.
     *
     * @param all the enabled documents
     */
    EnabledDocuments(List<EnabledDocument> all) {
        this(all, DEFAULT_PATH);
    }

    /**
     * Finds an enabled document by application name.
     *
     * @param name the application name
     * @return the document, or empty when none is enabled under that name
     */
    public Optional<EnabledDocument> byName(String name) {
        return all.stream().filter(document -> document.name().equals(name)).findFirst();
    }

    /**
     * Reports whether no document is enabled.
     *
     * @return {@code true} when {@link #all()} is empty
     */
    public boolean isEmpty() {
        return all.isEmpty();
    }

    /**
     * One enabled document.
     *
     * @param name the application name
     * @param declaringType the declaring interface
     * @param access the access policy of the document routes
     * @param mountPath the mount path of the application
     * @param contractOrigin where the application's contract comes from
     * @param info the {@code info} object of the document: its {@code title}, {@code version}, and
     *     {@code description}; {@code null} when not configured, and always {@code null} once
     *     resolved for a document whose application serves its own contract
     * @param serverUrl the configured server URL of the document, or {@code null} when absent
     * @param annotatedInfo the complete {@code info} read from the declaring interface's {@code
     *     OpenAPIDefinition}, which the document publishes instead of {@code info}; {@code null}
     *     when the {@code info} is configured, not yet resolved, or the application serves its own
     *     contract
     */
    public record EnabledDocument(
            String name,
            Class<?> declaringType,
            ApiDocs.Access access,
            String mountPath,
            ContractOrigin contractOrigin,
            @Nullable InfoConfig info,
            @Nullable String serverUrl,
            @Nullable AnnotatedInfo annotatedInfo) {

        /**
         * Creates a document without a complete annotated {@code info}.
         *
         * @param name the application name
         * @param declaringType the declaring interface
         * @param access the access policy of the document routes
         * @param mountPath the mount path of the application
         * @param contractOrigin where the application's contract comes from
         * @param info the {@code info} object of the document
         * @param serverUrl the configured server URL of the document, or {@code null} when absent
         */
        EnabledDocument(
                String name,
                Class<?> declaringType,
                ApiDocs.Access access,
                String mountPath,
                ContractOrigin contractOrigin,
                @Nullable InfoConfig info,
                @Nullable String serverUrl) {
            this(name, declaringType, access, mountPath, contractOrigin, info, serverUrl, null);
        }
    }
}
