// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublicationSink;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The publication sink of the documentation module. It asks for operation detail only for the
 * applications that have an enabled document, and hands each such mount's publication to the
 * {@link DocumentStore}.
 *
 * <p>The publication a sink receives must not be retained past the call. The sink therefore takes a
 * detached copy during {@link #mountBuilt}: every schema {@link JsonObject} is copied and the
 * operation descriptor is dropped. The copy shares only immutable records with the publication (the
 * input bindings, response shapes, policies, requirement sets and the body provenance manifest).
 * The stored document retains only the byte arrays, entity tags and the string snapshot.
 */
final class DocsPublicationSink implements OperationPublicationSink {

    private final EnabledDocuments documents;
    private final DocumentStore store;

    DocsPublicationSink(EnabledDocuments documents, DocumentStore store) {
        this.documents = documents;
        this.store = store;
    }

    /**
     * Reports whether the application has an enabled document.
     *
     * @param applicationName the application name, or {@code null} for a mount that serves no
     *     declared application
     * @return {@code true} exactly when the name is non-null and names an enabled document
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return applicationName != null && documents.byName(applicationName).isPresent();
    }

    /**
     * Publishes the mount of a documented application; every other mount completes at once.
     *
     * @param publication the publication of the mount
     * @return a future that completes on the calling context when this composition's part of the
     *     publication is done, and fails with a {@link RestConfigurationException} when the calling
     *     thread has no Vert.x context or the mount differs from the document already published
     */
    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        String applicationName = publication.applicationName();
        if (applicationName == null) {
            return Future.succeededFuture();
        }
        EnabledDocuments.EnabledDocument document =
                documents.byName(applicationName).orElse(null);
        if (document == null) {
            return Future.succeededFuture();
        }
        Context caller = Vertx.currentContext();
        if (caller == null) {
            return Future.failedFuture(new RestConfigurationException("The mount of application '" + applicationName
                    + "' was built outside a Vert.x context, so its document cannot be published"));
        }
        MountPublication detached = detach(publication);
        InfoConfig info = document.info();
        return store.publish(
                applicationName,
                caller,
                () -> DocumentWriter.write(info, SnapshotRenderer.render(detached)),
                () -> SnapshotRenderer.render(detached));
    }

    private static MountPublication detach(MountPublication publication) {
        List<OperationPublication> operations = publication.operations().stream()
                .map(DocsPublicationSink::detach)
                .toList();
        return new MountPublication(
                publication.mountPath(),
                publication.mountId(),
                publication.applicationName(),
                publication.declaringType(),
                publication.strategyId(),
                operations);
    }

    private static OperationPublication detach(OperationPublication operation) {
        OperationDetail detail = operation.detail();
        return new OperationPublication(
                operation.operationId(),
                operation.httpMethod(),
                operation.jaxRsPathTemplate(),
                operation.vertxRouteValue(),
                operation.vertxRouteIsRegex(),
                operation.effectivePolicy(),
                operation.securityRequirementSets(),
                operation.requiresAction(),
                detail == null ? null : detach(detail));
    }

    private static OperationDetail detach(OperationDetail detail) {
        return new OperationDetail(
                null,
                detail.profileId(),
                detach(detail.schemas()),
                detail.gateInstalled(),
                detail.inputs(),
                detail.response());
    }

    private static CapturedSchemas detach(CapturedSchemas schemas) {
        JsonObject body = schemas.body();
        Map<InputKey, JsonObject> parameters = new HashMap<>();
        schemas.parameters().forEach((key, schema) -> parameters.put(key, schema.copy()));
        return new CapturedSchemas(body == null ? null : body.copy(), schemas.bodyProvenance(), parameters);
    }
}
