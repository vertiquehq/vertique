// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link OperationSchemaSource} that wraps a delegate, returns the delegate's result unchanged
 * (the same {@link OperationSchemas} instance, body provenance included), and records every schema
 * object it returns: the object itself, by reference, and a deep copy taken when returning it. The
 * recorded copies are what the request-validation gate received.
 *
 * <p>The source learns the operation only through its descriptor, which carries no resource class
 * or method, so recordings are keyed by operation id. The registrar asks once per operation and
 * mount build, so one operation id has one recording per verticle instance that built its mount.
 *
 * <p>One parameter schema of an operation can be replaced before the server starts ({@link
 * #replaceParameter}); for such an operation the source returns a copy of the delegate's result,
 * provenance kept, with that one schema replaced, and records the replacement.
 *
 * <p>Thread-safe: several mounts may be built concurrently on different event loops.
 */
public final class RecordingSchemaSource implements OperationSchemaSource {

    /** The member {@link #mutateEverything()} adds to every returned object. */
    public static final String ADDED_MEMBER = "zq7Added";

    /** The {@code pattern} value {@link #mutateEverything()} writes into every returned object. */
    public static final String ADDED_PATTERN = "^zq7$";

    private final OperationSchemaSource delegate;
    private final Map<Replacement, JsonObject> replacements = new ConcurrentHashMap<>();
    private final List<Recording> recordings = new ArrayList<>();

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose results are returned and recorded
     */
    public RecordingSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas returned = delegate.schemasFor(op, profile);
        OperationSchemas.Builder replaced = null;
        for (ParamDescriptor parameter : op.parameters()) {
            JsonObject replacement =
                    replacements.get(new Replacement(op.operationId(), parameter.location(), parameter.name()));
            if (replacement != null) {
                if (replaced == null) {
                    replaced = returned.toBuilder();
                }
                replaced.parameterSchema(parameter.location(), parameter.name(), replacement);
            }
        }
        if (replaced != null) {
            returned = replaced.build();
        }
        JsonObject body = returned.bodySchema().orElse(null);
        Map<InputKey, JsonObject> parameters = new LinkedHashMap<>();
        Map<InputKey, JsonObject> parameterCopies = new LinkedHashMap<>();
        for (ParamDescriptor parameter : op.parameters()) {
            OperationSchemas source = returned;
            source.parameterSchema(parameter.location(), parameter.name()).ifPresent(schema -> {
                InputKey key = new InputKey(parameter.location(), parameter.name());
                parameters.put(key, schema);
                parameterCopies.put(key, schema.copy());
            });
        }
        Recording recording = new Recording(
                op.operationId(),
                returned,
                body,
                body == null ? null : body.copy(),
                returned.bodySchemaProvenance(Object.class).orElse(null),
                Collections.unmodifiableMap(parameters),
                Collections.unmodifiableMap(parameterCopies));
        synchronized (recordings) {
            recordings.add(recording);
        }
        return returned;
    }

    /**
     * Replaces one parameter schema of an operation with the given object on every later call. Call
     * it before the server starts.
     *
     * @param operationId the operation id
     * @param location    the parameter's location
     * @param name        the parameter's declared name
     * @param schema      the schema returned instead of the delegate's, by reference
     */
    public void replaceParameter(String operationId, ParamLocation location, String name, JsonObject schema) {
        replacements.put(new Replacement(operationId, location, name), Objects.requireNonNull(schema, "schema"));
    }

    /**
     * Returns every recording, in call order.
     *
     * @return a snapshot list of the recordings
     */
    public List<Recording> recordings() {
        synchronized (recordings) {
            return List.copyOf(recordings);
        }
    }

    /**
     * Returns every recording of one operation id, in call order.
     *
     * @param operationId the operation id
     * @return the recordings, empty when the source was never asked for that operation
     */
    public List<Recording> recordings(String operationId) {
        return recordings().stream()
                .filter(recording -> recording.operationId().equals(operationId))
                .toList();
    }

    /**
     * Returns the first recording of one operation id.
     *
     * @param operationId the operation id
     * @return the first recording
     * @throws IllegalStateException if the source was never asked for that operation
     */
    public Recording recording(String operationId) {
        return recordings(operationId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no schemas were returned for operation '" + operationId + "'; recorded: "
                                + recordings().stream()
                                        .map(Recording::operationId)
                                        .distinct()
                                        .toList()));
    }

    /**
     * Returns every schema object returned so far, by reference, in call order, each recording's body
     * first and then its parameters in descriptor order.
     *
     * @return the returned objects, index-aligned with {@link #originals()}
     */
    public List<JsonObject> returned() {
        List<JsonObject> returned = new ArrayList<>();
        for (Recording recording : recordings()) {
            if (recording.body() != null) {
                returned.add(recording.body());
            }
            returned.addAll(recording.parameters().values());
        }
        return returned;
    }

    /**
     * Returns the deep copy of every schema object returned so far, taken when it was returned.
     *
     * @return the copies, index-aligned with {@link #returned()}
     */
    public List<JsonObject> originals() {
        List<JsonObject> originals = new ArrayList<>();
        for (Recording recording : recordings()) {
            if (recording.bodyCopy() != null) {
                originals.add(recording.bodyCopy());
            }
            originals.addAll(recording.parameterCopies().values());
        }
        return originals;
    }

    /**
     * Writes into every schema object returned so far: adds the member {@value #ADDED_MEMBER} and
     * sets {@code pattern} to {@value #ADDED_PATTERN}. The recorded copies are not touched.
     *
     * @return the number of objects written to
     */
    public int mutateEverything() {
        List<JsonObject> returned = returned();
        for (JsonObject schema : returned) {
            schema.put(ADDED_MEMBER, ADDED_MEMBER);
            schema.put("pattern", ADDED_PATTERN);
        }
        return returned.size();
    }

    /**
     * What the source returned for one operation on one call.
     *
     * @param operationId     the operation id
     * @param schemas         the very instance returned
     * @param body            the returned body schema, by reference, or {@code null} when none
     * @param bodyCopy        the body schema's deep copy taken when returning it, or {@code null}
     * @param bodyProvenance  the body's provenance, by reference, or {@code null} when none
     * @param parameters      the returned parameter schemas, by reference, in descriptor order
     * @param parameterCopies the parameter schemas' deep copies taken when returning them
     */
    public record Recording(
            String operationId,
            OperationSchemas schemas,
            @Nullable JsonObject body,
            @Nullable JsonObject bodyCopy,
            @Nullable Object bodyProvenance,
            Map<InputKey, JsonObject> parameters,
            Map<InputKey, JsonObject> parameterCopies) {

        /**
         * Returns the copy of one parameter schema, taken when it was returned.
         *
         * @param location the parameter's location
         * @param name     the parameter's declared name
         * @return the copy
         * @throws IllegalStateException if no schema was returned for that parameter
         */
        public JsonObject parameterCopy(ParamLocation location, String name) {
            JsonObject copy = parameterCopies.get(new InputKey(location, name));
            if (copy == null) {
                throw new IllegalStateException("no schema was returned for " + location + " '" + name
                        + "' of operation '" + operationId + "'");
            }
            return copy;
        }
    }

    /** Identifies one replaced parameter schema. */
    private record Replacement(String operationId, ParamLocation location, String name) {}
}
