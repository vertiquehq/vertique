// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import io.vertx.core.json.JsonObject;

/**
 * A {@link CountingSchemaSource} whose answer changes from call to call for one operation only:
 * the query parameter {@code dryRun} of {@link CatalogResource#CREATE_ITEM} is marked
 * {@value #CALL_MARKER} with this source's call number. The body schema and its manifest never
 * change, so every difference lies in a parameter schema, which carries no manifest.
 */
public final class StatefulSchemaSource extends CountingSchemaSource {

    /** The schema keyword that carries the call number. */
    public static final String CALL_MARKER = "x-call";

    /** The name of the parameter whose schema varies. */
    public static final String VARYING_PARAMETER = "dryRun";

    /** Creates a source with no recorded call. */
    public StatefulSchemaSource() {}

    @Override
    protected JsonObject parameterSchema(JaxRsOperationDescriptor op, ParamDescriptor parameter, int call) {
        JsonObject schema = super.parameterSchema(op, parameter, call);
        if (CatalogResource.CREATE_ITEM.equals(op.operationId()) && VARYING_PARAMETER.equals(parameter.name())) {
            schema.put(CALL_MARKER, call);
        }
        return schema;
    }
}
