// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.CanonicalSchema;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic {@link OperationSchemaSource} that counts its calls, in total and per operation id.
 *
 * <p>For each operation it returns the schema of the operation's first declared parameter (by the
 * parameter's Java type: {@code integer}, {@code boolean}, or {@code string}) and, when the
 * operation has a body, the body type's canonical input schema with its redaction manifest as the
 * body provenance, both from {@link AnnotationJsonSchemaGenerator#describe} of the input-direction
 * generator for the requested profile. Equal inputs always give equal schemas.
 */
public class CountingSchemaSource implements OperationSchemaSource {

    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, AtomicInteger> callsByOperation = new ConcurrentHashMap<>();

    /** Creates a source with no recorded call. */
    public CountingSchemaSource() {}

    @Override
    public final OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        int call = calls.incrementAndGet();
        callsByOperation
                .computeIfAbsent(op.operationId(), operationId -> new AtomicInteger())
                .incrementAndGet();
        OperationSchemas.Builder builder = OperationSchemas.builder();
        List<ParamDescriptor> parameters = op.parameters();
        if (!parameters.isEmpty()) {
            ParamDescriptor first = parameters.get(0);
            builder.parameterSchema(first.location(), first.name(), parameterSchema(op, first, call));
        }
        Optional<BodyDescriptor> body = op.body();
        if (body.isPresent()) {
            CanonicalSchema canonical = AnnotationJsonSchemaGenerator.forInputProfile(profile)
                    .describe(body.get().type());
            builder.bodySchema(new JsonObject(canonical.json()), canonical.redactionManifest());
        }
        return builder.build();
    }

    /**
     * Returns the schema of an operation's first declared parameter.
     *
     * @param op        the operation
     * @param parameter the operation's first declared parameter
     * @param call      this source's call number, starting at {@code 1}
     * @return a fresh parameter schema
     */
    protected JsonObject parameterSchema(JaxRsOperationDescriptor op, ParamDescriptor parameter, int call) {
        return typeSchema(parameter.type());
    }

    /**
     * Returns the total number of calls.
     *
     * @return the call count
     */
    public int calls() {
        return calls.get();
    }

    /**
     * Returns the number of calls for one operation id.
     *
     * @param operationId the operation id
     * @return the call count for that operation, {@code 0} when never called for it
     */
    public int calls(String operationId) {
        AtomicInteger count = callsByOperation.get(operationId);
        return count == null ? 0 : count.get();
    }

    /**
     * Returns the fixed JSON Schema for a parameter's Java type.
     *
     * @param type the parameter's Java type
     * @return {@code {"type":"integer"}}, {@code {"type":"boolean"}}, or {@code {"type":"string"}}
     */
    static JsonObject typeSchema(Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return new JsonObject().put("type", "integer");
        }
        if (type == boolean.class || type == Boolean.class) {
            return new JsonObject().put("type", "boolean");
        }
        return new JsonObject().put("type", "string");
    }
}
