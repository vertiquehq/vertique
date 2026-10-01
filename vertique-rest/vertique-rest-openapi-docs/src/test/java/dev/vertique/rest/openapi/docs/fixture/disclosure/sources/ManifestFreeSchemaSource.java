// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * A decorating source that returns the delegate's body schema object unchanged but carries no
 * provenance: it rebuilds the delegate's result with the one-argument {@code bodySchema(JsonObject)},
 * which drops the redaction manifest the canonical source attached. Parameter schemas are returned
 * unchanged. An operation without a body schema is returned as the delegate returned it.
 */
public final class ManifestFreeSchemaSource implements OperationSchemaSource {

    private final OperationSchemaSource delegate;

    /** Wraps a new canonical {@link AnnotationSchemaSource}, which consults no Bean Validation. */
    public ManifestFreeSchemaSource() {
        this(new AnnotationSchemaSource());
    }

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose schemas are returned, normally the canonical source
     */
    public ManifestFreeSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas returned = delegate.schemasFor(op, profile);
        JsonObject body = returned.bodySchema().orElse(null);
        if (body == null) {
            return returned;
        }
        return returned.toBuilder().bodySchema(body).build();
    }
}
