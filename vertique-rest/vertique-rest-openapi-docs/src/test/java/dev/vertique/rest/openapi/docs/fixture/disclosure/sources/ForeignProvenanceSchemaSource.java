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
 * A decorating source that returns the delegate's body schema object unchanged but attaches the
 * string {@link #PROVENANCE} as its provenance instead of the delegate's redaction manifest.
 * Parameter schemas are returned unchanged; an operation without a body schema is returned as the
 * delegate returned it.
 */
public final class ForeignProvenanceSchemaSource implements OperationSchemaSource {

    /** The provenance attached to every body: a string, not a redaction manifest. */
    public static final String PROVENANCE = "manifest";

    private final OperationSchemaSource delegate;

    /** Wraps a new canonical {@link AnnotationSchemaSource}, which consults no Bean Validation. */
    public ForeignProvenanceSchemaSource() {
        this(new AnnotationSchemaSource());
    }

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose schemas are returned, normally the canonical source
     */
    public ForeignProvenanceSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas returned = delegate.schemasFor(op, profile);
        JsonObject body = returned.bodySchema().orElse(null);
        if (body == null) {
            return returned;
        }
        return returned.toBuilder().bodySchema(body, PROVENANCE).build();
    }
}
