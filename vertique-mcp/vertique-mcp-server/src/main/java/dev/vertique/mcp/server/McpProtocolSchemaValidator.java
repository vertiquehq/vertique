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
 * aa7306efa4dcc03a2a9f2f223e3b2d7a0c5f3ded}), closing R08 (merge blocker 1): {@link
 * McpProtocolCodec#validateNegotiation} previously checked only selected {@code _meta} fields, the
 * three required headers, and the reserved MRTR fields — never the pinned schema itself — so a
 * structurally invalid {@code cursor} or {@code name} reached interceptors, tool lookup, or
 * authorization instead of being rejected at negotiation. ({@code arguments} also reached them, and
 * by design still does — see this class's own note on that field, below.)
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
 * <p><strong>{@code tools/call}'s {@code arguments} property is deliberately excluded</strong> from
 * the compiled {@code tools/call} validator (see {@link #stripArgumentsTypeConstraint}). A present,
 * non-null, non-object {@code arguments} value is already rejected — deliberately, not by omission —
 * by {@code McpRequestDispatcher}'s pre-R08 stage-6 input pipeline (contract §4.7 — "Input JSON
 * Schema, input-processing... failures are safe text-only tool results with {@code isError=true}";
 * the review-finding remediation {@code McpToolCallMalformedArgumentsIT} pins the exact wire shape: a
 * bounded SSE tool-error result, reached only after registry lookup and the policy enforcer have
 * already run). Applying the pinned schema's {@code arguments: type=object} constraint at negotiation
 * too would reject the identical condition earlier, with a different wire response (a bare HTTP 400
 * instead of an SSE-framed tool error) — a consumer-visible behavior change §4.7 already froze
 * differently, and outside this repair-only slice's authority to make. Every other {@code
 * CallToolRequestParams} fact — {@code _meta}, the required {@code name} string — is still validated
 * here at negotiation, since neither has an equivalent pre-existing downstream contract.
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
            if ("tools/call".equals(entry.getKey())) {
                stripArgumentsTypeConstraint(perMethodSchema);
            }
            perMethodSchema.put("$ref", "#/$defs/" + entry.getValue());
            validators.put(entry.getKey(), Validator.create(JsonSchema.of(perMethodSchema), SCHEMA_OPTIONS));
        }
        this.validatorsByMethod = Map.copyOf(validators);
    }

    /**
     * Removes the {@code arguments} property entry from this per-method-schema copy's {@code
     * CallToolRequestParams} definition, so the compiled {@code tools/call} validator no longer types
     * it as an object — see the class-level javadoc for why. Mutates only {@code perMethodSchema}, a
     * copy freshly made for one method by {@link #McpProtocolSchemaValidator()}; the shared pinned
     * {@link #loadPinnedSchema() document} this copy was taken from is never touched, so every other
     * method's compiled validator, and any future copy, still sees the pinned schema unmodified.
     */
    private static void stripArgumentsTypeConstraint(JsonObject perMethodSchema) {
        perMethodSchema
                .getJsonObject("$defs")
                .getJsonObject("CallToolRequestParams")
                .getJsonObject("properties")
                .remove("arguments");
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
     *     classified {@code -32601} before {@link McpProtocolCodec#validateNegotiation} ever runs)
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
