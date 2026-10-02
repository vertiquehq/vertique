// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.json.JsonObject;

/**
 * Verifies that a captured request body carries the redaction manifest of exactly its content.
 *
 * <p>A body schema is published only when its provenance is a {@link RedactionManifest} that
 * {@linkplain RedactionManifest#matches(String) matches} the captured schema as it was captured,
 * before the document copies, redacts, or relocates anything. Only the framework's schema generator
 * binds a manifest, so a body schema a source built, replaced, or edited, or one whose provenance is
 * anything else, fails publication. The failure names the operation and the class of the bound schema
 * source, never schema content.
 */
final class ManifestVerifier {

    private ManifestVerifier() {}

    /**
     * Verifies the manifest of a captured request body.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime id of the operation
     * @param captured the captured body schema, only read
     * @param provenance the body schema's provenance, or {@code null}
     * @param context the component's assembly inputs, naming the bound schema source
     * @return the verified manifest
     * @throws RestConfigurationException when the provenance is not a manifest matching the captured
     *     schema
     */
    static RedactionManifest verify(
            String subject, String operationId, JsonObject captured, Object provenance, AssemblyContext context) {
        if (provenance instanceof RedactionManifest manifest && manifest.matches(captured.encode())) {
            return manifest;
        }
        throw new RestConfigurationException(
                subject + ": " + SchemaPublicationSubject.body(operationId).refusalPhrase()
                        + " carries no redaction manifest matching its content " + sourcePhrase(context)
                        + "; only the framework's schema generator binds one, so the source must return"
                        + " the generated body schema and its manifest unchanged");
    }

    /**
     * Names the bound schema source for a failure message.
     *
     * @param context the component's assembly inputs
     * @return {@code (schema source <binary name>)} of the bound source's runtime class, or {@code (no
     *     schema source is bound)}
     */
    static String sourcePhrase(AssemblyContext context) {
        return context.schemaSource()
                .map(source -> "(schema source " + source.getClass().getName() + ")")
                .orElse("(no schema source is bound)");
    }
}
