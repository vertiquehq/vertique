// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles and runs {@code params} validation against the pinned official per-method request
 * schema (contract §4.9 — {@code schema/2026-07-28/schema.json}, upstream commit {@code
 * aa7306efa4dcc03a2a9f2f223e3b2d7a0c5f3ded}): {@link
 * McpProtocolCodec#validateOfficialParams} is the sole official-schema boundary, so structurally
 * invalid {@code cursor}, {@code name}, or {@code arguments} values are rejected before
 * negotiation, interceptors, tool lookup, or authorization.
 *
 * <p>The pinned schema document is now packaged in this module's own {@code main} resources at
 * {@value #SCHEMA_RESOURCE} — the exact same file {@code McpOfficialSchemaFixtureTest} and every
 * other schema-fixture consumer already reads from the classpath, not a second vendored copy: main
 * resources are on the test classpath by Maven's own default layout, so moving the file out of
 * {@code src/test/resources} into {@code src/main/resources} makes it available to production
 * without changing a single existing consumer's resource path.
 *
 * <p>One instance is composed fresh per {@link McpProtocolCodec} — mirroring {@link
 * McpSchemaRegistry}'s own one-instance-per-deployed-Context discipline — and compiles its three
 * per-method {@link Validator}s exactly once, at construction, never on the request path.
 *
 * <p>Each compiled validator is the full pinned document with its root {@code $ref} pointed at the
 * one {@code $defs} entry that is the schema-official {@code params} shape for that method —
 * {@code RequestParams} ({@code server/discover}), {@code PaginatedRequestParams} ({@code
 * tools/list}), {@code CallToolRequestParams} ({@code tools/call}) — so every internal {@code
 * #/$defs/...} reference (e.g. {@code RequestMetaObject}, {@code ClientCapabilities}) resolves
 * against the same self-contained document, never a second registration.
 *
 */
final class McpProtocolSchemaValidator {

    /**
     * The classpath location of the pinned official schema, identical to {@code
     * McpOfficialSchemaFixtureTest#SCHEMA_RESOURCE} — the same artifact, not a copy.
     */
    private static final String SCHEMA_RESOURCE = "/mcp/schema/2026-07-28/schema.json";

    /**
     * The schema-official {@code $defs} name of the {@code params} shape for each of this server's
     * three supported methods.
     */
    private static final Map<String, String> PARAMS_DEFINITION_BY_METHOD = Map.of(
            "server/discover", "RequestParams",
            "tools/list", "PaginatedRequestParams",
            "tools/call", "CallToolRequestParams");

    private static final JsonSchemaOptions SCHEMA_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** Converts a decoded Jackson {@link JsonNode} tree to the plain Java object graph {@link
     * Validator#validate(Object)} accepts — the same {@code convertValue(..., Object.class)} idiom
     * {@code McpToolRuntime} already uses for the same purpose. */
    private static final ObjectMapper CONVERTER = JsonMapper.builder().build();

    private final Map<String, Validator> validatorsByMethod;

    /**
     * Loads the pinned schema from the classpath and compiles one validator per supported method.
     *
     * @throws IllegalStateException if the pinned schema resource is not on the classpath
     * @throws UncheckedIOException if the pinned schema resource cannot be read
     */
    McpProtocolSchemaValidator() {
        JsonObject document = loadPinnedSchema();
        Map<String, Validator> validators = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : PARAMS_DEFINITION_BY_METHOD.entrySet()) {
            JsonObject perMethodSchema = document.copy();
            perMethodSchema.put("$ref", "#/$defs/" + entry.getValue());
            validators.put(entry.getKey(), Validator.create(JsonSchema.of(perMethodSchema), SCHEMA_OPTIONS));
        }
        this.validatorsByMethod = Map.copyOf(validators);
    }

    /**
     * Reports whether {@code params} satisfies the pinned official {@code params} schema for {@code
     * method}.
     *
     * @param method one of this server's supported methods
     * @param params the request's {@code params} object, as decoded
     * @return {@code true} when {@code params} satisfies the pinned schema, or when {@code method} is
     *     not one this validator compiled a schema for (defensive only — {@link McpProtocolCodec}
     *     never calls this with an unsupported method, since an unsupported method is already
     *     classified {@code -32601} before {@link McpProtocolCodec#validateOfficialParams} ever runs)
     */
    boolean isValid(String method, JsonNode params) {
        Validator validator = validatorsByMethod.get(method);
        return validator == null
                || validator
                        .validate(CONVERTER.convertValue(params, Object.class))
                        .getValid();
    }

    private static JsonObject loadPinnedSchema() {
        try (InputStream schemaStream = McpProtocolSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaStream == null) {
                throw new IllegalStateException(
                        "pinned official MCP schema resource not found on the " + "classpath: " + SCHEMA_RESOURCE);
            }
            return new JsonObject(new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException loadFailure) {
            throw new UncheckedIOException(loadFailure);
        }
    }
}
